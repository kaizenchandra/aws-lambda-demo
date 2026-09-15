package com.synechisveltiosi.commerce.ordering.application;

import com.synechisveltiosi.commerce.contracts.AcceptedOrder;
import com.synechisveltiosi.commerce.contracts.IntegrationEvent;
import com.synechisveltiosi.commerce.ordering.application.port.in.OrderUseCases;
import com.synechisveltiosi.commerce.ordering.application.port.out.OrderRepository;
import com.synechisveltiosi.commerce.ordering.domain.Catalog;
import com.synechisveltiosi.commerce.ordering.domain.Order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class OrderService implements OrderUseCases {
    private final OrderRepository repository;
    private final Catalog catalog;
    private final Clock clock;

    public OrderService(OrderRepository repository, Catalog catalog, Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.clock = clock;
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

    public Order create(String customer, String key, List<Item> items) {
        if (customer == null
                || customer.isBlank()
                || customer.length() > 256
                || key == null
                || !key.matches("[A-Za-z0-9_-]{8,128}")
                || items == null
                || items.isEmpty()
                || items.size() > 20) throw new IllegalArgumentException("Invalid create request");
        if (items.stream()
                .anyMatch(
                        i ->
                                i == null
                                        || i.sku() == null
                                        || !i.sku().matches("[A-Z0-9-]{1,40}")
                                        || i.quantity() < 1
                                        || i.quantity() > 100)) throw new IllegalArgumentException("Invalid item");
        var canonical = items.stream().sorted(Comparator.comparing(Item::sku)).toList();
        if (canonical.stream().map(Item::sku).distinct().count() != canonical.size())
            throw new IllegalArgumentException("Duplicate SKU");
        var fingerprint = hash(canonical.toString());
        var id = UUID.nameUUIDFromBytes((customer + "\n" + key).getBytes(StandardCharsets.UTF_8));
        var existing = repository.find(id);
        if (existing.isPresent()) return replay(existing.get(), fingerprint);
        var lines = canonical.stream().map(i -> catalog.price(i.sku(), i.quantity())).toList();
        var order = new Order(id, customer, fingerprint, lines, clock.instant());
        var domainEvent = order.accepted();
        var payload =
                new AcceptedOrder(
                        customer,
                        "USD",
                        order.total().amount(),
                        lines.stream()
                                .map(l -> new AcceptedOrder.Item(l.sku(), l.quantity(), l.unitPrice().amount()))
                                .toList());
        var event =
                new IntegrationEvent<>(
                        UUID.nameUUIDFromBytes((id + ":accepted:1").getBytes(StandardCharsets.UTF_8)),
                        "OrderAccepted",
                        "1.0",
                        id,
                        id.toString(),
                        id.toString(),
                        domainEvent.occurredAt(),
                        payload);
        if (repository.create(order, event)) return order;
        return replay(
                repository
                        .find(id)
                        .orElseThrow(
                                () -> new IllegalStateException("Conflicting transaction must be retried")),
                fingerprint);
    }

    private Order replay(Order order, String fingerprint) {
        if (!order.requestFingerprint().equals(fingerprint))
            throw new Conflict("Idempotency key reused with different items");
        return order;
    }

    public Order get(String customer, UUID id) {
        return repository
                .find(id)
                .filter(o -> o.customerId().equals(customer))
                .orElseThrow(NotFound::new);
    }

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    public static final class NotFound extends RuntimeException {
    }
}
