package com.synechisveltiosi.commerce.platform;

import java.util.*;
import software.amazon.awssdk.services.dynamodb.model.*;

public final class Dynamo {
  private Dynamo() {}

  public static AttributeValue s(String value) {
    return AttributeValue.builder().s(value).build();
  }

  public static AttributeValue n(long value) {
    return AttributeValue.builder().n(Long.toString(value)).build();
  }

  public static Map<String, AttributeValue> key(String value) {
    return Map.of("pk", s(value));
  }

  public static boolean conditional(TransactionCanceledException e) {
    return e.cancellationReasons() != null
        && e.cancellationReasons().stream().anyMatch(r -> "ConditionalCheckFailed".equals(r.code()))
        && e.cancellationReasons().stream()
            .allMatch(r -> "None".equals(r.code()) || "ConditionalCheckFailed".equals(r.code()));
  }
}
