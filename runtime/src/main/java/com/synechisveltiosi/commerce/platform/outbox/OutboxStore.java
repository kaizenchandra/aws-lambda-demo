package com.synechisveltiosi.commerce.platform.outbox;

import java.util.List;

public interface OutboxStore {
  record Pending(String key, String body, long ageSeconds) {}

  List<Pending> pending(int shard);

  void delivered(String key);
}
