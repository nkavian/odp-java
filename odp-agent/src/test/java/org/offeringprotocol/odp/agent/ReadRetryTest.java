package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReadRetryTest {
    private static final HttpRequest REQUEST = HttpRequest.newBuilder(URI.create("https://example.com/odp/offerings"))
            .GET()
            .build();
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    @Test
    void honorsDelayAndStopsAfterThreeRetries() throws Exception {
        AtomicLong time = new AtomicLong();
        List<Long> waits = new ArrayList<>();
        ReadRetry retry = retry(time, waits);
        AtomicInteger calls = new AtomicInteger();
        HttpResponse<byte[]> result = retry.send(
                request -> {
                    calls.incrementAndGet();
                    return response(429, "2");
                },
                REQUEST,
                100);
        assertEquals(429, result.statusCode());
        assertEquals(4, calls.get());
        assertEquals(List.of(2000L, 2000L, 2000L), waits);
    }

    @Test
    void respectsOperationBudgetAndDoesNotRetryOtherStatuses() throws Exception {
        for (int status : List.of(200, 304, 400, 401, 402, 403, 404, 410, 422, 500)) {
            AtomicInteger calls = new AtomicInteger();
            retry(new AtomicLong(), new ArrayList<>())
                    .send(
                            request -> {
                                calls.incrementAndGet();
                                return response(status, "0");
                            },
                            REQUEST,
                            100);
            assertEquals(1, calls.get());
        }
        AtomicLong time = new AtomicLong();
        List<Long> waits = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        retry(time, waits)
                .send(
                        request -> {
                            calls.incrementAndGet();
                            time.addAndGet(5_000_000_000L);
                            return response(503, "20");
                        },
                        REQUEST,
                        100);
        assertEquals(2, calls.get());
        assertEquals(List.of(20_000L), waits);
    }

    @Test
    void parsesHttpDateAndRejectsInvalidOrExcessiveDelays() throws Exception {
        for (String value : List.of("31", "999999999999999999999999", "9223372036854775807", "-1", "1.5", "bad")) {
            List<Long> waits = new ArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            retry(new AtomicLong(), waits)
                    .send(
                            request -> {
                                calls.incrementAndGet();
                                return response(429, value);
                            },
                            REQUEST,
                            100);
            assertEquals(1, calls.get(), value);
            assertTrue(waits.isEmpty());
        }
        List<Long> waits = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        retry(new AtomicLong(), waits)
                .send(
                        request -> calls.getAndIncrement() == 0
                                ? response(503, "Mon, 21 Sep 2026 12:00:02 GMT")
                                : response(200, null),
                        REQUEST,
                        100);
        assertEquals(List.of(2000L), waits);
    }

    @Test
    void acceptsAllHttpDateFormsAndTreatsPastDatesAsImmediate() throws Exception {
        for (String value : List.of("Monday, 21-Sep-26 12:00:02 GMT", "Mon Sep 21 12:00:02 2026")) {
            List<Long> waits = new ArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            retry(new AtomicLong(), waits)
                    .send(
                            request -> calls.getAndIncrement() == 0 ? response(503, value) : response(200, null),
                            REQUEST,
                            100);
            assertEquals(List.of(2000L), waits, value);
        }
        for (String value : List.of("Sunday, 06-Nov-94 08:49:37 GMT", "Sun Nov  6 08:49:37 1994")) {
            List<Long> waits = new ArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            retry(new AtomicLong(), waits)
                    .send(
                            request -> calls.getAndIncrement() == 0 ? response(503, value) : response(200, null),
                            REQUEST,
                            100);
            assertEquals(List.of(0L), waits, value);
        }
    }

    @Test
    void usesJitterOnlyForMissing503Delay() throws Exception {
        List<Long> waits = new ArrayList<>();
        retry(new AtomicLong(), waits).send(request -> response(503, null), REQUEST, 100);
        assertEquals(List.of(500L, 1000L, 2000L), waits);
        waits.clear();
        retry(new AtomicLong(), waits).send(request -> response(429, null), REQUEST, 100);
        assertTrue(waits.isEmpty());
        retry(new AtomicLong(), waits)
                .send(
                        request -> Responses.of(request, 503, "", Map.of("Retry-After", List.of("0", "1"))),
                        REQUEST,
                        100);
        assertTrue(waits.isEmpty());
    }

    @Test
    void sharesBudgetAcrossRedirectRequests() throws Exception {
        ReadRetry retry = retry(new AtomicLong(), new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        retry.send(request -> calls.getAndIncrement() < 2 ? response(503, "0") : response(302, null), REQUEST, 100);
        AtomicInteger redirectedCalls = new AtomicInteger();
        retry.send(
                request -> {
                    redirectedCalls.incrementAndGet();
                    return response(503, "0");
                },
                REQUEST,
                100);
        assertEquals(2, redirectedCalls.get());
    }

    @Test
    void doesNotRetryTransportFailureOrInterruptions() {
        ReadRetry retry = retry(new AtomicLong(), new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                IOException.class,
                () -> retry.send(
                        request -> {
                            calls.incrementAndGet();
                            throw new IOException("connection closed");
                        },
                        REQUEST,
                        100));
        assertEquals(1, calls.get());
        ReadRetry interrupted = new ReadRetry(
                System::nanoTime,
                () -> NOW,
                milliseconds -> {
                    throw new InterruptedException();
                },
                () -> 0.5);
        assertThrows(InterruptedException.class, () -> interrupted.send(request -> response(503, "0"), REQUEST, 100));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> retry.send(request -> fail("must not send"), REQUEST, 100));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void doesNotSendAfterSleepOvershootsDeadline() throws Exception {
        AtomicLong time = new AtomicLong();
        ReadRetry retry = new ReadRetry(time::get, () -> NOW, milliseconds -> time.set(31_000_000_000L), () -> 0.5);
        AtomicInteger calls = new AtomicInteger();
        retry.send(
                request -> {
                    calls.incrementAndGet();
                    return response(503, "1");
                },
                REQUEST,
                100);
        assertEquals(1, calls.get());
    }

    @Test
    void actualClientRetriesReadOnlyPostAndPreservesRequest() {
        AtomicInteger calls = new AtomicInteger();
        List<HttpRequest> attempts = new ArrayList<>();
        OdpServiceClient client = OdpServiceClient.create(URI.create("https://example.com"), request -> {
            if (request.uri().getPath().equals("/.well-known/odp")) {
                return Responses.ok(request, Responses.SERVICE_DOCUMENT);
            }
            attempts.add(request);
            return calls.getAndIncrement() == 0
                    ? response(429, "0")
                    : Responses.ok(request, "{\"odp_version\":\"1.0\",\"items\":[]}");
        });
        client.searchOfferings(
                new org.offeringprotocol.odp.core.SearchRequests.Offerings(
                        "1.0", "plants", null, null, null, null, null, null),
                null,
                null);
        assertEquals(2, attempts.size());
        assertEquals("POST", attempts.get(0).method());
        assertSame(attempts.get(0), attempts.get(1));
    }

    private static ReadRetry retry(AtomicLong time, List<Long> waits) {
        return new ReadRetry(
                time::get,
                () -> NOW.plusNanos(time.get()),
                milliseconds -> {
                    waits.add(milliseconds);
                    time.addAndGet(milliseconds * 1_000_000);
                },
                () -> 0.5);
    }

    private static HttpResponse<byte[]> response(int status, String delay) {
        return Responses.of(REQUEST, status, "", delay == null ? Map.of() : Map.of("Retry-After", List.of(delay)));
    }
}
