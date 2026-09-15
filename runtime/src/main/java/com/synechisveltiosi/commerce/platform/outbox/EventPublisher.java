package com.synechisveltiosi.commerce.platform.outbox;

public interface EventPublisher {
  void publish(String body);
}
