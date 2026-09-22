package org.offeringprotocol.odp.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.agent.OdpRequestException;
import org.offeringprotocol.odp.agent.OdpServiceClient;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.SearchCatalog;
import org.offeringprotocol.odp.service.OdpHttpRequest;
import org.offeringprotocol.odp.service.OdpService;
import org.offeringprotocol.odp.service.StaticCatalog;

class SearchIntegrationTest {
    @Test
    void agentAndServiceAgreeOnStructuredSearchOverHttp() throws Exception {
        var capabilities = OdpJson.parseSearchCapabilities("""
                {"filters":{"inline":[{"id":"size","title":"Size","description":"Size",
                "type":"integer","operators":["eq"],"refinable":true}]}}
                """);
        var catalog = new SearchCatalog(capabilities.filters().inline(), List.of());
        AtomicInteger searches = new AtomicInteger();
        var endpoints = new EnumMap<OdpOperation, OdpService.Endpoint>(StaticCatalog.create(List.of(), List.of()));
        endpoints.put(
                OdpOperation.SEARCH_OFFERINGS,
                new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, request -> {
                    searches.incrementAndGet();
                    return OdpJson.parseOfferingSearchResponse("""
                    {"odp_version":"1.0","items":[{"id":"item","name":"Item"}],
                    "refinements":[{"filter_id":"size","values":[{"value":1,"count":1}]}]}
                    """);
                }));
        var service = OdpService.builder("Catalog", "Searchable catalog", "en", "/odp")
                .endpoints(endpoints)
                .searchCapabilities(capabilities)
                .searchCatalog(request -> catalog)
                .build();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Map<String, List<String>> query = new LinkedHashMap<>();
                String raw = exchange.getRequestURI().getRawQuery();
                if (raw != null)
                    for (String pair : raw.split("&")) {
                        String[] parts = pair.split("=", 2);
                        query.computeIfAbsent(
                                        URLDecoder.decode(parts[0], StandardCharsets.UTF_8), key -> new ArrayList<>())
                                .add(parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
                    }
                var response = service.handle(new OdpHttpRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        query,
                        exchange.getRequestHeaders(),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                response.headers()
                        .forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(response.status(), body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var client = OdpServiceClient.create(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    OdpServiceClient.localDevelopmentTransport());
            var request = OdpJson.parseOfferingSearchRequest(
                    "{\"odp_version\":\"1.0\",\"query\":\"item\",\"refinements\":[\"size\"]}");
            var result = client.searchOfferingsDetails(request, "terse", "en");
            assertTrue(result.issues().isEmpty());
            assertEquals("item", result.page().items().get(0).id());
            assertEquals("integer", result.refinements().get(0).filter().type());
            var invalid = OdpJson.parseOfferingSearchRequest(
                    "{\"odp_version\":\"1.0\",\"filters\":[{\"id\":\"size\",\"operator\":\"eq\",\"value\":1.5}]}");
            assertEquals(
                    400,
                    assertThrows(OdpRequestException.class, () -> client.searchOfferings(invalid, "terse", "en"))
                            .status());
            assertEquals(1, searches.get());
        } finally {
            server.stop(0);
        }
    }
}
