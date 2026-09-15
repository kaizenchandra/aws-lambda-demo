package com.synechisveltiosi.commerce.platform;

import com.synechisveltiosi.commerce.fulfillment.adapter.out.DynamoFulfillmentRepository;
import com.synechisveltiosi.commerce.fulfillment.adapter.out.S3ReceiptStorage;
import com.synechisveltiosi.commerce.fulfillment.application.FulfillmentService;
import com.synechisveltiosi.commerce.fulfillment.application.port.in.FulfillmentUseCases;
import com.synechisveltiosi.commerce.ordering.adapter.out.DynamoOrderRepository;
import com.synechisveltiosi.commerce.ordering.application.OrderService;
import com.synechisveltiosi.commerce.ordering.application.port.in.OrderUseCases;
import com.synechisveltiosi.commerce.ordering.domain.Catalog;
import com.synechisveltiosi.commerce.platform.outbox.DynamoOutboxStore;
import com.synechisveltiosi.commerce.platform.outbox.OutboxRelay;
import com.synechisveltiosi.commerce.platform.outbox.SnsEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
    static String env(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Missing environment variable " + name);
        return value;
    }

    private <B extends AwsClientBuilder<B, C>, C> B configure(B builder) {
        builder
                .region(Region.of(env("AWS_REGION")))
                .credentialsProvider(DefaultCredentialsProvider.builder().build());
        var endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint != null && !endpoint.isBlank()) {
            if (!Bootstrap.local())
                throw new IllegalStateException("Endpoint override requires APP_ENV=local");
            builder.endpointOverride(URI.create(endpoint));
        }
        builder.overrideConfiguration(
                ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(4))
                        .apiCallAttemptTimeout(Duration.ofSeconds(2))
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(2).build())
                        .build());
        return builder;
    }

    @Bean
    DynamoDbClient dynamo() {
        return configure(
                DynamoDbClient.builder()
                        .httpClientBuilder(
                                UrlConnectionHttpClient.builder()
                                        .connectionTimeout(Duration.ofSeconds(1))
                                        .socketTimeout(Duration.ofSeconds(2))))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "service.mode", havingValue = "order-api")
    OrderUseCases orders(DynamoDbClient db) {
        return new OrderService(
                new DynamoOrderRepository(db, env("TABLE_NAME")), new Catalog(), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "service.mode", havingValue = "fulfillment-worker")
    FulfillmentUseCases worker(DynamoDbClient db) {
        return new FulfillmentService(
                new DynamoFulfillmentRepository(db, env("TABLE_NAME")),
                new S3ReceiptStorage(s3(), env("RECEIPT_BUCKET")));
    }

    @Bean
    @ConditionalOnProperty(name = "service.mode", havingValue = "fulfillment-api")
    FulfillmentUseCases query(DynamoDbClient db) {
        return new FulfillmentService(
                new DynamoFulfillmentRepository(db, env("TABLE_NAME")),
                (id, body) -> {
                    throw new IllegalStateException("Read-only function");
                });
    }

    private S3Client s3() {
        return configure(
                S3Client.builder()
                        .httpClientBuilder(
                                UrlConnectionHttpClient.builder()
                                        .connectionTimeout(Duration.ofSeconds(1))
                                        .socketTimeout(Duration.ofSeconds(2))))
                .forcePathStyle(Bootstrap.local())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "service.mode", havingValue = "relay")
    OutboxRelay relay(DynamoDbClient db) {
        var sns =
                configure(
                        SnsClient.builder()
                                .httpClientBuilder(
                                        UrlConnectionHttpClient.builder()
                                                .connectionTimeout(Duration.ofSeconds(1))
                                                .socketTimeout(Duration.ofSeconds(2))))
                        .build();
        return new OutboxRelay(
                new DynamoOutboxStore(db, env("TABLE_NAME"), Clock.systemUTC()),
                new SnsEventPublisher(sns, env("TOPIC_ARN")));
    }
}
