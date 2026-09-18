package com.urlcheck.ai.tool;

/**
 * What a tool hands back to the model.
 *
 * <p>Failures travel as data rather than as exceptions: a rejected URL or an
 * unknown name is something the model should explain to the user, not an error
 * that throws away the whole answer.
 *
 * @param error why the call failed, or {@code null} when it succeeded
 * @param data  the result payload, or {@code null} when it failed
 */
public record ToolResult(String error, Object data) {

    public static ToolResult ok(Object data) {
        return new ToolResult(null, data);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, null);
    }
}
