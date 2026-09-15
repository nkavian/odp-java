package org.offeringprotocol.odp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchCatalogTest {
    private static SearchCatalog catalog(String type) {
        return new SearchCatalog(List.of(OdpJson.parseFilterDefinition("""
                {"id":"value","title":"Value","description":"Search value","type":"%s",
                 "operators":["eq","in","exists"],"refinable":true}
                """.formatted(type))), List.of());
    }

    private static SearchRequests.Offerings request(String operator, String value) {
        return OdpJson.parseOfferingSearchRequest("""
                {"odp_version":"1.0","filters":[{"id":"value","operator":"%s","value":%s}]}
                """.formatted(operator, value));
    }

    @Test
    void checksTypedScalarsAndOperatorShapes() {
        for (String[] example : List.of(
                new String[] {"string", "\"text\"", "1"},
                new String[] {"boolean", "true", "\"true\""},
                new String[] {"integer", "1e3", "1.5"},
                new String[] {"number", "1.5", "\"1.5\""},
                new String[] {"decimal", "\"1.5\"", "\"1e3\""},
                new String[] {"date", "\"2024-02-29\"", "\"2025-02-29\""},
                new String[] {"date-time", "\"2026-01-01T00:00:00.123456789123Z\"", "\"2026-01-01T00:00:00\""})) {
            SearchCatalog catalog = catalog(example[0]);
            assertDoesNotThrow(() -> catalog.validateRequest(request("eq", example[1])));
            assertThrows(IllegalArgumentException.class, () -> catalog.validateRequest(request("eq", example[2])));
            assertDoesNotThrow(() -> catalog.validateRequest(request("exists", "false")));
            assertThrows(IllegalArgumentException.class, () -> catalog.validateRequest(request("exists", "\"false\"")));
            assertThrows(IllegalArgumentException.class, () -> catalog.validateRequest(request("in", example[1])));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> catalog.validateRequest(request("eq", "[" + example[1] + "]")));
        }
    }

    @Test
    void comparesValuesByTheirDeclaredEquality() {
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog("date-time")
                        .validateRequest(request("in", "[\"2026-01-01T00:00:00Z\",\"2026-01-01T23:00:00+23:00\"]")));
        assertDoesNotThrow(() -> catalog("date-time")
                .validateRequest(request("in", "[\"2016-12-31T23:59:60Z\",\"2017-01-01T00:00:00Z\"]")));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog("decimal").validateRequest(request("in", "[\"1.0\",\"1.00\"]")));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog("date-time")
                        .validateRequest(request("in", "[\"2026-01-01T00:00:00Z\",\"2025-12-31T19:00:00-05:00\"]")));
        assertDoesNotThrow(() -> catalog("string").validateRequest(request("in", "[\"A\",\"a\"]")));
    }

    @Test
    void rejectsUnavailableFiltersSortsAndRefinements() {
        SearchCatalog catalog = catalog("string");
        String base = "{\"odp_version\":\"1.0\",\"query\":\"test\",";
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.validateRequest(OdpJson.parseOfferingSearchRequest(base + "\"sort\":\"missing\"}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.validateRequest(
                        OdpJson.parseOfferingSearchRequest(base + "\"refinements\":[\"missing\"]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SearchCatalog(List.of(), List.of()).validateRequest(request("eq", "\"value\"")));
        assertThrows(IllegalArgumentException.class, () -> catalog.validateRequest(request("lt", "\"value\"")));
    }

    @Test
    void validatesRefinementsInRequestContext() {
        SearchCatalog catalog = catalog("decimal");
        SearchRequests.Offerings request = OdpJson.parseOfferingSearchRequest("""
                {"odp_version":"1.0","query":"test","refinements":["value"]}
                """);
        String json = """
                {"odp_version":"1.0","items":[],"refinements":[
                {"filter_id":"value","values":[{"value":"1.00","count":3}]}]}
                """;
        OfferingPage page = OdpJson.parseOfferingSearchResponse(json);
        assertEquals(
                "value",
                catalog.validateRefinements(page, request, false)
                        .get(0)
                        .filter()
                        .id());
        assertThrows(IllegalArgumentException.class, () -> catalog.validateRefinements(page, request, true));
        assertThrows(IllegalArgumentException.class, () -> catalog.validateRefinements(page, null, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.validateRefinements(
                        OdpJson.parseOfferingSearchResponse(json.replace("\"1.00\"", "true")), request, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.validateRefinements(
                        OdpJson.parseOfferingSearchResponse(json.replace(
                                "{\"value\":\"1.00\",\"count\":3}",
                                "{\"value\":\"1.00\",\"count\":3},{\"value\":\"1.0\",\"count\":4}")),
                        request,
                        false));
        assertThrows(
                UnsupportedOperationException.class, () -> catalog.filters().clear());
    }
}
