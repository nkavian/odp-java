package org.offeringprotocol.odp.agent;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.io.HttpResponseInformationCallback;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.offeringprotocol.odp.core.OdpResponseLimitException;

final class ApacheTransport implements OdpTransport {
    private static final int MAXIMUM_BYTES = 1_048_576;
    private static final int MAXIMUM_PROBLEM_BYTES = 16_384;
    private static final CloseableHttpClient CLIENT = createClient(null);
    private static final ScheduledThreadPoolExecutor DEADLINES = createDeadlines();
    private final boolean allowLocalNetwork;
    private final CloseableHttpClient client;

    static CloseableHttpClient createClient(SSLContext sslContext) {
        var tls = ClientTlsStrategyBuilder.create().setSslContext(sslContext).buildClassic();
        var operator = new DefaultHttpClientConnectionOperator(
                ignored -> new Socket(Proxy.NO_PROXY),
                null,
                null,
                RegistryBuilder.<TlsSocketStrategy>create()
                        .register("https", tls)
                        .build());
        var manager = new PoolingHttpClientConnectionManager(
                operator, PoolConcurrencyPolicy.STRICT, PoolReusePolicy.LIFO, TimeValue.ofMinutes(1), null);
        manager.setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(10))
                .setSocketTimeout(Timeout.ofSeconds(30))
                .setTimeToLive(TimeValue.ofMinutes(1))
                .build());
        return HttpClients.custom()
                .setConnectionManager(manager)
                .setRoutePlanner(new DefaultRoutePlanner(null))
                .setRequestExecutor(new PeerCheckingExecutor())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .build();
    }

    ApacheTransport(boolean allowLocalNetwork) {
        this(allowLocalNetwork, CLIENT);
    }

    ApacheTransport(boolean allowLocalNetwork, CloseableHttpClient client) {
        this.allowLocalNetwork = allowLocalNetwork;
        this.client = client;
    }

    private static ScheduledThreadPoolExecutor createDeadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "odp-http-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Override
    public HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        return send(request, MAXIMUM_BYTES);
    }

    @Override
    public HttpResponse<byte[]> send(HttpRequest request, int maximumBytes) throws IOException, InterruptedException {
        if (maximumBytes < 0 || maximumBytes > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("ODP response byte limit must be between 0 and " + MAXIMUM_BYTES);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("ODP request was interrupted");
        }
        URI uri = request.uri();
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("ODP request URL must not contain credentials or a fragment");
        }
        var addresses = SecureDestinations.resolve(uri, allowLocalNetwork);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                && !(allowLocalNetwork
                        && "http".equalsIgnoreCase(uri.getScheme())
                        && addresses[0].isLoopbackAddress())) {
            throw new IllegalArgumentException("ODP request URL must use HTTPS outside local development");
        }
        Duration timeout = request.timeout().orElse(Duration.ofSeconds(30));
        byte[] payload = request.bodyPublisher().isPresent() ? body(request, timeout) : null;
        long started = System.nanoTime();
        IOException failure = null;
        for (int index = 0; index < addresses.length; index++) {
            InetAddress address = addresses[index];
            if (index > 0
                    && !Arrays.asList(SecureDestinations.resolve(uri, allowLocalNetwork))
                            .contains(address)) {
                continue;
            }
            long remaining = timeout.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new java.net.http.HttpTimeoutException("ODP request timed out");
            }
            try {
                return sendTo(request, maximumBytes, address, payload, Duration.ofNanos(remaining));
            } catch (HttpHostConnectException | ConnectTimeoutException exception) {
                // Only connection establishment failures qualify; an HTTP request is never replayed.
                failure = exception;
            }
        }
        throw new IOException("ODP connection could not be established", failure);
    }

    private HttpResponse<byte[]> sendTo(
            HttpRequest request, int maximumBytes, InetAddress address, byte[] payload, Duration timeout)
            throws IOException, InterruptedException {
        URI uri = request.uri();
        // Supplying the address prevents the connector from resolving the hostname a second time.
        HttpHost target = new HttpHost(uri.getScheme(), address, uri.getHost(), uri.getPort());
        HttpUriRequestBase outgoing = new HttpUriRequestBase(request.method(), uri);
        outgoing.setConfig(RequestConfig.custom()
                .setAuthenticationEnabled(false)
                .setConnectionRequestTimeout(Timeout.of(timeout))
                .setResponseTimeout(Timeout.of(timeout))
                .build());
        request.headers().map().forEach((name, values) -> {
            if (!"Content-Length".equalsIgnoreCase(name)
                    && !"Transfer-Encoding".equalsIgnoreCase(name)
                    && !"Host".equalsIgnoreCase(name)) {
                values.forEach(value -> outgoing.addHeader(name, value));
            }
        });
        if (payload != null && payload.length > 0) {
            outgoing.setEntity(new ByteArrayEntity(payload, null));
        }
        Thread caller = Thread.currentThread();
        long started = System.nanoTime();
        long budget = timeout.toNanos();
        var deadline = DEADLINES.scheduleAtFixedRate(
                () -> {
                    if (caller.isInterrupted() || System.nanoTime() - started >= budget) {
                        outgoing.cancel();
                    }
                },
                Math.min(budget, TimeUnit.MILLISECONDS.toNanos(25)),
                TimeUnit.MILLISECONDS.toNanos(25),
                TimeUnit.NANOSECONDS);
        try {
            HttpResponse<byte[]> result = client.execute(target, outgoing, response -> {
                try {
                    return read(request, response, maximumBytes);
                } catch (IOException | RuntimeException exception) {
                    // Abort before response closure: closing a live entity may otherwise drain it.
                    outgoing.cancel();
                    throw exception;
                }
            });
            if (caller.isInterrupted()) {
                throw new InterruptedException("ODP request was interrupted");
            }
            return result;
        } catch (IOException exception) {
            if (caller.isInterrupted()) {
                InterruptedException interrupted = new InterruptedException("ODP request was interrupted");
                interrupted.initCause(exception);
                throw interrupted;
            }
            throw exception;
        } finally {
            deadline.cancel(false);
        }
    }

    private static byte[] body(HttpRequest request, Duration timeout) throws IOException, InterruptedException {
        var subscriber = HttpResponse.BodySubscribers.ofByteArray();
        var subscription = new AtomicReference<Flow.Subscription>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                subscriber.onSubscribe(value);
            }

            @Override
            public void onNext(ByteBuffer value) {
                subscriber.onNext(List.of(value));
            }

            @Override
            public void onError(Throwable failure) {
                subscriber.onError(failure);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
        try {
            return subscriber.getBody().toCompletableFuture().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IOException("ODP request body could not be read", exception);
        } finally {
            if (subscription.get() != null) {
                subscription.get().cancel();
            }
        }
    }

    private static HttpResponse<byte[]> read(HttpRequest request, ClassicHttpResponse response, int maximumBytes)
            throws IOException {
        int status = response.getCode();
        int limit = status >= 200 && status < 300 ? maximumBytes : Math.min(maximumBytes, MAXIMUM_PROBLEM_BYTES);
        var entity = response.getEntity(); // NOPMD CloseResource - response handler owns closure after cancellation on
        // failure.
        byte[] bytes = new byte[0];
        if (entity != null) {
            if (entity.getContentLength() > limit) {
                throw new OdpResponseLimitException("ODP response exceeds its byte limit");
            }
            // The entity stream is decoded by HttpClient; compressed bodies have the same budget.
            bytes = entity.getContent().readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new OdpResponseLimitException("ODP response exceeds its byte limit");
            }
        }
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var header : response.getHeaders()) {
            headers.computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
                    .add(header.getValue());
        }
        return new Response(request, status, HttpHeaders.of(headers, (name, value) -> true), bytes);
    }

    private static final class PeerCheckingExecutor extends HttpRequestExecutor {
        @Override
        public ClassicHttpResponse execute(
                ClassicHttpRequest request,
                HttpClientConnection connection,
                HttpResponseInformationCallback callback,
                HttpContext context)
                throws IOException, HttpException {
            var expected = HttpClientContext.cast(context)
                    .getHttpRoute()
                    .getTargetHost()
                    .getAddress();
            if (!(connection.getRemoteAddress() instanceof InetSocketAddress peer)
                    || expected == null
                    || !expected.equals(peer.getAddress())) {
                throw new IOException("ODP connected peer does not match its validated destination");
            }
            return super.execute(request, connection, callback, context);
        }
    }

    private record Response(HttpRequest request, int statusCode, HttpHeaders headers, byte[] content)
            implements HttpResponse<byte[]> {
        private Response {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        @Override
        public byte[] body() {
            return content.clone();
        }

        @Override
        public Optional<HttpResponse<byte[]>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
