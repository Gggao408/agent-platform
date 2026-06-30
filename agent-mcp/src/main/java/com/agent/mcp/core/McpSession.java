package com.agent.mcp.core;

import com.agent.common.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 会话 — 管理一个 MCP Server 进程的生命周期和通信。
 * <p>
 * 每个 McpSession 对应一个独立的 Java 进程（MCP Server），
 * 通过该进程的 stdin/stdout 收发 JSON-RPC 消息。
 *
 * <h3>你需要实现的内容：</h3>
 * <ol>
 *   <li>{@link #start()} — 启动 MCP Server 子进程</li>
 *   <li>{@link #send(JsonRpcMessage)} — 发送 JSON-RPC 请求并等待响应</li>
 *   <li>{@link #fetchTools()} — 调用 tools/list 获取工具列表</li>
 *   <li>{@link #close()} — 关闭进程和 IO 流</li>
 * </ol>
 */
public class McpSession implements Closeable {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final String serverName;
    private final String[] command;       // 启动子进程的命令
    private final JsonRpcCodec codec;
    private final ObjectMapper objectMapper;

    private Process process;
    private BufferedWriter writer;        // → 进程 stdin
    private BufferedReader reader;        // ← 进程 stdout
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);

    /** 已从该 Server 获取的工具定义缓存 */
    private volatile List<ToolDefinition> tools;

    public McpSession(String serverName, String... command) {
        this.serverName = serverName;
        this.command = command;
        this.codec = new JsonRpcCodec();
        this.objectMapper = new ObjectMapper();
    }

    // ==========================================================
    // 进程生命周期（你来写）
    // ==========================================================

    /**
     * TODO: 启动 MCP Server 子进程。
     *
     * <h3>实现步骤：</h3>
     * <ol>
     *   <li>用 {@link ProcessBuilder} 启动 command 指定的子进程</li>
     *   <li>获取进程的 stdin（写入）和 stdout（读取）</li>
     *   <li>stderr 用独立线程消费并打日志（避免缓冲区阻塞）</li>
     * </ol>
     *
     * <h3>实现要点：</h3>
     * <ul>
     *   <li>如果进程已经启动，先 close 再重启</li>
     *   <li>stderr 消费线程应设为 daemon</li>
     *   <li>启动失败抛 {@link com.agent.common.exception.AgentException}</li>
     * </ul>
     *
     * <pre>{@code
     * ProcessBuilder pb = new ProcessBuilder(command);
     * pb.redirectErrorStream(false);  // 不要合并 stderr 到 stdout
     * this.process = pb.start();
     * this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
     * this.reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
     *
     * // 消费 stderr
     * Thread stderrThread = new Thread(() -> {
     *     try (BufferedReader errReader = new BufferedReader(
     *             new InputStreamReader(process.getErrorStream()))) {
     *         String line;
     *         while ((line = errReader.readLine()) != null) {
     *             log.debug("[{}] {}", serverName, line);
     *         }
     *     } catch (IOException ignored) {}
     * });
     * stderrThread.setDaemon(true);
     * stderrThread.start();
     * }</pre>
     */
    public void start()throws IOException{
       ProcessBuilder pb =new ProcessBuilder(command);
       pb.redirectErrorStream(false);
       this.process=pb.start();
       this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
       this.reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        Thread stderrThread = new Thread(() -> {
        try (BufferedReader errReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = errReader.readLine()) != null) {
                log.debug("[{}] {}", serverName, line);
            }
        } catch (IOException ignored) {
            //stderr 流关闭，线程结束
        }
    });
    stderrThread.setDaemon(true);
    stderrThread.start();
}

    // ==========================================================
    // 消息收发（你来写）
    // ==========================================================

    /**
     * TODO: 发送 JSON-RPC 请求并等待响应。
     *
     * <h3>实现步骤：</h3>
     * <ol>
     *   <li>给消息设置自增 id</li>
     *   <li>编码为 JSON 写入 writer → flush</li>
     *   <li>从 reader 读取一行 → 解码为响应</li>
     *   <li>校验响应的 id 与请求一致</li>
     * </ol>
     *
     * <h3>实现要点：</h3>
     * <ul>
     *   <li>这是同步阻塞调用——Agent 引擎在等待工具结果时必须阻塞</li>
     *   <li>需要超时机制（推荐 30 秒）</li>
     *   <li>进程崩溃时 reader.readLine() 返回 null → 抛异常</li>
     * </ul>
     *
     * @param message 请求消息（method 和 params 已设置）
     * @return 响应消息
     */
    public JsonRpcMessage send(JsonRpcMessage message) throws IOException{
      String Id = String.valueOf(requestIdCounter.getAndIncrement());
      JsonRpcMessage request = JsonRpcMessage.request(message.getMethod(),message.getParams(),Id);
      writer.write(codec.encode(request));
      writer.newLine();
      writer.flush();
      String responseLine = reader.readLine();
      if(responseLine == null){
        throw new IOException("子进程退出"+serverName);
      }
      return codec.decode(responseLine);
    }

    /**
     * TODO: 调用 tools/list 获取工具列表。
     *
     * <p>发送：
     * <pre>{@code
     * {"jsonrpc": "2.0", "method": "tools/list", "id": "1"}
     * }</pre>
     *
     * <p>响应：
     * <pre>{@code
     * {"jsonrpc": "2.0", "result": {"tools": [...]}, "id": "1"}
     * }</pre>
     *
     * @return 工具定义列表
     */
    public List<ToolDefinition> fetchTools() throws IOException {
        // 已缓存则直接返回，避免重复 RPC 调用
        if (this.tools != null) {
            return this.tools;
        }

        JsonRpcMessage request = JsonRpcMessage.request(
        "tools/list",
        null,
        String.valueOf(requestIdCounter.getAndIncrement())
      );
      JsonRpcMessage response = send(request);
       if(response.isError()){
        throw new RuntimeException("获取工具列表失败 [" + serverName + "]: " + response.getError().getMessage());}

        JsonNode toolsNode = response.getResult().get("tools");
        if(toolsNode == null|| !toolsNode.isArray()){
                    throw new RuntimeException(
            "tools/list 响应格式异常 [" + serverName + "]: 缺少 tools 数组"
        );
        }
        List<ToolDefinition> result = new ArrayList<>();
        for(JsonNode toolNode :toolsNode){
            ToolDefinition td = ToolDefinition.builder()
            .name(toolNode.get("name").asText())
            .description(toolNode.get("description").asText())
            .parameters(toolNode.get("inputSchema"))
            .build();
            result.add(td);
        }
        this.tools = result;
        return result;

        
       }


    /**
     * 调用工具（带缓存）。
     *
     * @param toolName  工具名称
     * @param arguments 参数 JSON
     * @return 执行结果 JSON
     */
    public JsonNode callTool(String toolName, JsonNode arguments) throws IOException{
        ObjectMapper mapper = new ObjectMapper();
        var params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);

        JsonRpcMessage request = JsonRpcMessage.request("tools/call", params,
                String.valueOf(requestIdCounter.getAndIncrement()));
        JsonRpcMessage response = send(request);

        if (response.isError()) {
            throw new RuntimeException("MCP 调用失败: " + response.getError().getMessage());
        }

        return response.getResult();
    }

   /* 
     *
     * <ol>
     *   <li>关闭 writer（发送 EOF 信号给子进程）</li>
     *   <li>关闭 reader</li>
     *   <li>销毁子进程（先 graceful destroy，超时后 destroyForcibly）</li>
     *   <li>等待进程退出</li>
     * </ol>
     */
    @Override
    public void close() {
       if(writer!=null){
        try {
            writer.close();
        } catch (IOException e) {
          log.warn("关闭 writer 失败 [{}]", serverName, e);
        }
       }
       if(reader!=null){
        try {
       reader.close();     
        } catch (IOException e) {
            log.warn("关闭 writer 失败 [{}]", serverName, e);
        }
       }
       if(process!=null &&process.isAlive()){
        process.destroy();
        try {
            boolean terminsted = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if(!terminsted){
                log.warn("子进程未响应 destroy,强制终止 [{}]", serverName);
                process.destroyForcibly();
            }
        }  catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
       }
       log.info("McpSession 已关闭 [{}]", serverName);
    }

    // ===== getters =====

    public String getServerName() {
        return serverName;
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }
}
