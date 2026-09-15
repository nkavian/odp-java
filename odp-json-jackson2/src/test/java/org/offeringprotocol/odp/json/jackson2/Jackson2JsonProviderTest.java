package org.offeringprotocol.odp.json.jackson2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpValidationException;

class Jackson2JsonProviderTest {
    @Test
    void acceptsCompatibleVersionsWithoutChangingTheReceivedVersion() {
        String offering = "{\"odp_version\":\"1.0\",\"id\":\"desk\",\"name\":\"Desk\"}";
        for (String version : java.util.List.of("1.0", "1.1", "1.7")) {
            assertEquals(
                    version,
                    OdpJson.parseOffering(offering.replace("1.0", version)).odpVersion());
            assertEquals(
                    version,
                    OdpJson.parseOfferingSearchRequest("{\"odp_version\":\"" + version + "\",\"query\":\"desk\"}")
                            .odpVersion());
            assertEquals(
                    version,
                    OdpJson.parsePage(
                                    "{\"odp_version\":\"" + version
                                            + "\",\"items\":[{\"id\":\"desk\",\"name\":\"Desk\"}]}",
                                    org.offeringprotocol.odp.core.Offering.class)
                            .odpVersion());
        }
        for (String version : java.util.List.of("2.0", "3.0", "1", "01.0", "1.01", "1.0.0", "1.7\\n")) {
            assertThrows(
                    OdpValidationException.class,
                    () -> OdpJson.parseOffering(offering.replace("1.0", version)),
                    version);
        }
    }

    @Test
    void validatesTypedSearchValuesWithoutProviderCoercion() {
        var definition = OdpJson.parseFilterDefinition("""
                {"id":"price","title":"Price","description":"Price","type":"decimal","operators":["eq","in"]}
                """);
        var catalog =
                new org.offeringprotocol.odp.core.SearchCatalog(java.util.List.of(definition), java.util.List.of());
        String valid =
                "{\"odp_version\":\"1.0\",\"filters\":[{\"id\":\"price\",\"operator\":\"eq\",\"value\":\"1.00\"}]}";
        catalog.validateRequest(OdpJson.parseOfferingSearchRequest(valid));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.validateRequest(OdpJson.parseOfferingSearchRequest(valid.replace("\"1.00\"", "1.00"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.validateRequest(OdpJson.parseOfferingSearchRequest(
                        valid.replace("\"eq\"", "\"in\"").replace("\"1.00\"", "[\"1.0\",\"1.00\"]"))));
    }

    @Test
    void rejectsNumericPricesBeforePageDeserialization() {
        String page = """
                {"odp_version":"1.0","items":[{"id":"desk","name":"Desk",
                "price":{"type":"fixed","amount":1450,"currency":"USD"}}]}
                """;
        assertThrows(OdpValidationException.class, () -> OdpJson.parseOfferingSearchResponse(page));
        assertThrows(
                OdpValidationException.class,
                () -> OdpJson.parsePage(page, org.offeringprotocol.odp.core.Offering.class));
        assertEquals(
                "1450",
                OdpJson.parsePage(page.replace(":1450", ":\"1450\""), org.offeringprotocol.odp.core.Offering.class)
                        .items()
                        .get(0)
                        .price()
                        .amount());
    }

    @Test
    void decodesDirectoryTimestamps() {
        assertEquals(
                Instant.parse("2026-09-18T12:30:00Z"),
                OdpJson.treeToValue(OdpJson.parseTree("\"2026-09-18T12:30:00Z\""), Instant.class));
    }

    @Test
    void supportsTheOdpJsonContract() {
        String document = """
                {
                  "odp_version":"1.0",
                  "name":"Example Service",
                  "description":"An ODP Service.",
                  "language":"en",
                  "localizations":["en"],
                  "operations":[
                    {"authentication":"not-required","name":"get-offering"},
                    {"authentication":"not-required","name":"list-offerings"}
                  ],
                  "http":{"endpoint_base":"/odp"}
                }
                """;

        assertEquals("Example Service", OdpJson.parseServiceDocument(document).name());
        assertThrows(OdpValidationException.class, () -> OdpJson.parseServiceDocument("{}"));
    }
}
