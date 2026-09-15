package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.OdpJson;

class SearchCapabilityTest {
    @Test
    void unsupportedDefinitionsCannotBeOverriddenByAnotherSource() {
        var service = client(
                inline("same").replace("\"string\"", "\"future\""),
                request -> Responses.ok(
                        request,
                        "{\"odp_version\":\"1.0\",\"id\":\"selected\",\"name\":\"Selected\",\"search_capabilities\":"
                                + inline("same", "other") + "}"));
        var result = service.resolveSearchCapabilities("selected", null);
        assertEquals(java.util.Set.of("other"), result.catalog().filters().keySet());
        assertEquals(2, result.issues().size());
    }

    @Test
    void unsupportedDefinitionsDoNotConsumeUsableCatalogBudget() {
        AtomicInteger calls = new AtomicInteger();
        var service = client("{\"filters\":{\"linked\":{\"href\":\"/filters\"}}}", request -> {
            int page = calls.incrementAndGet();
            List<String> items = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                String definition = filter("f" + page + "-" + index);
                items.add(page == 11 ? definition : definition.replace("\"string\"", "\"future\""));
            }
            return Responses.ok(
                    request,
                    "{\"odp_version\":\"1.0\",\"items\":[" + String.join(",", items) + "]"
                            + (page < 11 ? ",\"next\":\"/page/" + page + "\"}" : "}"));
        });
        var result = service.resolveSearchCapabilities(null, null);
        assertEquals(100, result.catalog().filters().size());
        assertEquals(11, calls.get());
    }

    @Test
    void sourceFailuresRemainScoped() {
        for (String capabilities : List.of(
                "null",
                "{}",
                "{\"filters\":{}}",
                "{\"filters\":{\"inline\":[]}}",
                "{\"filters\":{\"linked\":{\"href\":1}}}",
                "{\"filters\":{\"inline\":[{\"id\":42}]}}")) {
            var result = client(capabilities, request -> {
                        fail("Unexpected request");
                        return null;
                    })
                    .resolveSearchCapabilities(null, null);
            assertTrue(result.catalog().filters().isEmpty());
            assertEquals(1, result.issues().size(), capabilities);
        }
        var looping = client(
                "{\"filters\":{\"linked\":{\"href\":\"/filters\"}}}",
                request -> Responses.ok(request, "{\"odp_version\":\"1.0\",\"items\":[],\"next\":\"/filters\"}"));
        assertEquals(1, looping.resolveSearchCapabilities(null, null).issues().size());
        var nested = client(
                "{\"filters\":{\"linked\":{\"href\":\"/filters\"}}}",
                request -> Responses.ok(request, "{\"odp_version\":\"1.0\",\"items\":[{\"odp_version\":\"1.0\"}]}"));
        assertEquals(1, nested.resolveSearchCapabilities(null, null).issues().size());
    }

    @Test
    void validSortsResolveTheirFilterReferences() {
        String capabilities = "{\"filters\":{\"inline\":[" + filter("material")
                + "]},\"sorts\":{\"inline\":[{\"id\":\"order\",\"title\":\"Order\",\"description\":\"Order\",\"keys\":[{\"filter_id\":\"material\",\"direction\":\"ascending\",\"missing\":\"last\"}]}]}}";
        var result = client(capabilities, request -> {
                    fail("Unexpected request");
                    return null;
                })
                .resolveSearchCapabilities(null, null);
        assertTrue(result.issues().isEmpty());
        assertEquals(
                "material",
                result.catalog().sorts().get("order").filters().get(0).id());
    }

    @Test
    void acceptsSixteenCompletePagesAndStopsOnBudgetOverflow() {
        AtomicInteger calls = new AtomicInteger();
        var complete = client("{\"filters\":{\"linked\":{\"href\":\"/filters\"}}}", request -> {
            int page = calls.incrementAndGet();
            return Responses.ok(
                    request,
                    "{\"odp_version\":\"1.0\",\"items\":[" + filter("f" + page) + "]"
                            + (page < 16 ? ",\"next\":\"/page/" + page + "\"}" : "}"));
        });
        assertEquals(
                16,
                complete.resolveSearchCapabilities(null, null)
                        .catalog()
                        .filters()
                        .size());
        assertEquals(16, calls.get());
        calls.set(0);
        var oversized = client("{\"filters\":{\"linked\":{\"href\":\"/filters\"}}}", request -> {
            int page = calls.incrementAndGet();
            List<String> items = new ArrayList<>();
            for (int index = 0; index < 100; index++) items.add(filter("f" + page + "-" + index));
            return Responses.ok(
                    request,
                    "{\"odp_version\":\"1.0\",\"items\":[" + String.join(",", items) + "],\"next\":\"/page/" + page
                            + "\"}");
        });
        var result = oversized.resolveSearchCapabilities(null, null);
        assertTrue(result.catalog().filters().isEmpty());
        assertEquals(1, result.issues().size());
        assertEquals(11, calls.get());
    }

    @Test
    void dropsDependentSortButPreservesOtherDefinitions() {
        String sort =
                "\"sorts\":{\"inline\":[{\"id\":\"order\",\"title\":\"Order\",\"description\":\"Order\",\"keys\":[{\"filter_id\":\"price\",\"direction\":\"ascending\",\"missing\":\"last\"}]}]}";
        String capabilities = inline("price", "other");
        capabilities = capabilities.substring(0, capabilities.length() - 1) + "," + sort + "}";
        var service = client(
                capabilities,
                request -> Responses.ok(
                        request,
                        "{\"odp_version\":\"1.0\",\"id\":\"selected\",\"name\":\"Selected\",\"search_capabilities\":"
                                + inline("price") + "}"));
        var result = service.resolveSearchCapabilities("selected", null);
        assertEquals(java.util.Set.of("other"), result.catalog().filters().keySet());
        assertTrue(result.catalog().sorts().isEmpty());
        assertEquals(2, result.issues().size());
    }

    @Test
    void interruptionIsNotConvertedIntoAnUnavailableSource() {
        var document = OdpJson.parseTree(Responses.SERVICE_DOCUMENT);
        document.set("search_capabilities", OdpJson.parseTree("{\"filters\":{\"linked\":{\"href\":\"/filters\"}}}"));
        var service = OdpServiceClient.create(URI.create(ORIGIN), request -> {
            if (request.uri().getPath().equals("/.well-known/odp")) return Responses.ok(request, document.toString());
            throw new InterruptedException("Cancelled");
        });
        try {
            assertThrows(IllegalStateException.class, () -> service.resolveSearchCapabilities(null, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void searchDetailsKeepsOfferingsWhenARefinementGroupIsInvalid() {
        String capabilities =
                inline("one", "two").replace("\"operators\":[\"eq\"]", "\"operators\":[\"eq\"],\"refinable\":true");
        var service = client(capabilities, request -> Responses.ok(request, """
                {"odp_version":"1.0","items":[{"id":"item","name":"Item"}],"refinements":[
                {"filter_id":"one","values":[{"value":"yes","count":2}]},
                {"filter_id":"two","values":[{"value":true,"count":2}]}]}
                """));
        var request = OdpJson.parseOfferingSearchRequest(
                "{\"odp_version\":\"1.0\",\"query\":\"items\",\"refinements\":[\"one\",\"two\"]}");
        var result = service.searchOfferingsDetails(request, "terse", null);
        assertEquals(1, result.page().items().size());
        assertEquals(1, result.refinements().size());
        assertEquals("one", result.refinements().get(0).filter().id());
        assertEquals("two", result.issues().get(0).identifier());
        assertThrows(IllegalArgumentException.class, () -> service.continueOfferings("/next", "terse", null));
    }

    private static final String ORIGIN = "https://plants.example";

    private static String filter(String id) {
        return "{\"id\":\"" + id
                + "\",\"title\":\"Title\",\"description\":\"Description\",\"type\":\"string\",\"operators\":[\"eq\"]}";
    }

    private static String inline(String... ids) {
        return "{\"filters\":{\"inline\":["
                + String.join(
                        ",",
                        java.util.Arrays.stream(ids)
                                .map(SearchCapabilityTest::filter)
                                .toList()) + "]}}";
    }

    private static OdpServiceClient client(
            String capabilities,
            java.util.function.Function<java.net.http.HttpRequest, java.net.http.HttpResponse<byte[]>> response) {
        var document = OdpJson.parseTree(Responses.SERVICE_DOCUMENT);
        document.set("search_capabilities", OdpJson.parseTree(capabilities));
        return OdpServiceClient.create(
                URI.create(ORIGIN),
                request -> request.uri().getPath().equals("/.well-known/odp")
                        ? Responses.ok(request, document.toString())
                        : response.apply(request));
    }

    @Test
    void mergesOnlyTheSelectedCollectionAndQuarantinesConflicts() {
        List<String> paths = new ArrayList<>();
        var service = client(inline("price", "service"), request -> {
            paths.add(request.uri().getPath());
            return Responses.ok(
                    request,
                    "{\"odp_version\":\"1.0\",\"id\":\"selected\",\"name\":\"Selected\",\"parent_ids\":[\"parent\"],\"search_capabilities\":"
                            + inline("price", "collection") + "}");
        });
        assertEquals(
                2,
                service.resolveSearchCapabilities(null, null)
                        .catalog()
                        .filters()
                        .size());
        assertTrue(paths.isEmpty());
        var result = service.resolveSearchCapabilities("selected", null);
        assertEquals(
                java.util.Set.of("service", "collection"),
                result.catalog().filters().keySet());
        assertEquals(List.of("/odp/collections/selected"), paths);
        assertEquals("price", result.issues().get(0).identifier());
    }

    @Test
    void validatesWholeLinkedSourceBeforeExposingAnything() {
        var service = client(
                "{\"filters\":{\"linked\":{\"href\":\"/filters\"}}}",
                request -> Responses.ok(
                        request,
                        "{\"odp_version\":\"1.0\",\"items\":[" + filter("repeated") + "]"
                                + (request.uri().getPath().equals("/filters") ? ",\"next\":\"/second\"}" : "}")));
        var result = service.resolveSearchCapabilities(null, null);
        assertTrue(result.catalog().filters().isEmpty());
        assertEquals(1, result.issues().size());
    }

    @Test
    void stopsBeforeSeventeenthPageAndDoesNotCrossOrigins() {
        AtomicInteger calls = new AtomicInteger();
        var service = client("{\"filters\":{\"linked\":{\"href\":\"/filters\"}}}", request -> {
            int page = calls.incrementAndGet();
            return Responses.ok(
                    request,
                    "{\"odp_version\":\"1.0\",\"items\":[" + filter("f" + page) + "],\"next\":\"/page/" + page + "\"}");
        });
        assertTrue(service.resolveSearchCapabilities(null, null)
                .catalog()
                .filters()
                .isEmpty());
        assertEquals(16, calls.get());
        var foreign = client("{\"filters\":{\"linked\":{\"href\":\"https://other.example/filters\"}}}", request -> {
            fail("Cross-origin request");
            return null;
        });
        assertEquals(1, foreign.resolveSearchCapabilities(null, null).issues().size());
    }

    @Test
    void retainsLiveChallengeInScopedFailure() {
        var service = client(
                "{\"filters\":{\"linked\":{\"href\":\"/filters\"}}}",
                request -> Responses.of(request, 401, "{}", Map.of("WWW-Authenticate", List.of("Bearer"))));
        var result = service.resolveSearchCapabilities(null, null);
        assertTrue(result.catalog().filters().isEmpty());
        assertEquals(401, result.issues().get(0).responseFailure().status());
        assertEquals(
                "Bearer",
                result.issues()
                        .get(0)
                        .responseFailure()
                        .headers()
                        .firstValue("WWW-Authenticate")
                        .orElseThrow());
    }

    @Test
    void isolatesMalformedAndUnknownDefinitionsDifferently() {
        var malformed = client(inline("one", "two").replace("\"type\":\"string\"", "\"type\":true"), request -> {
            fail("Unexpected request");
            return null;
        });
        assertTrue(malformed
                .resolveSearchCapabilities(null, null)
                .catalog()
                .filters()
                .isEmpty());
        var unknown =
                client(inline("one", "two").replaceFirst("\"type\":\"string\"", "\"type\":\"future\""), request -> {
                    fail("Unexpected request");
                    return null;
                });
        var result = unknown.resolveSearchCapabilities(null, null);
        assertEquals(java.util.Set.of("two"), result.catalog().filters().keySet());
        assertEquals("one", result.issues().get(0).identifier());
    }
}
