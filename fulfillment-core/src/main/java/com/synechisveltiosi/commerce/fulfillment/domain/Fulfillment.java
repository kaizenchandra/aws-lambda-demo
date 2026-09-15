package com.synechisveltiosi.commerce.fulfillment.domain;

import java.util.Objects;
import java.util.UUID;

public record Fulfillment(
        UUID orderId,
        String customerId,
        UUID sourceEventId,
        String fingerprint,
        Status status,
        long version,
        String receiptKey) {
    public Fulfillment {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(sourceEventId);
        Objects.requireNonNull(status);
        if (customerId == null || customerId.isBlank() || fingerprint == null || fingerprint.isBlank())
            throw new IllegalArgumentException("Missing fulfillment identity");
        if (status == Status.PENDING && (version != 0 || receiptKey != null)
                || status == Status.COMPLETED
                && (version != 1 || receiptKey == null || receiptKey.isBlank()))
            throw new IllegalArgumentException("Invalid fulfillment state");
    }

    public static Fulfillment pending(UUID order, String customer, UUID event, String fingerprint) {
        return new Fulfillment(order, customer, event, fingerprint, Status.PENDING, 0, null);
    }

    public void requireSameSource(UUID event, String hash) {
        if (!sourceEventId.equals(event) || !fingerprint.equals(hash))
            throw new IllegalStateException("Conflicting source event for aggregate");
    }

    public Fulfillment complete(String key) {
        if (status != Status.PENDING) throw new IllegalStateException("Already complete");
        return new Fulfillment(
                orderId, customerId, sourceEventId, fingerprint, Status.COMPLETED, version + 1, key);
    }

    public FulfillmentCompleted completedEvent() {
        if (status != Status.COMPLETED) throw new IllegalStateException("Not completed");
        return new FulfillmentCompleted(orderId, receiptKey);
    }

    public enum Status {
        PENDING,
        COMPLETED
    }
}
