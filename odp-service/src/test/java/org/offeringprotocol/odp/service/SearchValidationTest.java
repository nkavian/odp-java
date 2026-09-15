package org.offeringprotocol.odp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.SearchCatalog;

class SearchValidationTest {
    @Test
    void validatesBeforeCallingHandlerAndChecksReturnedRefinements() {
        SearchCatalog catalog = new SearchCatalog(List.of(OdpJson.parseFilterDefinition("""
                {"id":"value","title":"Value","description":"Search value","type":"integer",
                 "operators":["eq"],"refinable":true}
                """)), List.of());
        AtomicInteger calls = new AtomicInteger();
        var endpoints = new EnumMap<OdpOperation, OdpService.Endpoint>(StaticCatalog.create(List.of(), List.of()));
        endpoints.put(
                OdpOperation.SEARCH_OFFERINGS,
                new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, request -> {
                    calls.incrementAndGet();
                    return OdpJson.parseTree(
                            "{\"odp_version\":\"1.0\",\"items\":[],\"refinements\":[{\"filter_id\":\"value\",\"values\":[{\"value\":\"wrong\",\"count\":1}]}]}");
                }));
        var service = OdpService.builder("Service", "Description", "en", "/odp")
                .endpoints(endpoints)
                .searchCatalog(request -> catalog)
                .build();
        String bad = "{\"odp_version\":\"1.0\",\"filters\":[{\"id\":\"value\",\"operator\":\"eq\",\"value\":1.5}]}";
        assertEquals(400, service.handle(post(bad)).status());
        assertEquals(0, calls.get());
        assertEquals(
                500,
                service.handle(post("{\"odp_version\":\"1.0\",\"query\":\"test\",\"refinements\":[\"value\"]}"))
                        .status());
        assertEquals(1, calls.get());
    }

    private static OdpHttpRequest post(String body) {
        return new OdpHttpRequest(
                "POST",
                "/odp/offerings/search",
                Map.of(),
                Map.of("Content-Type", List.of("application/odp+json")),
                body);
    }
}
