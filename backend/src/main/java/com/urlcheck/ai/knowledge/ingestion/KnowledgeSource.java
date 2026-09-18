package com.urlcheck.ai.knowledge.ingestion;

/**
 * One knowledge document as it exists on disk, with the hash that decides
 * whether it has to be embedded again.
 *
 * @param path     path relative to {@code ai/knowledge/}, e.g.
 *                 {@code business/change-detection.md}; the document's identity
 * @param category first directory of that path, e.g. {@code business}; kept as
 *                 metadata so retrieval can be narrowed later
 * @param content  the file's text, which is what gets chunked
 * @param hash     SHA-256 of the file's bytes, hex
 */
record KnowledgeSource(String path, String category, String content, String hash) {

    /** The file's own name, with the category left out. */
    String fileName() {
        return this.path.substring(this.path.lastIndexOf('/') + 1);
    }
}