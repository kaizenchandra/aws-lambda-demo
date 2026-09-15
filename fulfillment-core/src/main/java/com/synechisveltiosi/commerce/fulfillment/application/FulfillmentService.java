package com.synechisveltiosi.commerce.fulfillment.application;

import com.synechisveltiosi.commerce.contracts.*;
import com.synechisveltiosi.commerce.fulfillment.application.port.in.FulfillmentUseCases;
import com.synechisveltiosi.commerce.fulfillment.application.port.out.*;
import com.synechisveltiosi.commerce.fulfillment.domain.Fulfillment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.*;

public final class FulfillmentService implements FulfillmentUseCases {
  private final FulfillmentRepository repository;
  private final ReceiptStorage receipts;
  private final Clock clock;

  public FulfillmentService(FulfillmentRepository repository, ReceiptStorage receipts) {
    this(repository, receipts, Clock.systemUTC());
  }

  public FulfillmentService(
      FulfillmentRepository repository, ReceiptStorage receipts, Clock clock) {
    this.repository = repository;
    this.receipts = receipts;
    this.clock = clock;
  }

  public boolean process(IntegrationEvent<AcceptedOrder> event) {
    if (!"OrderAccepted".equals(event.eventType()))
      throw new IllegalArgumentException("Unsupported event type");
    var content = receipt(event);
    var hash = hash(content);
    var aggregate =
        repository.begin(
            Fulfillment.pending(
                event.aggregateId(), event.payload().customerId(), event.eventId(), hash));
    aggregate.requireSameSource(event.eventId(), hash);
    if (aggregate.status() == Fulfillment.Status.COMPLETED) return false;
    var completed = aggregate.complete(receipts.putImmutable(event.aggregateId(), content));
    var domainEvent = completed.completedEvent();
    // Only the winning completion transaction publishes its timestamp and event.
    var outgoing =
        new IntegrationEvent<>(
            UUID.nameUUIDFromBytes(
                (event.aggregateId() + ":fulfilled:1").getBytes(StandardCharsets.UTF_8)),
            "FulfillmentCompleted",
            "1.0",
            event.aggregateId(),
            event.correlationId(),
            event.eventId().toString(),
            clock.instant(),
            new CompletedFulfillment(completed.customerId(), domainEvent.receiptKey()));
    if (repository.complete(completed, outgoing)) return true;
    var winner =
        repository
            .find(event.aggregateId())
            .orElseThrow(() -> new IllegalStateException("Missing concurrent fulfillment"));
    winner.requireSameSource(event.eventId(), hash);
    if (winner.status() != Fulfillment.Status.COMPLETED)
      throw new IllegalStateException("Concurrent completion requires retry");
    return false;
  }

  public Fulfillment get(String customer, UUID id) {
    return repository
        .find(id)
        .filter(f -> f.customerId().equals(customer))
        .orElseThrow(NotFound::new);
  }

  private String receipt(IntegrationEvent<AcceptedOrder> e) {
    var body =
        new StringBuilder("Fulfillment registration v1\nOrder: ")
            .append(e.aggregateId())
            .append("\nSource event: ")
            .append(e.eventId())
            .append("\nAccepted at: ")
            .append(e.occurredAt())
            .append("\nCurrency: USD\nTotal: ")
            .append(e.payload().total().toPlainString())
            .append('\n');
    e.payload().items().stream()
        .sorted(Comparator.comparing(AcceptedOrder.Item::sku))
        .forEach(
            i ->
                body.append(i.sku())
                    .append(' ')
                    .append(i.quantity())
                    .append(' ')
                    .append(i.unitPrice().toPlainString())
                    .append('\n'));
    // Include principal in the fingerprint without placing it in the receipt.
    return body.append("Owner digest: ")
        .append(hash(e.payload().customerId()))
        .append('\n')
        .toString();
  }

  private static String hash(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static final class NotFound extends RuntimeException {}
}
