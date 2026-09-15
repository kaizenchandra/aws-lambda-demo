package com.synechisveltiosi.commerce.platform;

import com.synechisveltiosi.commerce.platform.outbox.EventPublisher;
import com.synechisveltiosi.commerce.platform.outbox.OutboxRelay;
import com.synechisveltiosi.commerce.platform.outbox.OutboxStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class RelayTest {
    @Test
    void relay_publishFailure_leavesOutboxPending() {
        var store = mock(OutboxStore.class);
        var publisher = mock(EventPublisher.class);
        when(store.pending(0)).thenReturn(List.of(new OutboxStore.Pending("key", "event", 0)));
        doThrow(new IllegalStateException("SNS down")).when(publisher).publish("event");
        assertEquals(1, new OutboxRelay(store, publisher).run((i, e) -> {
        }, () -> true));
        verify(store, never()).delivered(any());
    }

    @Test
    void relay_ackFailure_republishesSafely() {
        var store = mock(OutboxStore.class);
        var publisher = mock(EventPublisher.class);
        when(store.pending(0)).thenReturn(List.of(new OutboxStore.Pending("key", "event", 0)));
        doThrow(new IllegalStateException("timeout")).doNothing().when(store).delivered("key");
        var relay = new OutboxRelay(store, publisher);
        assertEquals(1, relay.run((i, e) -> {
        }, () -> true));
        assertEquals(0, relay.run((i, e) -> {
        }, () -> true));
        verify(publisher, times(2)).publish("event");
    }

    @Test
    void relay_poisonEntry_continuesOtherEntries() {
        var store = mock(OutboxStore.class);
        var publisher = mock(EventPublisher.class);
        when(store.pending(0))
                .thenReturn(
                        List.of(
                                new OutboxStore.Pending("bad", "bad", 1),
                                new OutboxStore.Pending("good", "good", 1)));
        doThrow(new IllegalStateException()).when(publisher).publish("bad");
        assertEquals(1, new OutboxRelay(store, publisher).run((i, e) -> {
        }, () -> true));
        verify(store).delivered("good");
        verify(store, never()).delivered("bad");
    }
}
