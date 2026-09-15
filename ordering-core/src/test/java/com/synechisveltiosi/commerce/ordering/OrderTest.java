package com.synechisveltiosi.commerce.ordering;

import com.synechisveltiosi.commerce.ordering.application.OrderService;
import com.synechisveltiosi.commerce.ordering.application.port.in.OrderUseCases.Item;
import com.synechisveltiosi.commerce.ordering.application.port.out.OrderRepository;
import com.synechisveltiosi.commerce.ordering.domain.Catalog;
import com.synechisveltiosi.commerce.ordering.domain.Money;
import com.synechisveltiosi.commerce.ordering.domain.Order;
import com.synechisveltiosi.commerce.ordering.domain.OrderLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderTest {
    final OrderRepository repository = mock(OrderRepository.class);
    final OrderService service =
            new OrderService(
                    repository,
                    new Catalog(),
                    Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    final List<Item> items = List.of(new Item("JAVA-GUIDE", 2));

    @Test
    void createOrder_validRequest_createsOrder() {
        when(repository.create(any(), any())).thenReturn(true);
        var order = service.create("customer", "request-123", items);
        assertEquals(new BigDecimal("59.80"), order.total().amount());
        assertEquals("ACCEPTED", order.status());
        verify(repository)
                .create(
                        eq(order),
                        argThat(
                                e -> e.aggregateId().equals(order.id()) && e.eventType().equals("OrderAccepted")));
    }

    @Test
    void createOrder_emptyItems_rejectsRequest() {
        assertThrows(
                IllegalArgumentException.class, () -> service.create("customer", "request-123", List.of()));
        verifyNoInteractions(repository);
    }

    @Test
    void createOrder_duplicateSku_rejectsRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.create("customer", "request-123", List.of(items.getFirst(), items.getFirst())));
        verifyNoInteractions(repository);
    }

    @Test
    void createOrder_unknownSku_rejectsRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create("customer", "request-123", List.of(new Item("MISSING", 1))));
        verify(repository, never()).create(any(), any());
    }

    @Test
    void createOrder_invalidQuantity_rejectsRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create("customer", "request-123", List.of(new Item("JAVA-GUIDE", 0))));
    }

    @Test
    void createOrder_duplicateRequest_replaysOriginal() {
        when(repository.create(any(), any())).thenReturn(true);
        var first = service.create("customer", "request-123", items);
        when(repository.find(first.id())).thenReturn(Optional.of(first));
        assertEquals(first, service.create("customer", "request-123", items));
        verify(repository, times(1)).create(any(), any());
    }

    @Test
    void createOrder_keyReusedWithDifferentItems_conflicts() {
        when(repository.create(any(), any())).thenReturn(true);
        var first = service.create("customer", "request-123", items);
        when(repository.find(first.id())).thenReturn(Optional.of(first));
        assertThrows(
                OrderService.Conflict.class,
                () -> service.create("customer", "request-123", List.of(new Item("AWS-GUIDE", 1))));
    }

    @Test
    void createOrder_conditionalConflict_returnsWinner() {
        when(repository.create(any(), any()))
                .thenAnswer(
                        call -> {
                            var order = call.getArgument(0, Order.class);
                            when(repository.find(order.id())).thenReturn(Optional.of(order));
                            return false;
                        });
        assertEquals("ACCEPTED", service.create("customer", "request-123", items).status());
    }

    @Test
    void createOrder_differentCustomers_isolatesKeys() {
        when(repository.create(any(), any())).thenReturn(true);
        assertNotEquals(
                service.create("a", "request-123", items).id(),
                service.create("b", "request-123", items).id());
    }

    @Test
    void getOrder_differentCustomer_hidesExistence() {
        when(repository.create(any(), any())).thenReturn(true);
        var order = service.create("customer", "request-123", items);
        when(repository.find(order.id())).thenReturn(Optional.of(order));
        assertThrows(OrderService.NotFound.class, () -> service.get("intruder", order.id()));
    }

    @Test
    void money_fractionalCent_rejectsValue() {
        assertThrows(ArithmeticException.class, () -> new Money(new BigDecimal("1.001"), "USD"));
    }

    @Test
    void order_overLimit_rejectsValue() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Order(
                                UUID.randomUUID(),
                                "customer",
                                "hash",
                                List.of(new OrderLine("BULK", 100, new Money(new BigDecimal("1001"), "USD"))),
                                Instant.now()));
    }
}
