package com.synechisveltiosi.commerce.platform.outbox;

import java.util.List;

public interface OutboxStore {
    List<Pending> pending(int shard);

    void delivered(String key);

    record Pending(String key, String body, long ageSeconds) {
    }
}
