package com.synechisveltiosi.commerce.platform.outbox;

import java.util.function.BiConsumer;

public final class OutboxRelay {
  private final OutboxStore store;
  private final EventPublisher publisher;

  public OutboxRelay(OutboxStore store, EventPublisher publisher) {
    this.store = store;
    this.publisher = publisher;
  }

  public int run(
      BiConsumer<OutboxStore.Pending, RuntimeException> observer,
      java.util.function.BooleanSupplier hasTime) {
    int failures = 0;
    for (int shard = 0; shard < 4; shard++)
      for (var item : store.pending(shard)) {
        if (!hasTime.getAsBoolean()) return failures;
        try {
          publisher.publish(item.body());
          store.delivered(item.key());
          observer.accept(item, null);
        } catch (RuntimeException e) {
          failures++;
          observer.accept(item, e);
        }
      }
    return failures;
  }
}
