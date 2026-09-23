package org.offeringprotocol.odp.directory;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpJsonNode;
import org.offeringprotocol.odp.core.PaymentOption;

final class DirectorySources {
    private static final String FIELD_PAYMENTS = "payments";
    private static final String FIELD_PROTOCOLS = "protocols";
    private static final int NAME_ONLY_FIELDS = 1;

    private DirectorySources() {}

    static DirectoryModels.Source read(OdpJsonNode value) {
        DirectoryResults.object(value, "source");
        DirectoryResults.text(value, "type", 128);
        URI url = URI.create(DirectoryResults.text(value, "url", 2048));
        if (!"https".equalsIgnoreCase(url.getScheme())
                || url.getHost() == null
                || url.getRawUserInfo() != null
                || url.getRawFragment() != null
                || url.getPort() > 65535) {
            throw new IllegalArgumentException(
                    "source.url must be an HTTPS document URL without credentials or fragment");
        }
        DirectoryOrigins.requirePublicHost(url.getHost());
        OdpJsonNode discovery = value.get("x402_discovery");
        if (discovery == null || !("true".equals(discovery.toString()) || "false".equals(discovery.toString()))) {
            throw new IllegalArgumentException("source.x402_discovery must be a boolean");
        }
        return OdpJson.treeToValue(value, DirectoryModels.Source.class);
    }

    static void importedService(OdpJsonNode service) {
        DirectoryResults.text(service, "name", 128);
        for (String field :
                List.of("description", "language", "documentation_url", "status_url", "support_url", "website_url")) {
            if (service.has(field) && !service.get(field).isString()) {
                throw new IllegalArgumentException(field + " must be a string");
            }
        }
        for (String field : List.of("keywords", "localizations")) {
            if (service.has(field)) {
                strings(service.get(field), field);
            }
        }
        service.remove("operations");
        if (service.has(FIELD_PROTOCOLS)) {
            OdpJsonNode protocols = DirectoryResults.object(service.get(FIELD_PROTOCOLS), FIELD_PROTOCOLS);
            OdpJsonNode retained = OdpJson.parseTree("{}");
            descriptors(protocols, retained, "enrollment", Set.of("aep"));
            descriptors(protocols, retained, FIELD_PAYMENTS, Set.of("mpp", "x402"));
            descriptors(protocols, retained, "trust", Set.of("tap"));
            service.set(FIELD_PROTOCOLS, retained);
        }
    }

    private static void descriptors(OdpJsonNode protocols, OdpJsonNode retained, String category, Set<String> known) {
        if (!protocols.has(category)) {
            return;
        }
        OdpJsonNode values = protocols.get(category);
        if (!values.isArray() || values.isEmpty()) {
            throw new IllegalArgumentException("protocols." + category + " must be a nonempty array");
        }
        List<OdpJsonNode> selected = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (OdpJsonNode descriptor : values) {
            DirectoryResults.object(descriptor, category);
            String name = DirectoryResults.text(descriptor, "name", 128);
            if (!known.contains(name)) {
                continue;
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate " + category + " descriptor");
            }
            if (FIELD_PAYMENTS.equals(category)) {
                payment(descriptor);
            } else if (descriptor.size() != NAME_ONLY_FIELDS) {
                throw new IllegalArgumentException("Unexpected " + category + " descriptor field");
            }
            selected.add(descriptor);
        }
        if (!selected.isEmpty()) {
            retained.set(category, OdpJson.valueToTree(selected));
        }
    }

    private static void payment(OdpJsonNode descriptor) {
        String authentication = DirectoryResults.text(descriptor, "authentication", 128);
        if (!("required".equals(authentication) || "not-required".equals(authentication))
                || !Set.of("name", "authentication", "options").containsAll(descriptor.fieldNames())) {
            throw new IllegalArgumentException("Invalid payment descriptor");
        }
        if (descriptor.has("options")) {
            List<String> options = strings(descriptor.get("options"), "payment options");
            if (options.isEmpty() || options.size() > 16 || new HashSet<>(options).size() != options.size()) {
                throw new IllegalArgumentException("Invalid payment options");
            }
            options.forEach(PaymentOption::fromValue);
        }
    }

    private static List<String> strings(OdpJsonNode value, String name) {
        if (!value.isArray()) {
            throw new IllegalArgumentException(name + " must be an array of strings");
        }
        List<String> values = new ArrayList<>();
        for (OdpJsonNode item : value) {
            if (!item.isString()) {
                throw new IllegalArgumentException(name + " must contain only strings");
            }
            values.add(item.asString());
        }
        return values;
    }
}
