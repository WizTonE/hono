/*******************************************************************************
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.service.deviceconnection;

import java.net.HttpURLConnection;
import java.util.Objects;

import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.service.EventBusService;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.DeviceConnectionConstants;
import org.eclipse.hono.util.DeviceConnectionResult;
import org.eclipse.hono.util.EventBusMessage;
import org.eclipse.hono.util.MessageHelper;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.tag.Tags;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Verticle;

/**
 * Adapter to bind {@link DeviceConnectionService} to the vertx event bus.
 * <p>
 * This base class provides support for receiving service invocation request messages
 * via vert.x' event bus and routing them to specific methods accepting the
 * query parameters contained in the request message.
 */
public abstract class EventBusDeviceConnectionAdapter extends EventBusService implements Verticle {

    private static final String SPAN_NAME_GET_LAST_GATEWAY = "get last known gateway";
    private static final String SPAN_NAME_SET_LAST_GATEWAY = "set last known gateway";

    /**
     * The service to forward requests to.
     *
     * @return The service to bind to, must never return {@code null}.
     */
    protected abstract DeviceConnectionService getService();

    @Override
    protected final String getEventBusAddress() {
        return DeviceConnectionConstants.EVENT_BUS_ADDRESS_DEVICE_CONNECTION_IN;
    }

    /**
     * Processes a Device Connection API request message received via the vert.x event bus.
     * <p>
     * This method validates the request payload against the Device Connection API specification
     * before invoking the corresponding {@code DeviceConnectionService} methods.
     * 
     * @param request The request message.
     * @return A future indicating the outcome of the service invocation.
     * @throws NullPointerException If the request message is {@code null}.
     */
    @Override
    public Future<EventBusMessage> processRequest(final EventBusMessage request) {

        Objects.requireNonNull(request);

        switch (DeviceConnectionConstants.DeviceConnectionAction.from(request.getOperation())) {
        case GET_LAST_GATEWAY:
            return processGetLastGatewayRequest(request);
        case SET_LAST_GATEWAY:
            return processSetLastGatewayRequest(request);
        default:
            return processCustomOperationMessage(request);
        }
    }

    /**
     * Processes a <em>get last known gateway</em> request message.
     *
     * @param request The request message.
     * @return The response to send to the client via the event bus.
     */
    protected Future<EventBusMessage> processGetLastGatewayRequest(final EventBusMessage request) {
        final String tenantId = request.getTenant();
        final String deviceId = request.getDeviceId();
        final SpanContext spanContext = request.getSpanContext();

        final Span span = newChildSpan(SPAN_NAME_GET_LAST_GATEWAY, spanContext, tenantId, deviceId, null);
        final Future<EventBusMessage> resultFuture;
        if (tenantId == null || deviceId == null) {
            TracingHelper.logError(span, "missing tenant and/or device");
            resultFuture = Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST));
        } else {
            final Promise<DeviceConnectionResult> result = Promise.promise();
            log.debug("getting last known gateway for tenant [{}], device [{}]", tenantId, deviceId);
            getService().getLastKnownGatewayForDevice(tenantId, deviceId, span, result);

            resultFuture = result.future()
                    .map(res -> request.getResponse(res.getStatus())
                                .setJsonPayload(res.getPayload())
                                .setCacheDirective(res.getCacheDirective()));
        }
        return finishSpanOnFutureCompletion(span, resultFuture);
    }

    /**
     * Processes a <em>set last known gateway</em> request message.
     *
     * @param request The request message.
     * @return The response to send to the client via the event bus.
     */
    protected Future<EventBusMessage> processSetLastGatewayRequest(final EventBusMessage request) {
        final String tenantId = request.getTenant();
        final String deviceId = request.getDeviceId();
        final String gatewayId = request.getGatewayId();
        final SpanContext spanContext = request.getSpanContext();

        final Span span = newChildSpan(SPAN_NAME_SET_LAST_GATEWAY, spanContext, tenantId, deviceId, gatewayId);
        final Future<EventBusMessage> resultFuture;
        if (tenantId == null || deviceId == null || gatewayId == null) {
            TracingHelper.logError(span, "missing tenant, device and/or gateway");
            resultFuture = Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST));
        } else {
            final Promise<DeviceConnectionResult> result = Promise.promise();
            log.debug("setting last known gateway for tenant [{}], device [{}] to {}", tenantId, deviceId, gatewayId);
            getService().setLastKnownGatewayForDevice(tenantId, deviceId, gatewayId, span, result);

            resultFuture = result.future().map(res -> {
                return request.getResponse(res.getStatus())
                        .setJsonPayload(res.getPayload())
                        .setCacheDirective(res.getCacheDirective());
            });
        }
        return finishSpanOnFutureCompletion(span, resultFuture);
    }

    /**
     * Creates a new <em>OpenTracing</em> span for tracing the execution of a device connection service operation.
     * <p>
     * The returned span will already contain tags for the given tenant, device and gateway ids (if either is not {@code null}).
     *
     * @param operationName The operation name that the span should be created for.
     * @param spanContext Existing span context.
     * @param tenantId The tenant id.
     * @param deviceId The device id.
     * @param gatewayId The gateway id.
     * @return The new {@code Span}.
     * @throws NullPointerException if operationName is {@code null}.
     */
    protected final Span newChildSpan(final String operationName, final SpanContext spanContext, final String tenantId,
            final String deviceId, final String gatewayId) {
        Objects.requireNonNull(operationName);
        // we set the component tag to the class name because we have no access to
        // the name of the enclosing component we are running in
        final Tracer.SpanBuilder spanBuilder = TracingHelper.buildChildSpan(tracer, spanContext, operationName)
                .ignoreActiveSpan()
                .withTag(Tags.COMPONENT.getKey(), getClass().getSimpleName())
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER);
        if (tenantId != null) {
            spanBuilder.withTag(MessageHelper.APP_PROPERTY_TENANT_ID, tenantId);
        }
        if (deviceId != null) {
            spanBuilder.withTag(MessageHelper.APP_PROPERTY_DEVICE_ID, deviceId);
        }
        if (gatewayId != null) {
            spanBuilder.withTag(MessageHelper.APP_PROPERTY_GATEWAY_ID, gatewayId);
        }
        return spanBuilder.start();
    }

    /**
     * Processes a request for a non-standard operation.
     * <p>
     * Subclasses should override this method in order to support additional, custom
     * operations that are not defined by Hono's Device Connection API.
     * <p>
     * This default implementation simply returns a future that is failed with a
     * {@link ClientErrorException} with an error code <em>400 Bad Request</em>.
     *
     * @param request The request to process.
     * @return A future indicating the outcome of the service invocation.
     */
    protected Future<EventBusMessage> processCustomOperationMessage(final EventBusMessage request) {
        log.debug("invalid operation in request message [{}]", request.getOperation());
        return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST));
    }

}
