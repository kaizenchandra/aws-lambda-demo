package com.synechisveltiosi.commerce.platform;

import com.amazonaws.services.lambda.runtime.Context;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CloudWatch Embedded Metric Format. No payloads, principal IDs or exception messages.
 */
public final class Telemetry {
    private Telemetry() {
    }

    public static void record(
            Context context,
            String metric,
            String correlation,
            String aggregate,
            String event,
            long started,
            Throwable error) {
        var log = new LinkedHashMap<String, Object>();
        var service = System.getenv().getOrDefault("SERVICE_MODE", "test");
        log.put("timestamp", Instant.now().toString());
        log.put("level", error == null ? "INFO" : "ERROR");
        log.put("service", service);
        log.put("function", context == null ? "test" : context.getFunctionName());
        log.put("awsRequestId", context == null ? "test" : context.getAwsRequestId());
        log.put(
                "traceId",
                System.getProperty(
                        "com.amazonaws.xray.traceHeader",
                        System.getenv().getOrDefault("_X_AMZN_TRACE_ID", "")));
        log.put("correlationId", correlation);
        log.put("aggregateId", aggregate);
        log.put("eventId", event);
        log.put("durationMs", (System.nanoTime() - started) / 1_000_000);
        if (error != null) log.put("errorType", error.getClass().getSimpleName());
        log.put(metric, 1);
        log.put(
                "_aws",
                Map.of(
                        "Timestamp",
                        System.currentTimeMillis(),
                        "CloudWatchMetrics",
                        List.of(
                                Map.of(
                                        "Namespace",
                                        "Commerce",
                                        "Dimensions",
                                        List.of(List.of("service")),
                                        "Metrics",
                                        List.of(
                                                Map.of("Name", metric, "Unit", "Count"),
                                                Map.of("Name", "durationMs", "Unit", "Milliseconds"))))));
        System.out.println(Json.write(log));
    }

    public static void outboxAge(long age) {
        System.out.println(
                Json.write(
                        Map.of(
                                "service",
                                System.getenv().getOrDefault("SERVICE_MODE", "test"),
                                "OutboxAge",
                                age,
                                "_aws",
                                Map.of(
                                        "Timestamp",
                                        System.currentTimeMillis(),
                                        "CloudWatchMetrics",
                                        List.of(
                                                Map.of(
                                                        "Namespace",
                                                        "Commerce",
                                                        "Dimensions",
                                                        List.of(List.of("service")),
                                                        "Metrics",
                                                        List.of(Map.of("Name", "OutboxAge", "Unit", "Seconds"))))))));
    }
}
