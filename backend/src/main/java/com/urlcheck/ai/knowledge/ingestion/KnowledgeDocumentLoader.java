package com.urlcheck.ai.knowledge.ingestion;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Finds the knowledge documents and hashes them.
 *
 * <p>The files ship inside the application jar under {@code ai/knowledge/}, one
 * directory per category, and they are the source of truth: Qdrant only holds a
 * searchable copy. Because they are packaged with the code, the knowledge and
 * the implementation it describes are always the same Git revision.
 *
 * <p>The hash is taken over the file's bytes rather than its parsed text, so a
 * change that only touches whitespace or a line ending still counts as a change.
 * That is the safe direction: re-embedding a file costs milliseconds on a local
 * model, while quietly serving a stale description costs a wrong answer.
 */
class KnowledgeDocumentLoader {

    static final String DEFAULT_PATTERN = "classpath*:ai/knowledge/**/*.md";

    private static final String MARKDOWN_SUFFIX = ".md";

    private final ResourcePatternResolver resolver;
    private final String pattern;
    private final String root;

    KnowledgeDocumentLoader(ResourcePatternResolver resolver) {
        this(resolver, DEFAULT_PATTERN);
    }

    KnowledgeDocumentLoader(ResourcePatternResolver resolver, String pattern) {
        this.resolver = resolver;
        this.pattern = pattern;
        this.root = rootOf(pattern);
    }

    List<KnowledgeSource> load() {
        List<KnowledgeSource> sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Resource[] resources;
        try {
            resources = this.resolver.getResources(this.pattern);
        }
        catch (IOException ex) {
            throw new IllegalStateException("The knowledge documents could not be listed from "
                    + this.pattern, ex);
        }

        for (Resource resource : resources) {
            String fileName = resource.getFilename();
            if (fileName == null || !fileName.endsWith(MARKDOWN_SUFFIX)) {
                // The pattern picks up directories along the way; only files matter.
                continue;
            }

            String path = pathOf(resource);
            if (!seen.add(path)) {
                throw new IllegalStateException("Two knowledge documents resolve to the same path ("
                        + path + "): the ledger is keyed by that path, so they cannot be told apart");
            }

            byte[] bytes;
            try {
                bytes = resource.getContentAsByteArray();
            }
            catch (IOException ex) {
                throw new IllegalStateException("Knowledge document " + path + " could not be read", ex);
            }

            sources.add(new KnowledgeSource(path, categoryOf(path),
                    new String(bytes, StandardCharsets.UTF_8), sha256(bytes)));
        }

        // Stable order keeps logs and tests predictable; nothing depends on it.
        sources.sort(Comparator.comparing(KnowledgeSource::path));
        return sources;
    }

    /**
     * The document's path relative to the knowledge root, which is what the
     * ledger stores. Taken from the URL rather than resolved against a base,
     * because a resource inside a jar has no filesystem path to resolve.
     */
    private String pathOf(Resource resource) {
        String url = String.valueOf(resource);
        try {
            url = resource.getURL().toString();
        }
        catch (IOException ignored) {
            // Fall back to the resource's own description below.
        }

        int at = url.lastIndexOf(this.root);
        String path = at >= 0 ? url.substring(at + this.root.length()) : String.valueOf(resource.getFilename());
        return URLDecoder.decode(path, StandardCharsets.UTF_8);
    }

    private static String categoryOf(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** The part of the pattern before the first wildcard: where the documents start. */
    private static String rootOf(String pattern) {
        String withoutScheme = pattern.substring(pattern.indexOf(':') + 1);
        int wildcard = withoutScheme.indexOf('*');
        String root = wildcard < 0 ? withoutScheme : withoutScheme.substring(0, wildcard);
        return root.endsWith("/") ? root : root + "/";
    }

    static String sha256(byte[] content) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", ex);
        }
        return toHex(digest.digest(content));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }
}