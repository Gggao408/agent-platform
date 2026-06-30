-- ============================================
-- Agent Platform 数据库初始化脚本
-- ============================================

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- ===== 会话表 =====
CREATE TABLE IF NOT EXISTS conversations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       VARCHAR(500) NOT NULL DEFAULT '新对话',
    model       VARCHAR(100) NOT NULL DEFAULT 'qwen-plus',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ===== 消息表 =====
CREATE TABLE IF NOT EXISTS messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role            VARCHAR(20)  NOT NULL,   -- system / user / assistant / tool
    content         TEXT,                     -- 文本内容（assistant 最终回答 / user 输入）
    tool_calls      JSONB,                    -- assistant 的工具调用记录 [{id, name, arguments}]
    tool_call_id    VARCHAR(100),             -- tool 角色消息对应的调用 ID
    token_count     INTEGER DEFAULT 0,        -- 该消息的 token 估算数
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_messages_conv_id ON messages(conversation_id);
CREATE INDEX IF NOT EXISTS idx_messages_created_at ON messages(conversation_id, created_at);

-- ===== 知识库文档表 =====
CREATE TABLE IF NOT EXISTS documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_name       VARCHAR(500) NOT NULL,
    file_type       VARCHAR(50)  NOT NULL,   -- pdf / docx / txt / md
    file_size       BIGINT NOT NULL DEFAULT 0,
    file_path       VARCHAR(1000),            -- 原始文件存储路径
    chunk_count     INTEGER NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / PROCESSING / COMPLETED / FAILED
    error_message   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ===== 文档切块表（PGVector 向量存储）=====
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index     INTEGER NOT NULL,         -- 块序号（从0开始）
    chunk_text      TEXT NOT NULL,            -- 块文本内容
    embedding       vector(1024),             -- 向量（阿里百炼 text-embedding-v3: 1024维）
    metadata        JSONB,                    -- 额外元数据（页码、章节等）
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON knowledge_chunks(document_id);
-- PGVector IVFFlat 索引（余弦相似度检索）
-- 数据量超过几千条后建议创建，初始化时先注释掉
-- CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON knowledge_chunks
--     USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ===== 更新会话时间触发器 =====
CREATE OR REPLACE FUNCTION update_conversation_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE conversations SET updated_at = NOW() WHERE id = NEW.conversation_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_message_insert
    AFTER INSERT ON messages
    FOR EACH ROW
    EXECUTE FUNCTION update_conversation_timestamp();
