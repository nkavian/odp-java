package org.offeringprotocol.odp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class OdpMediaTypeTest {
    @Test
    void acceptsValidParametersWithoutInterpretingThem() {
        for (String value : List.of(
                "application/odp+json",
                " APPLICATION/ODP+JSON \t",
                "application/odp+json; charset=utf-8",
                "application/odp+json; p=\"semi;colon\"",
                "application/odp+json; p=\"escaped\\\"quote\"",
                "application/odp+json; ;")) {
            assertEquals("application/odp+json", OdpMediaType.essence(value), value);
        }
    }

    @Test
    void rejectsMalformedTypesAndParameters() {
        assertEquals("", OdpMediaType.essence(null));
        for (String value : List.of(
                "",
                "application",
                "/json",
                "application/",
                "application/odp+json;broken",
                "application/odp+json; p=",
                "application/odp+json; p=\"unterminated",
                "application/odp+json; p =v",
                "application/odp+json; p=has space",
                "application/odp+json; p=\"line\nbreak\"",
                "application/odp+json; p=\"x\"garbage",
                "application/odp+json, application/json",
                "application/odp+json\r\n")) {
            assertEquals("", OdpMediaType.essence(value), value);
        }
    }
}
