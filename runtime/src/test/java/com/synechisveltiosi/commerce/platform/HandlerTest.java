package com.synechisveltiosi.commerce.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.events.*;
import com.synechisveltiosi.commerce.contracts.*;
import com.synechisveltiosi.commerce.fulfillment.adapter.in.FulfillmentHandler;
import com.synechisveltiosi.commerce.fulfillment.application.port.in.FulfillmentUseCases;
import com.synechisveltiosi.commerce.ordering.adapter.in.OrderHandler;
import com.synechisveltiosi.commerce.ordering.application.port.in.OrderUseCases;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class HandlerTest {
  static IntegrationEvent<AcceptedOrder> event() {
    return new IntegrationEvent<>(
        UUID.randomUUID(),
        "OrderAccepted",
        "1.0",
        UUID.randomUUID(),
        "correlation",
        "cause",
        Instant.now(),
        new AcceptedOrder(
            "customer",
            "USD",
            new BigDecimal("29.90"),
            List.of(new AcceptedOrder.Item("JAVA-GUIDE", 1, new BigDecimal("29.90")))));
  }

  static SQSEvent.SQSMessage message(String id, String body) {
    var m = new SQSEvent.SQSMessage();
    m.setMessageId(id);
    m.setBody(body);
    return m;
  }

  @Test
  void handleBatch_partialFailure_returnsOnlyFailedIds() {
    var useCase = mock(FulfillmentUseCases.class);
    when(useCase.process(any())).thenReturn(true);
    var batch = new SQSEvent();
    batch.setRecords(List.of(message("good", Json.write(event())), message("bad", "invalid")));
    var result = new FulfillmentHandler(useCase).handleRequest(batch, null);
    assertEquals(
        List.of("bad"),
        result.getBatchItemFailures().stream()
            .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
            .toList());
    verify(useCase, times(1)).process(any());
  }

  @Test
  void handleBatch_downstreamFailure_retriesRecord() {
    var useCase = mock(FulfillmentUseCases.class);
    when(useCase.process(any())).thenThrow(new IllegalStateException("Unavailable"));
    var batch = new SQSEvent();
    batch.setRecords(List.of(message("retry", Json.write(event()))));
    assertEquals(
        "retry",
        new FulfillmentHandler(useCase)
            .handleRequest(batch, null)
            .getBatchItemFailures()
            .getFirst()
            .getItemIdentifier());
  }

  @Test
  void createOrder_invalidJson_returns400() {
    var useCase = mock(OrderUseCases.class);
    var request = new APIGatewayProxyRequestEvent().withHttpMethod("POST").withBody("{");
    assertEquals(400, new OrderHandler(useCase, true).handleRequest(request, null).getStatusCode());
    verifyNoInteractions(useCase);
  }

  @Test
  void createOrder_unauthenticated_returns401() {
    var useCase = mock(OrderUseCases.class);
    assertEquals(
        401,
        new OrderHandler(useCase, false)
            .handleRequest(new APIGatewayProxyRequestEvent().withHttpMethod("POST"), null)
            .getStatusCode());
    verifyNoInteractions(useCase);
  }

  @Test
  void createOrder_nullJson_returns400() {
    assertEquals(
        400,
        new OrderHandler(mock(OrderUseCases.class), true)
            .handleRequest(
                new APIGatewayProxyRequestEvent().withHttpMethod("POST").withBody("null"), null)
            .getStatusCode());
  }

  @Test
  void deserialize_unknownOptionalField_acceptsCompatibleEvent() {
    var event = event();
    var json =
        Json.write(event).replace("\"eventType\"", "\"optionalField\":\"new\",\"eventType\"");
    assertNotNull(
        Json.read(
            json,
            new com.fasterxml.jackson.core.type.TypeReference<
                IntegrationEvent<AcceptedOrder>>() {}));
  }

  @Test
  void deserialize_unknownVersion_rejectsEvent() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Json.read(
                Json.write(event()).replace("\"1.0\"", "\"2.0\""),
                new com.fasterxml.jackson.core.type.TypeReference<
                    IntegrationEvent<AcceptedOrder>>() {}));
  }

  @Test
  void createOrder_multibyteBodyOverLimit_returns400BeforeUseCase() {
    var useCase = mock(OrderUseCases.class);
    var body = "{\"items\":[],\"padding\":\"" + "界".repeat(12000) + "\"}";
    var request = new APIGatewayProxyRequestEvent().withHttpMethod("POST").withBody(body);
    assertEquals(400, new OrderHandler(useCase, true).handleRequest(request, null).getStatusCode());
    verifyNoInteractions(useCase);
  }
}
