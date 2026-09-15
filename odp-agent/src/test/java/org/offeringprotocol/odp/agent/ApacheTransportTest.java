package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.OdpResponseLimitException;

class ApacheTransportTest {
    private HttpServer server;
    private URI origin;
    private final OdpTransport transport = OdpServiceClient.localDevelopmentTransport();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        origin = URI.create("http://localhost:" + server.getAddress().getPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void clientPreservesStreamingLimitError() {
        server.createContext("/.well-known/odp", exchange -> {
            exchange.sendResponseHeaders(200, 100_000);
            exchange.getResponseBody().flush();
            exchange.close();
        });
        var failure = assertThrows(OdpResponseLimitException.class, () -> OdpServiceClient.create(origin, transport));
        assertEquals("RESPONSE_LIMIT_EXCEEDED", failure.code());
        assertFalse(failure.retryable());
    }

    @Test
    void clientRetriesSearchOverHttpWithoutChangingItsBody() {
        AtomicInteger attempts = new AtomicInteger();
        java.util.ArrayList<String> bodies = new java.util.ArrayList<>();
        server.createContext("/.well-known/odp", exchange -> {
            byte[] body = Responses.SERVICE_DOCUMENT.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", Responses.ODP);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/odp/offerings/search", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"odp_version\":\"1.0\",\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            int status = attempts.getAndIncrement() == 0 ? 429 : 200;
            exchange.getResponseHeaders().add("Content-Type", Responses.ODP);
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        OdpServiceClient client = OdpServiceClient.create(origin, transport);
        var page = client.searchOfferings(
                new org.offeringprotocol.odp.core.SearchRequests.Offerings(
                        "1.0", "plants", null, null, null, null, null, null),
                "terse",
                null);
        assertTrue(page.items().isEmpty());
        assertEquals(2, attempts.get());
        assertEquals(bodies.get(0), bodies.get(1));
        assertTrue(bodies.get(0).contains("plants"));
    }

    @Test
    void sendsPostAndPreservesResponse() throws Exception {
        server.createContext("/echo", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/odp+json");
            exchange.getResponseHeaders().add("X-Method", exchange.getRequestMethod());
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var request = HttpRequest.newBuilder(origin.resolve("/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"plants\"}"))
                .header("Content-Type", "application/odp+json")
                .build();
        var response = transport.send(request, 100);
        assertEquals(201, response.statusCode());
        assertEquals("POST", response.headers().firstValue("X-Method").orElseThrow());
        assertEquals("{\"query\":\"plants\"}", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals(request, response.request());
        assertEquals(request.uri(), response.uri());
        byte[] copy = response.body();
        copy[0] = 0;
        assertEquals('{', response.body()[0]);
    }

    @Test
    void rejectsDeclaredLengthBeforeWaitingForBody() {
        server.createContext("/large", exchange -> {
            exchange.sendResponseHeaders(200, 100_000);
            exchange.getResponseBody().flush();
            exchange.close();
        });
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            OdpResponseLimitException error =
                    assertThrows(OdpResponseLimitException.class, () -> transport.send(request("/large"), 64));
            assertTrue(error.getMessage().contains("byte limit"));
            assertEquals("RESPONSE_LIMIT_EXCEEDED", error.code());
            assertFalse(error.retryable());
        });
    }

    @Test
    void boundsChunkedAndCompressedBodiesAndErrorResponses() throws Exception {
        byte[] oversized = new byte[20_000];
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write(oversized);
        }
        for (String path : new String[] {"/chunked", "/compressed", "/error", "/redirect"}) {
            server.createContext(path, exchange -> {
                boolean gzip = path.equals("/compressed");
                if (gzip) {
                    exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                }
                int status = path.equals("/error") ? 400 : path.equals("/redirect") ? 302 : 200;
                exchange.sendResponseHeaders(status, 0);
                try {
                    exchange.getResponseBody().write(gzip ? compressed.toByteArray() : oversized);
                } finally {
                    exchange.close();
                }
            });
            int limit = path.equals("/error") || path.equals("/redirect") ? 30_000 : 64;
            OdpResponseLimitException error =
                    assertThrows(OdpResponseLimitException.class, () -> transport.send(request(path), limit));
            assertTrue(error.getMessage().contains("byte limit"));
            assertEquals("RESPONSE_LIMIT_EXCEEDED", error.code());
            assertFalse(error.retryable());
        }
    }

    @Test
    void doesNotFollowRedirectOrRetainCookies() throws Exception {
        var hits = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders()
                    .add("Location", origin.resolve("/target").toString());
            exchange.getResponseHeaders().add("Set-Cookie", "secret=private");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders()
                    .add(
                            "X-Content-Type",
                            String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            exchange.getResponseHeaders()
                    .add("X-Cookie", String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        assertEquals(302, transport.send(request("/redirect")).statusCode());
        assertEquals(0, hits.get());
        assertEquals(
                "null",
                transport
                        .send(request("/target"))
                        .headers()
                        .firstValue("X-Cookie")
                        .orElseThrow());
        assertEquals(
                "null",
                transport
                        .send(HttpRequest.newBuilder(origin.resolve("/target"))
                                .method("GET", HttpRequest.BodyPublishers.noBody())
                                .build())
                        .headers()
                        .firstValue("X-Content-Type")
                        .orElseThrow());
    }

    @Test
    void cancelsStalledResponse() {
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 0);
        });
        assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> assertThrows(
                        IOException.class,
                        () -> transport.send(
                                HttpRequest.newBuilder(origin.resolve("/stall"))
                                        .timeout(Duration.ofMillis(100))
                                        .build(),
                                64)));
    }

