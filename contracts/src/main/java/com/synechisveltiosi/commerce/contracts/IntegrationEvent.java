package com.synechisveltiosi.commerce.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IntegrationEvent<T>(
        UUID eventId,
        String eventType,
        String eventVersion,
        UUID aggregateId,
        String correlationId,
        String causationId,
        Instant occurredAt,
        T payload) {
    public IntegrationEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(aggregateId);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(payload);
        if (!"1.0".equals(eventVersion))
            throw new IllegalArgumentException("Unsupported event version");
        if (eventType == null
                || eventType.isBlank()
                || correlationId == null
                || correlationId.isBlank()
                || causationId == null
                || causationId.isBlank()) throw new IllegalArgumentException("Missing event metadata");
    }
}
