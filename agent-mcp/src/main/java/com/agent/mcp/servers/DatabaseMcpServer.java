package com.agent.mcp.servers;

import com.agent.mcp.core.McpServer;
import com.agent.mcp.core.SchemaFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.*;

/**
 * 数据库 MCP Server — 暴露 SQL 查询和表管理工具。
 * <p>
 * 使用 H2 内嵌数据库作为演示（零配置，进程启动即用）。
 * 实际生产环境可改为 PostgreSQL / MySQL。
 *
 * <h3>暴露的工具：</h3>
 * <ul>
 *   <li>{@code query_database} — 执行只读 SQL 查询</li>
 *   <li>{@code list_tables} — 列出所有表</li>
 *   <li>{@code describe_table} — 查看表结构</li>
 * </ul>
 *
 * <h3>运行方式：</h3>
 * <pre>{@code
 * java -cp agent-mcp.jar com.agent.mcp.servers.DatabaseMcpServer
 * }</pre>
 *
 * <h3>你需要实现的内容：</h3>
 * {@link #initDataSource()} — 初始化数据源
 * {@link #main(String[])} — 启动入口
 */
public class DatabaseMcpServer extends McpServer {

    private DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public DatabaseMcpServer() {
        initDataSource();
        registerTools();
    }

    // ==========================================================
    // 数据源初始化（你来写）
    // ==========================================================

