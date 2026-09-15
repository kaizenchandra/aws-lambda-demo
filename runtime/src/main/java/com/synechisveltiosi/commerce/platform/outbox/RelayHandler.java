package com.synechisveltiosi.commerce.platform.outbox;

import com.amazonaws.services.lambda.runtime.*;
import com.synechisveltiosi.commerce.platform.*;
import java.util.Map;

public final class RelayHandler implements RequestHandler<Map<String, Object>, String> {
  private final OutboxRelay relay;

  public RelayHandler() {
    relay = Bootstrap.bean(OutboxRelay.class);
  }

  public String handleRequest(Map<String, Object> event, Context context) {
    long start = System.nanoTime();
    int failures =
        relay.run(
            (item, error) -> {
              Telemetry.outboxAge(item.ageSeconds());
              Telemetry.record(
                  context,
                  error == null ? "EventsPublished" : "PublishFailures",
                  "",
                  "",
                  item.key(),
                  start,
                  error);
            },
            () -> context == null || context.getRemainingTimeInMillis() > 10000);
    if (failures > 0) throw new IllegalStateException("Outbox publish failures: " + failures);
    return "published";
  }
}
