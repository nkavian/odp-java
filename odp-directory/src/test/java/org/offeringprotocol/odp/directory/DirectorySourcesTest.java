package org.offeringprotocol.odp.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpJsonNode;
import org.offeringprotocol.odp.core.PaymentOption;

class DirectorySourcesTest {
    private static final String DOCUMENT_URL = "https://documents.example/specs/api.json?version=3&key=a%2Fb";

    private static OdpJsonNode imported(String type) {
        OdpJsonNode result = DirectoryResultsTest.result(type);
        OdpJsonNode service = result.get("service");
        service.remove(List.of("description", "language", "localizations", "keywords", "operations", "protocols"));
        service.get("source").put("type", "openapi");
        service.get("source").put("url", DOCUMENT_URL);
        return result;
    }

    @Test
    void readsOptionalImportedMetadataAndExactSourceWithoutInventingOdpCapabilities() {
        for (String type : List.of("openapi", "future-format")) {
            OdpJsonNode result = imported("service");
            OdpJsonNode service = result.get("service");
            service.get("source").put("type", type);
            service.get("source").put("extra", "retained");
            service.get("source").set("x402_discovery", OdpJson.parseTree("true"));
            for (String unverified : List.of(
                    "http", "branding", "mcp", "operations", "odp_version", "payment_origins", "search_capabilities")) {
                service.put(unverified, "not authoritative");
            }
            var response = DirectoryResults.decode(DirectoryResultsTest.response(result));
            assertTrue(response.issues().isEmpty(), response.issues().toString());
            var parsed = assertInstanceOf(
                            DirectoryModels.ServiceResult.class,
                            response.items().get(0))
                    .service();
            assertEquals(type, parsed.source().type());
            assertEquals(DOCUMENT_URL, parsed.source().url());
            assertTrue(parsed.source().x402Discovery());
            assertEquals("retained", parsed.source().additional().get("extra").asString());
            assertNull(parsed.description());
            assertNull(parsed.language());
            assertNull(parsed.protocols());
            assertTrue(parsed.operations().isEmpty());
            assertTrue(parsed.localizations().isEmpty());
            assertTrue(parsed.keywords().isEmpty());
            assertEquals(
                    java.util.Set.of("service_id", "source"),
                    parsed.additional().keySet());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> parsed.source().additional().clear());
            assertEquals(
                    DOCUMENT_URL,
                    OdpJson.parseTree(OdpJson.write(parsed)).at("/source/url").asString());
        }
    }

    @Test
    void retainsCollectionIdentityAndValidImportedMetadata() {
        OdpJsonNode first = imported("collection");
        OdpJsonNode second = imported("collection");
        second.get("service").put("service_id", "another-document");
        second.get("service").get("source").put("url", "https://documents.example/other.json");
        first.get("service").put("description", "");
        first.get("service").put("language", "en");
        first.get("service").set("localizations", OdpJson.parseTree("[\"en\"]"));
        first.get("service").set("keywords", OdpJson.parseTree("[\"weather\"]"));
        for (String field : List.of("documentation_url", "status_url", "support_url", "website_url")) {
            first.get("service").put(field, "https://example.com/" + field);
        }
        var response = DirectoryResults.decode(DirectoryResultsTest.response(first, second));
        assertTrue(response.issues().isEmpty());
        var one = assertInstanceOf(
                DirectoryModels.CollectionResult.class, response.items().get(0));
        var two = assertInstanceOf(
                DirectoryModels.CollectionResult.class, response.items().get(1));
        assertEquals(one.service().serviceOrigin(), two.service().serviceOrigin());
        assertEquals(one.collection().id(), two.collection().id());
        assertEquals("another-document", two.service().serviceId());
        assertEquals("", one.service().description());
        assertEquals(List.of("weather"), one.service().keywords());
        assertEquals(List.of("en"), one.service().localizations());
        assertEquals("https://example.com/website_url", one.service().websiteUrl());
        String exact = "HTTPS://Documents.Example:443/specs/api.json?version=3";
        second.get("service").get("source").put("url", exact);
        var uppercase = DirectoryResults.decode(DirectoryResultsTest.response(second));
        assertTrue(uppercase.issues().isEmpty());
        assertEquals(
                exact,
                assertInstanceOf(
                                DirectoryModels.CollectionResult.class,
                                uppercase.items().get(0))
                        .service()
                        .source()
                        .url());
    }

    @Test
    void rejectsMalformedSourcesAndImportedFieldsPerItem() {
        List<Consumer<OdpJsonNode>> changes = new ArrayList<>(List.of(
                service -> service.remove("source"),
                service -> service.set("source", OdpJson.parseTree("null")),
                service -> service.get("source").remove("type"),
                service -> service.get("source").put("type", " "),
                service -> service.get("source").remove("url"),
                service -> service.get("source").remove("x402_discovery"),
                service -> service.get("source").put("x402_discovery", "false"),
                service -> service.get("source").set("x402_discovery", OdpJson.parseTree("null")),
                service -> service.remove("name")));
        for (String url : List.of(
                "http://example.com/api.json",
                "/openapi.json",
                "https://user:secret@example.com/spec",
                "https://example.com/spec#part",
                "https://localhost/spec",
                "https:/spec",
                "https://127.0.0.1/spec",
                "https://example.com:70000/spec",
                "https://[")) {
            changes.add(service -> service.get("source").put("url", url));
        }
        for (String field : List.of(
                "description",
                "language",
                "documentation_url",
                "status_url",
                "support_url",
                "website_url",
                "localizations",
                "keywords")) {
            changes.add(service -> service.set(field, OdpJson.parseTree("null")));
            changes.add(service -> service.set(field, OdpJson.parseTree("42")));
        }
        changes.add(service -> service.set("keywords", OdpJson.parseTree("[null]")));
        changes.add(service -> service.set("localizations", OdpJson.parseTree("[1]")));
        for (var change : changes) {
            OdpJsonNode invalid = imported("service");
            change.accept(invalid.get("service"));
            var response = DirectoryResults.decode(DirectoryResultsTest.response(invalid, imported("service")));
            assertEquals(1, response.items().size(), invalid.toString());
            assertEquals(1, response.issues().size(), invalid.toString());
            assertEquals(0, response.issues().get(0).index());
        }
    }

    @Test
    void preservesOnlyValidatedRecognizedProtocolEvidence() {
        OdpJsonNode result = imported("service");
        result.get("service").set("protocols", OdpJson.parseTree("""
                {"enrollment":[{"name":"future"},{"name":"aep"}],
                 "payments":[{"name":"x402","authentication":"required","options":["base"]},
                             {"name":"mpp","authentication":"not-required"},{"name":"future"}],
                 "trust":[{"name":"tap"},{"name":"future"}],"future":true}
                """));
        var response = DirectoryResults.decode(DirectoryResultsTest.response(result));
        assertTrue(response.issues().isEmpty(), response.issues().toString());
        var protocols = assertInstanceOf(
                        DirectoryModels.ServiceResult.class, response.items().get(0))
                .service()
                .protocols();
        assertEquals(1, protocols.enrollment().size());
        assertEquals(2, protocols.payments().size());
        assertEquals(List.of(PaymentOption.BASE), protocols.payments().get(0).options());
        assertEquals(1, protocols.trust().size());
        for (String accepted : List.of(
                "{}",
                "{\"payments\":[{\"name\":\"future\"}]}",
                "{\"payments\":[{\"name\":\"x402\",\"authentication\":\"required\"}]}")) {
            result.get("service").set("protocols", OdpJson.parseTree(accepted));
            assertTrue(
                    DirectoryResults.decode(DirectoryResultsTest.response(result))
                            .issues()
                            .isEmpty(),
                    accepted);
        }
        for (String invalid : List.of(
                "null",
                "[]",
                "{\"trust\":null}",
                "{\"trust\":[]}",
                "{\"trust\":[null]}",
                "{\"trust\":[{}]}",
                "{\"trust\":[{\"name\":\"tap\",\"extra\":true}]}",
                "{\"trust\":[{\"name\":\"tap\"},{\"name\":\"tap\"}]}")) {
            result.get("service").set("protocols", OdpJson.parseTree(invalid));
            assertEquals(
                    1,
                    DirectoryResults.decode(DirectoryResultsTest.response(result))
                            .issues()
                            .size(),
                    invalid);
        }
        for (String invalid : List.of(
                "{}",
                "{\"authentication\":\"optional\"}",
                "{\"authentication\":null}",
                "{\"authentication\":\"not-required\",\"extra\":true}",
                "{\"authentication\":\"required\",\"options\":[]}",
                "{\"authentication\":\"required\",\"options\":[\"base\",\"base\"]}",
                "{\"authentication\":\"required\",\"options\":"
                        + OdpJson.write(java.util.Collections.nCopies(17, "base")) + "}",
                "{\"authentication\":\"required\",\"options\":[\"unknown\"]}")) {
            OdpJsonNode payment = OdpJson.parseTree(invalid).put("name", "x402");
            result.get("service").set("protocols", OdpJson.parseTree("{\"payments\":[" + payment + "]}"));
            assertEquals(
                    1,
                    DirectoryResults.decode(DirectoryResultsTest.response(result))
                            .issues()
                            .size(),
                    invalid);
        }
    }

    @Test
    void retainsNativeValidationAndSnapshotsSourceFilters() {
        OdpJsonNode nativeResult = DirectoryResultsTest.result("service");
        nativeResult.get("service").remove("language");
        assertEquals(
                1,
                DirectoryResults.decode(DirectoryResultsTest.response(nativeResult))
                        .issues()
                        .size());
        OdpJsonNode imported = imported("service").get("service");
        assertEquals(
                1,
                DirectoryClient.decodeSearchPage("{\"items\":[" + imported + "]}")
                        .issues()
                        .size());
        OdpJsonNode nativeService = OdpJson.parseTree(DirectoryResultsTest.SERVICE);
        nativeService.remove("source");
        assertNull(DirectoryClient.decodeSearchPage("{\"items\":[" + nativeService + "]}")
                .items()
                .get(0)
                .source());
        List<String> sources = new ArrayList<>(List.of("odp", "openapi"));
        var filters = new DirectoryModels.ServiceFilters(null, null, null, null, null, sources);
        sources.clear();
        assertEquals(List.of("odp", "openapi"), filters.sources());
        assertEquals("{\"sources\":[\"odp\",\"openapi\"]}", OdpJson.write(filters));
        assertThrows(
                UnsupportedOperationException.class, () -> filters.sources().clear());
        assertEquals("{}", OdpJson.write(new DirectoryModels.ServiceFilters(null, null, null, null)));
        for (List<String> invalid : List.of(
                List.<String>of(),
                List.of("ODP"),
                List.of("future"),
                List.of("odp", "odp"),
                Arrays.asList("odp", null),
                List.of("odp", "openapi", "future"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DirectoryModels.ServiceFilters(null, null, null, null, null, invalid));
        }
    }
}
