package com.urlcheck.ai.message;

/**
 * Who wrote a message.
 *
 * <p>These names are what {@code chat_message.role} stores, and they are also
 * the roles Spring AI replays to the model, so no translation sits between the
 * two.
 */
public enum ChatRole {
    USER,
    ASSISTANT
}