    @Test
    void rejectsProductionLoopbackAndInvalidUrls() {
        assertThrows(
                IllegalStateException.class,
                () -> OdpServiceClient.defaultTransport().send(request("/")));
        assertThrows(
                IllegalArgumentException.class,
                () -> transport.send(HttpRequest.newBuilder(URI.create("http://user@localhost/"))
                        .build()));
        assertThrows(IllegalArgumentException.class, () -> transport.send(request("/"), -1));
    }

    @Test
    void rejectsMixedDnsAnswersAndLocalAlias() throws Exception {
        InetAddress[] mixed = {InetAddress.getByAddress(new byte[] {8, 8, 8, 8}), InetAddress.getLoopbackAddress()};
        assertThrows(IllegalStateException.class, () -> SecureDestinations.validate("example.com", mixed, false));
        assertThrows(IllegalStateException.class, () -> SecureDestinations.validate("example.com", mixed, true));
        assertThrows(IllegalStateException.class, () -> SecureDestinations.validate("localhost", mixed, true));
    }

    @Test
    void interruptionCancelsBlockedRead() throws Exception {
        var received = new CountDownLatch(1);
        server.createContext("/interrupt", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            received.countDown();
        });
        var failure = new AtomicReference<Throwable>();
        Thread caller = new Thread(() -> {
            try {
                transport.send(request("/interrupt"));
            } catch (Exception exception) {
                failure.set(exception);
            }
        });
        caller.start();
        try {
            assertTrue(received.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2_000);
            assertFalse(caller.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
        } finally {
            caller.interrupt();
            caller.join(2_000);
        }
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(origin.resolve(path)).build();
    }

    @Test
    void ignoresSystemProxyAndDoesNotRetryErrors() throws Exception {
        var requests = new AtomicInteger();
        var proxyLookups = new AtomicInteger();
        server.createContext("/failure", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                proxyLookups.incrementAndGet();
                throw new AssertionError("System proxy must not handle ODP requests");
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                throw new AssertionError("System proxy must not handle ODP requests");
            }
        });
        try {
            assertEquals(503, transport.send(request("/failure")).statusCode());
            assertEquals(1, requests.get());
            assertEquals(0, proxyLookups.get());
        } finally {
            ProxySelector.setDefault(previous);
        }
    }

    @Test
    void triesAnotherAddressOnlyWhenConnectionWasNotEstablished() throws Exception {
        InetAddress[] addresses = InetAddress.getAllByName("localhost");
        org.junit.jupiter.api.Assumptions.assumeTrue(addresses.length > 1, "Requires dual-stack localhost");
        var alternate = HttpServer.create(new InetSocketAddress(addresses[addresses.length - 1], 0), 0);
        var requests = new AtomicInteger();
        alternate.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        alternate.start();
        try {
            var request = HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + alternate.getAddress().getPort() + "/"))
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString("search"))
                    .build();
            var response = transport.send(request);
            assertEquals("search", new String(response.body(), StandardCharsets.UTF_8));
            assertEquals(1, requests.get());
        } finally {
            alternate.stop(0);
        }
    }
}
