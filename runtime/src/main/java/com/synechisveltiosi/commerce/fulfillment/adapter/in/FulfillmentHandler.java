package com.synechisveltiosi.commerce.fulfillment.adapter.in;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.synechisveltiosi.commerce.contracts.AcceptedOrder;
import com.synechisveltiosi.commerce.contracts.IntegrationEvent;
import com.synechisveltiosi.commerce.fulfillment.application.port.in.FulfillmentUseCases;
import com.synechisveltiosi.commerce.platform.Bootstrap;
import com.synechisveltiosi.commerce.platform.Json;
import com.synechisveltiosi.commerce.platform.Telemetry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class FulfillmentHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private final FulfillmentUseCases service;

    public FulfillmentHandler() {
        this(Bootstrap.bean(FulfillmentUseCases.class));
    }

    public FulfillmentHandler(FulfillmentUseCases service) {
        this.service = service;
    }

    public SQSBatchResponse handleRequest(SQSEvent batch, Context context) {
        var failures = new ArrayList<SQSBatchResponse.BatchItemFailure>();
        for (var record : batch.getRecords()) {
            long start = System.nanoTime();
            IntegrationEvent<AcceptedOrder> event = null;
            try {
                if (context != null && context.getRemainingTimeInMillis() < 10000)
                    throw new IllegalStateException("Invocation deadline approaching");
                if (record.getBody() == null
                        || record.getBody().getBytes(StandardCharsets.UTF_8).length > 32768)
                    throw new IllegalArgumentException("Invalid event length");
                event =
                        Json.read(record.getBody(), new TypeReference<IntegrationEvent<AcceptedOrder>>() {
                        });
                if (event == null) throw new IllegalArgumentException("Missing event");
                boolean processed = service.process(event);
                Telemetry.record(
                        context,
                        processed ? "OrdersProcessed" : "DuplicateEvents",
                        event.correlationId(),
                        event.aggregateId().toString(),
                        event.eventId().toString(),
                        start,
                        null);
            } catch (RuntimeException e) {
                // Per-record boundary: preserve successful records; SQS retries only listed IDs, including
                // malformed events.
                failures.add(new SQSBatchResponse.BatchItemFailure(record.getMessageId()));
                Telemetry.record(
                        context,
                        "ProcessingFailures",
                        event == null ? "" : event.correlationId(),
                        event == null ? "" : event.aggregateId().toString(),
                        event == null ? "" : event.eventId().toString(),
                        start,
                        e);
            }
        }
        return new SQSBatchResponse(failures);
    }
}
