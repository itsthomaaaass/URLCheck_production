package com.urlcheck.ai.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.urlcheck.ai.config.AiProperties;

/**
 * The two rules the conversation module owns: a conversation resolves only for
 * the user who owns it, and a user never keeps more than
 * {@code app.ai.max-conversations} of them.
 */
class ConversationServiceTest {

    private static final long USER_ID = 7L;
    private static final long CONVERSATION_ID = 101L;

    private final ConversationMapper mapper = mock(ConversationMapper.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ConversationService service =
            new ConversationService(mapper, events, properties(5));

    @Test
    void namesANewConversationAfterTheMessageThatStartedIt() {
        when(mapper.findByIdAndUserId(any(), any())).thenReturn(new Conversation());

        service.create(USER_ID, "Which of my URLs are down?");

        assertThat(inserted().getTitle()).isEqualTo("Which of my URLs are down?");
        assertThat(inserted().getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void collapsesAMultilineFirstMessageIntoAOneLineTitle() {
        when(mapper.findByIdAndUserId(any(), any())).thenReturn(new Conversation());

        service.create(USER_ID, "  line one\n\n  line\ttwo  ");

        assertThat(inserted().getTitle()).isEqualTo("line one line two");
    }

    @Test
    void cutsALongTitleWithoutSplittingASurrogatePair() {
        when(mapper.findByIdAndUserId(any(), any())).thenReturn(new Conversation());
        // Each emoji is one code point but two chars, so a naive cut at 60 chars
        // would split the 30th one in half.
        String message = "👍".repeat(80);

        service.create(USER_ID, message);

        String title = inserted().getTitle();
        assertThat(title).endsWith("…");
        assertThat(title.codePointCount(0, title.length())).isEqualTo(61);
        assertThat(Character.isHighSurrogate(title.charAt(title.length() - 2))).isFalse();
    }

    @Test
    void fallsBackToANeutralTitleForAnEmptyConversation() {
        when(mapper.findByIdAndUserId(any(), any())).thenReturn(new Conversation());

        service.create(USER_ID, null);

        assertThat(inserted().getTitle()).isEqualTo(ConversationService.UNTITLED);
    }

    @Test
    void resolvesAConversationItsOwnerAskedFor() {
        Conversation conversation = new Conversation();
        when(mapper.findByIdAndUserId(CONVERSATION_ID, USER_ID)).thenReturn(conversation);

        assertThat(service.requireOwned(USER_ID, CONVERSATION_ID)).isSameAs(conversation);
    }

    /**
     * Somebody else's conversation and one that does not exist are answered
     * identically, so the endpoint never confirms that a stranger's id is real.
     */
    @Test
    void refusesAConversationThatIsMissingOrSomebodyElses() {
        when(mapper.findByIdAndUserId(anyLong(), anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service.requireOwned(USER_ID, CONVERSATION_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("101");
    }

    @Test
    void deletesTheLeastRecentlyUsedConversationsDownToTheCap() {
        when(mapper.findIdsBeyondRetention(USER_ID, 5)).thenReturn(List.of(11L, 12L));

        service.enforceRetention(USER_ID);

        verify(mapper).deleteByIdAndUserId(11L, USER_ID);
        verify(mapper).deleteByIdAndUserId(12L, USER_ID);
    }

    @Test
    void touchesThenHoldsTheCap() {
        when(mapper.findIdsBeyondRetention(USER_ID, 5)).thenReturn(List.of());

        service.touch(USER_ID, CONVERSATION_ID);

        verify(mapper).touch(CONVERSATION_ID, USER_ID);
        verify(mapper).findIdsBeyondRetention(USER_ID, 5);
    }

    @Test
    void announcesWhatItDroppedSoTheMemoryCanGoToo() {
        when(mapper.findIdsBeyondRetention(USER_ID, 5)).thenReturn(List.of(11L));

        service.enforceRetention(USER_ID);

        ArgumentCaptor<ConversationsEvictedEvent> event =
                ArgumentCaptor.forClass(ConversationsEvictedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().conversationIds()).containsExactly(11L);
    }

    @Test
    void announcesNothingWhenTheCapAlreadyHolds() {
        when(mapper.findIdsBeyondRetention(USER_ID, 5)).thenReturn(List.of());

        service.enforceRetention(USER_ID);

        verify(events, never()).publishEvent(any(ConversationsEvictedEvent.class));
    }

    @Test
    void deletesAConversationItsOwnerAskedForAndAnnouncesIt() {
        when(mapper.findByIdAndUserId(CONVERSATION_ID, USER_ID)).thenReturn(new Conversation());

        service.delete(USER_ID, CONVERSATION_ID);

        verify(mapper).deleteByIdAndUserId(CONVERSATION_ID, USER_ID);
        ArgumentCaptor<ConversationsEvictedEvent> event =
                ArgumentCaptor.forClass(ConversationsEvictedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().conversationIds()).containsExactly(CONVERSATION_ID);
    }

    @Test
    void refusesToDeleteAConversationThatIsNotTheUsers() {
        when(mapper.findByIdAndUserId(anyLong(), anyLong())).thenReturn(null);

        assertThatThrownBy(() -> service.delete(USER_ID, CONVERSATION_ID))
                .isInstanceOf(NoSuchElementException.class);

        verify(mapper, never()).deleteByIdAndUserId(anyLong(), anyLong());
    }

    /**
     * A cap of zero would delete the conversation being opened, so the last one
     * always survives whatever the environment says.
     */
    @Test
    void neverCapsBelowOneConversation() {
        ConversationMapper strictMapper = mock(ConversationMapper.class);
        ConversationService strict = new ConversationService(strictMapper, events, properties(0));
        when(strictMapper.findIdsBeyondRetention(eq(USER_ID), anyInt())).thenReturn(List.of());
        when(strictMapper.findByIdAndUserId(any(), any())).thenReturn(new Conversation());

        strict.create(USER_ID, "hello");
        strict.enforceRetention(USER_ID);

        verify(strictMapper, times(1)).findIdsBeyondRetention(USER_ID, 1);
    }

    private Conversation inserted() {
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private static AiProperties properties(int maxConversations) {
        return new AiProperties(
                true, "https://api.deepseek.com", "sk-test", "deepseek-flash",
                0.2, 1024, 2, 60, 10, 20, maxConversations);
    }
}
