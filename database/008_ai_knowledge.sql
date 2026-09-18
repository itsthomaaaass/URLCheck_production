-- 008: the ingestion ledger for the AI assistant's knowledge base (RAG).
--
-- One row per knowledge document, holding the SHA-256 of the content that was
-- last embedded. Ingestion compares the hash of every file under
-- src/main/resources/ai/knowledge/ against this table and re-embeds only what
-- changed, which is what makes a restart free and a deploy cost only the files
-- that were edited. Qdrant holds the vectors, but this table is what makes the
-- work incremental. The design is in docs/RAG_design.md.
--
-- Run once against the url_monitor database:
--   docker exec -i url-change-monitor-mysql mysql -uroot -proot < 008_ai_knowledge.sql
--
-- Idempotent, so re-running the script is harmless.

USE url_monitor;

CREATE TABLE IF NOT EXISTS ai_knowledge_document (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    -- Path relative to ai/knowledge/, e.g. business/change-detection.md. The
    -- natural key: a document is identified by where it lives, so a rename is a
    -- delete plus an add.
    document     VARCHAR(255)    NOT NULL,
    -- SHA-256 of the file's bytes, hex. This is the whole change-detection
    -- scheme: a row whose hash still matches the file means the document does
    -- not have to be embedded again.
    content_hash CHAR(64)        NOT NULL,
    -- How many vectors the document was split into, kept for support and for
    -- spotting a chunker change that needs a re-ingest.
    chunk_count  INT UNSIGNED    NOT NULL DEFAULT 0,
    embedded_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ai_knowledge_document (document)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;