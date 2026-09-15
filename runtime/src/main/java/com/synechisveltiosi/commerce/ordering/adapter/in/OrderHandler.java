package com.synechisveltiosi.commerce.ordering.adapter.in;

import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.*;
import com.synechisveltiosi.commerce.ordering.application.OrderService;
import com.synechisveltiosi.commerce.ordering.application.port.in.OrderUseCases;
import com.synechisveltiosi.commerce.platform.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class OrderHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
  private final OrderUseCases orders;
  private final boolean local;

  public OrderHandler() {
    this(Bootstrap.bean(OrderUseCases.class), Bootstrap.local());
  }

  public OrderHandler(OrderUseCases orders, boolean local) {
    this.orders = orders;
    this.local = local;
  }

  public record CreateRequest(List<OrderUseCases.Item> items) {}

  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent request, Context context) {
    long start = System.nanoTime();
    try {
      var customer = Api.customer(request, local);
      if ("GET".equals(request.getHttpMethod())) {
        var o = orders.get(customer, Api.id(request));
        return Api.response(
            200,
            Map.of(
                "orderId",
                o.id(),
                "status",
                o.status(),
                "total",
                o.total(),
                "items",
                o.lines(),
                "acceptedAt",
                o.acceptedAt()));
      }
      if (!"POST".equals(request.getHttpMethod()))
        return Api.response(405, Map.of("error", "Method not allowed"));
      if (Boolean.TRUE.equals(request.getIsBase64Encoded())
          || request.getBody() == null
          || request.getBody().getBytes(StandardCharsets.UTF_8).length > 32768)
        throw new IllegalArgumentException("Invalid body");
      var body = Json.read(request.getBody(), CreateRequest.class);
      if (body == null) throw new IllegalArgumentException("Missing body");
      var o = orders.create(customer, Api.header(request, "Idempotency-Key"), body.items());
      Telemetry.record(
          context, "OrdersAcceptedRequests", o.id().toString(), o.id().toString(), "", start, null);
      return Api.response(
          202,
          Map.of(
              "orderId",
              o.id(),
              "status",
              o.status(),
              "total",
              o.total(),
              "fulfillmentUrl",
              "/fulfillments/" + o.id()));
    } catch (Api.Unauthorized e) {
      return Api.response(401, Map.of("error", "Authentication required"));
    } catch (OrderService.NotFound e) {
      return Api.response(404, Map.of("error", "Order not found"));
    } catch (OrderService.Conflict e) {
      return Api.response(409, Map.of("error", e.getMessage()));
    } catch (IllegalArgumentException e) {
      return Api.response(400, Map.of("error", "Invalid order request"));
    } catch (RuntimeException e) {
      Telemetry.record(context, "ApiFailures", "", "", "", start, e);
      return Api.response(
          503, Map.of("error", "Temporarily unavailable; retry with the same idempotency key"));
    }
  }
}