    /**
     * TODO: 初始化 H2 内嵌数据库。
     *
     * <h3>实现提示：</h3>
     * <pre>{@code
     * HikariConfig config = new HikariConfig();
     * config.setJdbcUrl("jdbc:h2:mem:agentdb;DB_CLOSE_DELAY=-1");
     * config.setUsername("sa");
     * config.setPassword("");
     * this.dataSource = new HikariDataSource(config);
     *
     * // 创建示例表
     * try (Connection conn = dataSource.getConnection();
     *      Statement stmt = conn.createStatement()) {
     *     stmt.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100), email VARCHAR(200))");
     *     stmt.execute("INSERT INTO users VALUES (1, '张三', 'zhangsan@example.com')");
     *     stmt.execute("INSERT INTO users VALUES (2, '李四', 'lisi@example.com')");
     * }
     * }</pre>
     */
    private void initDataSource() {
        HikariConfig config = new HikariConfig();
       config.setJdbcUrl("jdbc:h2:mem:agentdb;DB_CLOSE_DELAY=-1");
    config.setUsername("sa");
    config.setPassword("");
    this.dataSource = new HikariDataSource(config);
     try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100), email VARCHAR(200))");
        stmt.execute("INSERT INTO users VALUES (1, '张三', 'zhangsan@example.com')");
        stmt.execute("INSERT INTO users VALUES (2, '李四', 'lisi@example.com')");
        stmt.execute("CREATE TABLE orders (id INT PRIMARY KEY, user_id INT, product VARCHAR(200), amount DECIMAL(10,2))");
        stmt.execute("INSERT INTO orders VALUES (1, 1, '笔记本电脑', 6999.00)");
        stmt.execute("INSERT INTO orders VALUES (2, 2, '机械键盘', 399.00)");
        log.info("H2 初始化完成:users(2行), orders(2行)");
    } catch (SQLException e) {
        throw new RuntimeException("H2 初始化失败", e);
    }
    }

    // ==========================================================
    // 工具注册
    // ==========================================================

    private void registerTools() {
        // 工具1：执行 SQL 查询
        registerTool("query_database",
                "执行只读SQL查询(仅允许SELECT语句),da返回JSON格式的结果集",
                SchemaFactory.object()
                        .add("sql", SchemaFactory.string("要执行的SELECT查询语句"))
                        .required("sql")
                        .build(),
                this::executeQuery
        );

        // 工具2：列出所有表
        registerTool("list_tables",
                "列出数据库中的所有表及其行数",
                SchemaFactory.object().build(),
                this::listTables
        );

        // 工具3：查看表结构
        registerTool("describe_table",
                "查看指定表的结构（列名、类型、是否可空）",
                SchemaFactory.object()
                        .add("table_name", SchemaFactory.string("要查看的表名"))
                        .required("table_name")
                        .build(),
                this::describeTable
        );
    }

    // ==========================================================
    // 工具实现（你来写）
    // ==========================================================

    /**
     * TODO: 执行 SELECT 查询并返回 JSON 结果集。
     *
     * <h3>安全检查（必须做）：</h3>
     * <ul>
     *   <li>只允许 SELECT 语句</li>
     *   <li>禁止 DROP / DELETE / INSERT / UPDATE / TRUNCATE / ALTER / CREATE</li>
     *   <li>SQL 关键字用大写判断</li>
     * </ul>
     *
     * <h3>返回格式：</h3>
     * <pre>{@code
     * {
     *   "columns": ["id", "name", "email"],
     *   "rows": [
     *     {"id": 1, "name": "张三", "email": "zhangsan@example.com"},
     *     {"id": 2, "name": "李四", "email": "lisi@example.com"}
     *   ],
     *   "row_count": 2
     * }
     * }</pre>
     */
    private JsonNode executeQuery(JsonNode arguments) throws Exception {
       rivate JsonNode executeQuery(JsonNode arguments) throws Exception {
    String sql = arguments.get("sql").asText().trim();

    // 安全检查
    String upperSql = sql.toUpperCase();
    String[] forbidden = {"DROP", "DELETE", "INSERT", "UPDATE", "TRUNCATE", "ALTER", "CREATE"};
    for (String kw : forbidden) {
        if (upperSql.contains(kw)) {
            throw new IllegalArgumentException("不允许的关键字: " + kw);
        }
    }
    if (!upperSql.startsWith("SELECT")) {
        throw new IllegalArgumentException("仅允许 SELECT 查询");
    }

    try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(sql)) {

        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        ArrayNode columns = mapper.createArrayNode();
        for (int i = 1; i <= colCount; i++) {
            columns.add(meta.getColumnName(i));
        }

        ArrayNode rows = mapper.createArrayNode();
        while (rs.next()) {
            ObjectNode row = mapper.createObjectNode();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnName(i), String.valueOf(rs.getObject(i)));
            }
            rows.add(row);
        }

        ObjectNode result = mapper.createObjectNode();
        result.set("columns", columns);
        result.set("rows", rows);
        result.put("row_count", rows.size());
        return result;
    }
}

    /**
     * TODO: 列出所有表及其行数。
     *
     * <p>H2 中查询所有表：
     * <pre>{@code
     * SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
     * WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'TABLE'
     * }</pre>
     */
    private JsonNode listTables(JsonNode arguments) throws Exception {
         String sql = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='TABLE'";
    try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(sql)) {

        ArrayNode tables = mapper.createArrayNode();
        while (rs.next()) {
            String tableName = rs.getString("TABLE_NAME");
            int count = 0;
            try (Statement s2 = conn.createStatement();
                 ResultSet rs2 = s2.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                if (rs2.next()) count = rs2.getInt(1);
            }
            ObjectNode t = mapper.createObjectNode();
            t.put("table_name", tableName);
            t.put("row_count", count);
            tables.add(t);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("tables", tables);
        return result;
    }

    /**
     * TODO: 查看指定表的结构。
     *
     * <p>H2 中查询列信息：
     * <pre>{@code
     * SELECT COLUMN_NAME, TYPE_NAME, NULLABLE
     * FROM INFORMATION_SCHEMA.COLUMNS
     * WHERE TABLE_NAME = ?
     * }</pre>
     */
    private JsonNode describeTable(JsonNode arguments) throws Exception {
        String tableName = arguments.get("table_name").asText();
    String sql = "SELECT COLUMN_NAME, TYPE_NAME, NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=?";
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setString(1, tableName.toUpperCase());
        ResultSet rs = stmt.executeQuery();

        ArrayNode columns = mapper.createArrayNode();
        while (rs.next()) {
            ObjectNode col = mapper.createObjectNode();
            col.put("column_name", rs.getString("COLUMN_NAME"));
            col.put("type", rs.getString("TYPE_NAME"));
            col.put("nullable", rs.getString("NULLABLE"));
            columns.add(col);
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("table_name", tableName);
        result.set("columns", columns);
        return result;
    }
    }

    // ===== 入口 =====

    /**
     * TODO: MCP Server 启动入口。
     * <pre>{@code
     * new DatabaseMcpServer().start();
     * }</pre>
     */
    public static void main(String[] args) {
       new DatabaseMcpServer().start();
    }
}
