package com.synechisveltiosi.commerce.fulfillment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.synechisveltiosi.commerce.contracts.*;
import com.synechisveltiosi.commerce.fulfillment.application.FulfillmentService;
import com.synechisveltiosi.commerce.fulfillment.application.port.out.*;
import com.synechisveltiosi.commerce.fulfillment.domain.Fulfillment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class FulfillmentTest {
  final FulfillmentRepository repository = mock(FulfillmentRepository.class);
  final ReceiptStorage receipts = mock(ReceiptStorage.class);
  final FulfillmentService service = new FulfillmentService(repository, receipts);
  final IntegrationEvent<AcceptedOrder> event =
      new IntegrationEvent<>(
          UUID.randomUUID(),
          "OrderAccepted",
          "1.0",
          UUID.randomUUID(),
          "correlation",
          "cause",
          Instant.parse("2026-01-01T00:00:00Z"),
          new AcceptedOrder(
              "customer",
              "USD",
              new BigDecimal("29.90"),
              List.of(new AcceptedOrder.Item("JAVA-GUIDE", 1, new BigDecimal("29.90")))));

  void pending() {
    when(repository.begin(any())).thenAnswer(c -> c.getArgument(0));
    when(receipts.putImmutable(any(), any())).thenReturn("receipts/result.txt");
  }

  @Test
  void processOrder_validEvent_completesWithOutbox() {
    pending();
    when(repository.complete(any(), any())).thenReturn(true);
    assertTrue(service.process(event));
    var ordered = inOrder(repository, receipts);
    ordered.verify(repository).begin(any());
    ordered
        .verify(receipts)
        .putImmutable(eq(event.aggregateId()), contains(event.aggregateId().toString()));
    ordered
        .verify(repository)
        .complete(
            argThat(f -> f.status() == Fulfillment.Status.COMPLETED),
            argThat(e -> e.causationId().equals(event.eventId().toString())));
  }

  @Test
  void processOrder_duplicateEvent_skipsProcessing() {
    when(repository.begin(any()))
        .thenAnswer(c -> c.getArgument(0, Fulfillment.class).complete("receipts/result.txt"));
    assertFalse(service.process(event));
    verifyNoInteractions(receipts);
    verify(repository, never()).complete(any(), any());
  }

  @Test
  void processOrder_downstreamFailure_keepsPending() {
    pending();
    when(receipts.putImmutable(any(), any()))
        .thenThrow(new IllegalStateException("S3 unavailable"));
    assertThrows(IllegalStateException.class, () -> service.process(event));
    verify(repository, never()).complete(any(), any());
  }

  @Test
  void processOrder_databaseFailure_retriesSameReceipt() {
    pending();
    when(repository.complete(any(), any()))
        .thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(true);
    assertThrows(IllegalStateException.class, () -> service.process(event));
    assertTrue(service.process(event));
    verify(receipts, times(2)).putImmutable(eq(event.aggregateId()), anyString());
  }

  @Test
  void processOrder_concurrentCompletion_acceptsWinner() {
    pending();
    when(repository.complete(any(), any()))
        .thenAnswer(
            c -> {
              when(repository.find(event.aggregateId())).thenReturn(Optional.of(c.getArgument(0)));
              return false;
            });
    assertFalse(service.process(event));
  }

  @Test
  void processOrder_conflictingSource_rejectsEvent() {
    when(repository.begin(any()))
        .thenAnswer(
            c ->
                Fulfillment.pending(
                    event.aggregateId(), "customer", UUID.randomUUID(), "different"));
    assertThrows(IllegalStateException.class, () -> service.process(event));
    verifyNoInteractions(receipts);
  }

  @Test
  void processOrder_unknownType_rejectsEvent() {
    var wrong =
        new IntegrationEvent<>(
            event.eventId(),
            "Unknown",
            "1.0",
            event.aggregateId(),
            "c",
            "c",
            event.occurredAt(),
            event.payload());
    assertThrows(IllegalArgumentException.class, () -> service.process(wrong));
    verifyNoInteractions(repository, receipts);
  }

  @Test
  void complete_missingReceipt_rejectsTransition() {
    var f = Fulfillment.pending(UUID.randomUUID(), "customer", UUID.randomUUID(), "hash");
    assertThrows(IllegalArgumentException.class, () -> f.complete(""));
  }

  @Test
  void payload_inconsistentTotal_rejectsEvent() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AcceptedOrder("customer", "USD", new BigDecimal("1.00"), event.payload().items()));
  }

  @Test
  void payload_equivalentDecimalScale_normalizesReceiptInputs() {
    var canonical =
        new AcceptedOrder(
            "customer",
            "USD",
            new BigDecimal("29.90"),
            List.of(new AcceptedOrder.Item("JAVA-GUIDE", 1, new BigDecimal("29.90"))));
    var transported =
        new AcceptedOrder(
            "customer",
            "USD",
            new BigDecimal("29.9"),
            List.of(new AcceptedOrder.Item("JAVA-GUIDE", 1, new BigDecimal("29.9"))));
    assertEquals(canonical, transported);
    assertEquals("29.90", transported.total().toPlainString());
  }
}
