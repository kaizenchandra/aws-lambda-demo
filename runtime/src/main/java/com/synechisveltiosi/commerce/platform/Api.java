package com.synechisveltiosi.commerce.platform;

import com.amazonaws.services.lambda.runtime.events.*;
import java.util.*;

public final class Api {
  private Api() {}

  public static String customer(APIGatewayProxyRequestEvent request, boolean local) {
    if (local) return "local-customer";
    var context = request.getRequestContext();
    var identity = context == null ? null : context.getIdentity();
    var principal = identity == null ? null : identity.getUserArn();
    if (principal == null || principal.isBlank()) throw new Unauthorized();
    return principal;
  }

  public static String header(APIGatewayProxyRequestEvent request, String key) {
    return request.getHeaders() == null
        ? null
        : request.getHeaders().entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase(key))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
  }

  public static APIGatewayProxyResponseEvent response(int status, Object body) {
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(status)
        .withHeaders(
            Map.of(
                "Content-Type",
                "application/json",
                "Cache-Control",
                "no-store",
                "X-Content-Type-Options",
                "nosniff"))
        .withBody(Json.write(body));
  }

  public static UUID id(APIGatewayProxyRequestEvent request) {
    if (request.getPathParameters() == null) throw new IllegalArgumentException("Missing id");
    return UUID.fromString(request.getPathParameters().get("id"));
  }

  public static final class Unauthorized extends RuntimeException {}
}
