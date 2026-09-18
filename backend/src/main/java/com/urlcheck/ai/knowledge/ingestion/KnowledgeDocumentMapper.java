package com.urlcheck.ai.knowledge.ingestion;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * SQL for {@code ai_knowledge_document}: which version of each knowledge
 * document has already been embedded. See {@code database/008_ai_knowledge.sql}.
 */
@Mapper
public interface KnowledgeDocumentMapper {

    String COLUMNS = "id, document, content_hash, chunk_count, embedded_at";

    @Select("SELECT " + COLUMNS + " FROM ai_knowledge_document")
    List<KnowledgeDocumentState> findAll();

    /**
     * Records the version that is now in the vector store.
     *
     * <p>An upsert rather than an insert or update: the caller has just decided
     * the document changed and does not care whether it was seen before. The
     * {@code VALUES()} form matches the other migrations in this project.
     */
    @Insert("INSERT INTO ai_knowledge_document (document, content_hash, chunk_count)"
            + " VALUES (#{document}, #{contentHash}, #{chunkCount})"
            + " ON DUPLICATE KEY UPDATE content_hash = VALUES(content_hash),"
            + " chunk_count = VALUES(chunk_count), embedded_at = NOW(6)")
    int upsert(KnowledgeDocumentState state);

    /** Dropped documents stop being tracked, so their vectors are not orphaned. */
    @Delete("DELETE FROM ai_knowledge_document WHERE document = #{document}")
    int deleteByDocument(String document);
}