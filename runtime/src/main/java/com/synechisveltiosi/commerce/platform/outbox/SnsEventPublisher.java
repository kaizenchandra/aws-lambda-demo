package com.synechisveltiosi.commerce.platform.outbox;

import software.amazon.awssdk.services.sns.SnsClient;

public final class SnsEventPublisher implements EventPublisher {
  private final SnsClient sns;
  private final String topic;

  public SnsEventPublisher(SnsClient sns, String topic) {
    this.sns = sns;
    this.topic = topic;
  }

  public void publish(String body) {
    sns.publish(r -> r.topicArn(topic).message(body));
  }
}
