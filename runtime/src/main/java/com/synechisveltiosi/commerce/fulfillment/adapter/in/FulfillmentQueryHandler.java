package com.synechisveltiosi.commerce.fulfillment.adapter.in;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.synechisveltiosi.commerce.fulfillment.application.FulfillmentService;
import com.synechisveltiosi.commerce.fulfillment.application.port.in.FulfillmentUseCases;
import com.synechisveltiosi.commerce.platform.Api;
import com.synechisveltiosi.commerce.platform.Bootstrap;
import com.synechisveltiosi.commerce.platform.Telemetry;

import java.util.Map;

public final class FulfillmentQueryHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final FulfillmentUseCases service;
    private final boolean local;

    public FulfillmentQueryHandler() {
        this(Bootstrap.bean(FulfillmentUseCases.class), Bootstrap.local());
    }

    public FulfillmentQueryHandler(FulfillmentUseCases service, boolean local) {
        this.service = service;
        this.local = local;
    }

    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent request, Context context) {
        long start = System.nanoTime();
        try {
            var f = service.get(Api.customer(request, local), Api.id(request));
            return Api.response(
                    200,
                    Map.of(
                            "orderId",
                            f.orderId(),
                            "status",
                            f.status(),
                            "receiptKey",
                            f.receiptKey() == null ? "" : f.receiptKey()));
        } catch (Api.Unauthorized e) {
            return Api.response(401, Map.of("error", "Authentication required"));
        } catch (FulfillmentService.NotFound e) {
            return Api.response(404, Map.of("error", "Fulfillment not found"));
        } catch (IllegalArgumentException e) {
            return Api.response(400, Map.of("error", "Invalid id"));
        } catch (RuntimeException e) {
            Telemetry.record(context, "ApiFailures", "", "", "", start, e);
            return Api.response(503, Map.of("error", "Temporarily unavailable"));
        }
    }
}
