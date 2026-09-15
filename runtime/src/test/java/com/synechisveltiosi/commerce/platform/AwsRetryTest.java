package com.synechisveltiosi.commerce.platform;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AwsRetryTest {
    @Test
    void getItem_transientServerFailure_retriesAndSucceeds() throws Exception {
        exercise(false);
    }

    @Test
    void getItem_persistentServerFailure_stopsAtTwoAttempts() throws Exception {
        exercise(true);
    }

    void exercise(boolean persistent) throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    boolean fail = calls.incrementAndGet() == 1 || persistent;
                    var body =
                            (fail ? "{\"__type\":\"InternalServerError\",\"message\":\"transient\"}" : "{}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.0");
                    exchange.sendResponseHeaders(fail ? 500 : 200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try (var db =
                     DynamoDbClient.builder()
                             .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                             .region(Region.US_EAST_1)
                             .credentialsProvider(
                                     StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                             .overrideConfiguration(
                                     ClientOverrideConfiguration.builder()
                                             .apiCallTimeout(Duration.ofSeconds(4))
                                             .apiCallAttemptTimeout(Duration.ofSeconds(2))
                                             .retryStrategy(StandardRetryStrategy.builder().maxAttempts(2).build())
                                             .build())
                             .build()) {
            if (persistent)
                assertThrows(
                        DynamoDbException.class,
                        () -> db.getItem(r -> r.tableName("retry-test").key(Dynamo.key("one"))));
            else assertFalse(db.getItem(r -> r.tableName("retry-test").key(Dynamo.key("one"))).hasItem());
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
