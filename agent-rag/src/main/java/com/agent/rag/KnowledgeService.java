package com.agent.rag;

import com.agent.llm.LlmService;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 知识库服务 — RAG 管道的编排器。
 * <p>
 * 协调文档解析 → 切分 → 向量化 → 存储的全流程，
 * 以及检索时的向量检索 → 重排序 → 上下文拼接。
 *
 * <h3>你需要实现的内容：</h3>
 * <ol>
 *   <li>{@link #uploadDocument(Path, String)} — 文档上传全流程</li>
 *   <li>{@link #search(String, int)} — 知识库检索</li>
 * </ol>
 */
@Slf4j
public class KnowledgeService {

    private final DocumentParser documentParser;
    private final ChunkSplitter chunkSplitter;
    private final LlmService llmService;          // 用于向量化
    private final VectorStoreService vectorStore;
    private final RerankService rerankService;

    public KnowledgeService(DocumentParser documentParser,
                            ChunkSplitter chunkSplitter,
                            LlmService llmService,
                            VectorStoreService vectorStore,
                            RerankService rerankService) {
        this.documentParser = documentParser;
        this.chunkSplitter = chunkSplitter;
        this.llmService = llmService;
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
    }

    /**
     * TODO: 文档上传全流程。
     *
     * <h3>完整流程：</h3>
     * <pre>
     * 1. documentParser.parse(filePath) → 原始文本
     * 2. chunkSplitter.split(text) → 文本块列表
     * 3. 对每块调用 llmService.embed(chunk.text()) → 向量列表
     * 4. vectorStore.insertChunks(documentId, chunks, embeddings) → 写入 PGVector
     * 5. 更新 documents 表状态为 COMPLETED
     * </pre>
     *
     * <h3>注意：</h3>
     * <ul>
     *   <li>embed 调用是批量还是逐条？百炼 Embedding API 支持批量输入，
     *       但需要注意单次调用的 token 上限</li>
     *   <li>整个流程应异步执行（文档上传后立即返回，后台处理）</li>
     *   <li>异常处理：任何步骤失败都应更新 document 状态为 FAILED</li>
     * </ul>
     *
     * @param filePath 文档文件路径
     * @param fileName 原始文件名
     * @return 文档 ID
     */
    public UUID uploadDocument(Path filePath, String fileName) {
      UUID documentId = UUID.randomUUID();
      try {
        //0. 先在 documents 表插入记录
        vectorStore.insertDocument(documentId, fileName, filePath.toString());
        //tika解析文档
        DocumentParser.ParseResult parsed = documentParser.parse(filePath);
        log.info("文档解析完成: {}, 方式: {}, 字数: {}", fileName, parsed.method(), parsed.text().length());
        //文档切块
       List<ChunkSplitter.Chunk> chunks = chunkSplitter.split(parsed.text());
       log.info("文档切块完成: {}, 块数: {}", fileName, chunks.size());
       //文本向量化
       List<float[]> embeddings = new ArrayList<>();
       for(ChunkSplitter.Chunk chunk : chunks){
        embeddings.add(llmService.embed(chunk.text()));
       }
       //存入pgvector
       vectorStore.insertChunks(documentId, chunks, embeddings);
       log.info("文档入库完成： {}", fileName);
       return documentId;
      } catch (Exception e) {
       log.error("文档处理失败: {}, 原因: {}", fileName, e.getMessage(), e);
    throw new RuntimeException("文档处理失败: " + fileName + " - " + e.getMessage(), e);
      }
    }

    /**
     * TODO: 知识库检索（两阶段）。
     *
     * <h3>完整流程：</h3>
     * <pre>
     * 1. llmService.embed(query) → 查询向量
     * 2. vectorStore.search(queryEmbedding, 20) → 粗排 top-20
     * 3. rerankService.rerank(query, candidates, topK) → 精排 top-K
     * 4. 返回精排后的结果
     * </pre>
     *
     * <h3>返回格式（用于拼接 System Prompt）：</h3>
     * <pre>{@code
     * 参考资料：
     * ---
     * [来源1] ...chunk_text...
     * ---
     * [来源2] ...chunk_text...
     * ---
     * }</pre>
     *
     * @param query 用户查询
     * @param topK  最终返回的文档片段数（建议 3）
     * @return 格式化后的参考资料文本
     */
    public String search(String query, int topK) {
      //bi-encoder
      float[] queryEmbedding= llmService.embed(query);
      List<VectorStoreService.SearchResult> candidates = vectorStore.search(queryEmbedding, 20);
      if(candidates.isEmpty()){
        return "未找到！";
      }
      // 尝试精排，失败则回退到粗排结果
      List<VectorStoreService.SearchResult> finalResults;
      try {
          List<RerankService.RerankResult> reranked = rerankService.rerank(query, candidates, Math.min(topK,candidates.size()));
          finalResults = reranked.stream().map(RerankService.RerankResult::chunk).toList();
      } catch (Exception e) {
          log.warn("Rerank 失败，回退到向量检索结果: {}", e.getMessage());
          finalResults = candidates.subList(0, Math.min(topK, candidates.size()));
      }
      StringBuilder sb = new StringBuilder("请基于以下资料回答问题：\n\n");
      for(int i =0; i< finalResults.size();i++){
        sb.append("---\n");
     sb.append("[来源").append(i + 1).append("] ").append(finalResults.get(i).chunkText()).append("\n");
    }
    return sb.toString();
      }
    }

