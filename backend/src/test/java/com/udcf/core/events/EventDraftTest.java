package com.udcf.core.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the factory defaults and that each wither leaves the original draft untouched. */
class EventDraftTest {

    @Test
    @DisplayName("of sets the core fields with no peer, no message and empty data")
    void factorySetsDefaults() {
        EventDraft draft = EventDraft.of("election", 2, "ELECTION_START", 9);

        assertThat(draft.module()).isEqualTo("election");
        assertThat(draft.nodeId()).isEqualTo(2);
        assertThat(draft.type()).isEqualTo("ELECTION_START");
        assertThat(draft.lamportTime()).isEqualTo(9);
        assertThat(draft.peerId()).isNull();
        assertThat(draft.message()).isNull();
        assertThat(draft.data()).isEmpty();
    }

    @Test
    @DisplayName("withPeer returns a new draft and leaves the original unchanged")
    void withPeer() {
        EventDraft original = EventDraft.of("m", 1, "T", 0);

        EventDraft changed = original.withPeer(4);

        assertThat(changed.peerId()).isEqualTo(4);
        assertThat(original.peerId()).isNull();
        assertThat(changed).isEqualTo(new EventDraft("m", 1, "T", 0, 4, null, Map.of()));
    }

    @Test
    @DisplayName("withMessage returns a new draft and leaves the original unchanged")
    void withMessage() {
        EventDraft original = EventDraft.of("m", 1, "T", 0);

        EventDraft changed = original.withMessage("hello");

        assertThat(changed.message()).isEqualTo("hello");
        assertThat(original.message()).isNull();
        assertThat(changed).isEqualTo(new EventDraft("m", 1, "T", 0, null, "hello", Map.of()));
    }

    @Test
    @DisplayName("withData returns a new draft and leaves the original unchanged")
    void withData() {
        EventDraft original = EventDraft.of("m", 1, "T", 0);

        EventDraft changed = original.withData(Map.of("k", "v"));

        assertThat(changed.data()).containsExactlyEntriesOf(Map.of("k", "v"));
        assertThat(original.data()).isEmpty();
        assertThat(changed).isEqualTo(new EventDraft("m", 1, "T", 0, null, null, Map.of("k", "v")));
    }

    @Test
    @DisplayName("withers chain and keep every earlier change")
    void withersChain() {
        EventDraft draft = EventDraft.of("m", 1, "T", 5)
                .withPeer(3)
                .withMessage("msg")
                .withData(Map.of("x", 1));

        assertThat(draft).isEqualTo(new EventDraft("m", 1, "T", 5, 3, "msg", Map.of("x", 1)));
    }
}
