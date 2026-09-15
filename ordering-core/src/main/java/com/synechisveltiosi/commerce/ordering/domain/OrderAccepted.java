package com.synechisveltiosi.commerce.ordering.domain;

import java.time.Instant;
import java.util.UUID;

public record OrderAccepted(UUID orderId, Instant occurredAt) {}
