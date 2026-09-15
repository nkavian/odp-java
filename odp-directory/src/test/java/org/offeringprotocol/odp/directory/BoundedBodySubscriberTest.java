package org.offeringprotocol.odp.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BoundedBodySubscriberTest {
    @Test
    void boundsRealFixedLengthAndChunkedSuccessErrorAndRedirectBodies() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String[] values = exchange.getRequestURI().getPath().substring(1).split("/");
            int status = Integer.parseInt(values[0]);
            int length = Integer.parseInt(values[1]);
            exchange.sendResponseHeaders(status, "chunked".equals(values[2]) ? 0 : length);
            try (var output = exchange.getResponseBody()) {
                output.write(new byte[length]);
            } catch (IOException ignored) {
                // The client cancels oversized responses.
            }
        });
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            for (int status : new int[] {200, 400, 302}) {
                int limit = status == 200 ? 524_288 : 16_384;
                for (String mode : new String[] {"fixed", "chunked"}) {
                    String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + status + "/";
                    HttpRequest accepted = HttpRequest.newBuilder(URI.create(base + limit + "/" + mode))
                            .timeout(Duration.ofSeconds(5))
                            .build();
                    assertEquals(
                            limit,
                            client.send(accepted, DirectoryClient.boundedBodyHandler())
                                    .body()
                                    .length);
                    HttpRequest rejected = HttpRequest.newBuilder(URI.create(base + (limit + 1) + "/" + mode))
                            .timeout(Duration.ofSeconds(5))
                            .build();
                    assertThrows(IOException.class, () -> client.send(rejected, DirectoryClient.boundedBodyHandler()));
                }
            }
        } finally {
            server.stop(0);
        }
    }
}
