package com.urlcheck.ai.knowledge.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.Assert;

/**
 * Splits a knowledge document into the pieces that get embedded.
 *
 * <p>Markdown is split on its headings, not by character count: a heading is
 * where the document changes subject, and a chunk that mixes two subjects
 * retrieves badly for both. A section that is still too long is then broken
 * between paragraphs, and a single oversized paragraph is cut at the budget, so
 * no chunk is larger than configured.
 *
 * <p>Every chunk is prefixed with its document title and heading path, so a
 * fragment that is retrieved on its own still says what it is about. That prefix
 * is what the budget does not cover, because it is short, fixed, and worth more
 * to retrieval than the characters it costs.
 *
 * <p>Headings inside fenced code blocks are text, not headings, so a fenced
 * sample that starts a line with {@code #} is left alone.
 */
class KnowledgeChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{2,6})\\s+(.*)$");
    private static final Pattern BLANK_LINE = Pattern.compile("\\n\\s*\\n");
    private static final String FENCE = "```";

    private final int maxChunkCharacters;

    KnowledgeChunker(int maxChunkCharacters) {
        Assert.isTrue(maxChunkCharacters > 0, "maxChunkCharacters must be positive");
        this.maxChunkCharacters = maxChunkCharacters;
    }

    List<Chunk> chunk(String markdown) {
        List<Chunk> chunks = new ArrayList<>();
        String title = "";
        String[] headings = new String[7];
        StringBuilder body = new StringBuilder();
        boolean insideFence = false;

        for (String line : markdown.split("\\n", -1)) {
            if (line.startsWith(FENCE)) {
                insideFence = !insideFence;
            }

            if (!insideFence) {
                if (line.startsWith("# ") && !line.startsWith("## ")) {
                    title = line.substring(2).trim();
                    continue;
                }
                Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    flush(chunks, title, path(headings), body);
                    int level = heading.group(1).length();
                    headings[level] = heading.group(2).trim();
                    for (int deeper = level + 1; deeper < headings.length; deeper++) {
                        headings[deeper] = null;
                    }
                    continue;
                }
            }

            body.append(line).append('\n');
        }

        flush(chunks, title, path(headings), body);
        return chunks;
    }

    /** The heading path of the current section, outermost first. */
    private static String path(String[] headings) {
        StringBuilder path = new StringBuilder();
        for (int level = 2; level < headings.length; level++) {
            if (headings[level] == null) {
                continue;
            }
            if (path.length() > 0) {
                path.append(" > ");
            }
            path.append(headings[level]);
        }
        return path.toString();
    }

    private void flush(List<Chunk> chunks, String title, String heading, StringBuilder body) {
        String text = body.toString().strip();
        body.setLength(0);
        if (text.isEmpty()) {
            return;
        }

        StringBuilder prefix = new StringBuilder();
        if (!title.isBlank()) {
            prefix.append(title);
        }
        if (!heading.isBlank()) {
            if (prefix.length() > 0) {
                prefix.append(" > ");
            }
            prefix.append(heading);
        }

        for (String piece : split(text)) {
            chunks.add(new Chunk(heading, prefix.length() == 0 ? piece : prefix + "\n\n" + piece));
        }
    }

    /** Greedy paragraph packing, with a hard cut for a paragraph that is too big on its own. */
    private List<String> split(String text) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : BLANK_LINE.split(text)) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (int start = 0; start < trimmed.length(); start += this.maxChunkCharacters) {
                String slice = trimmed.substring(start, Math.min(start + this.maxChunkCharacters, trimmed.length()));
                boolean roomLeft = current.length() + (current.length() == 0 ? 0 : 2) + slice.length()
                        <= this.maxChunkCharacters;
                if (current.length() > 0 && !roomLeft) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(slice);
            }
        }

        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    /**
     * A piece of a document, ready to be embedded.
     *
     * @param heading the section it came from, empty for text before the first heading
     * @param text    the title, the heading path and the body
     */
    record Chunk(String heading, String text) {
    }
}