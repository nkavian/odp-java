package org.offeringprotocol.odp.conformance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import org.offeringprotocol.odp.agent.OdpRequestException;
import org.offeringprotocol.odp.agent.OdpServiceClient;
import org.offeringprotocol.odp.agent.OdpTransport;
import org.offeringprotocol.odp.agent.OfferingDetails;
import org.offeringprotocol.odp.agent.OfferingIssue;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.OdpPagination;
import org.offeringprotocol.odp.core.OdpUris;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.OperationDescriptor;
import org.offeringprotocol.odp.core.Page;
import org.offeringprotocol.odp.core.ResourceIdentity;
import org.offeringprotocol.odp.core.SearchCapabilities.FilterDefinition;
import org.offeringprotocol.odp.core.SearchCapabilities.SortDefinition;
import org.offeringprotocol.odp.core.SearchCatalog;
import org.offeringprotocol.odp.core.ServiceDocument;
import org.offeringprotocol.odp.service.OdpHttpRequest;
import org.offeringprotocol.odp.service.OdpService;
import org.offeringprotocol.odp.service.StaticCatalog;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Process adapter for the language-neutral ODP conformance harness. */
public final class ConformanceAdapter {
    private static final String REQUEST_FIELD = "request";
    private static final String RETRY_OPERATION = "retry";
    private static final String LIMIT_FAILURE_SCOPE = "limit-failure-scope";
    private static final String FILTERS_FIELD = "filters";
    private static final String OPERATIONS_FIELD = "operations";
    private static final String AUTHENTICATION_FIELD = "authentication";
    private static final String NOT_REQUIRED_AUTH = "not-required";
    private static final String SEARCH_OFFERINGS_OPERATION = "search-offerings";
    private static final String INLINE_FIELD = "inline";
    private static final String COMPUTE_COUNTS = "compute-counts";
    private static final String REPRESENTATION_FIELD = "representation";
    private static final String ODP_MEDIA_TYPE = "application/odp+json";
    private static final String SKIPPED_STATUS = "skipped";
    private static final String EXPECTED_FIELD = "expected";
    private static final String ITEM_ID = "item";
    private static final String OFFERINGS_PATH = "/odp/offerings";
    private static final String GET_METHOD = "GET";
    private static final String TERSE_REPRESENTATION = "terse";
    private static final String VERSION_FIELD = "odp_version";
    private static final String ITEMS_FIELD = "items";
    private static final String NAME_FIELD = "name";
    private static final String OPENAPI_FIELD = "openapi";
    private static final String SERVICE_ORIGIN = "https://service.example";
    private static final String VALIDATE_ACTIONS = "validate-actions";
    private static final String RESOLVE_OPENAPI = "resolve-openapi";
    private static final String STATUS_FIELD = "status";
    private static final String ITEM_NAME = "Item";
    private static final String SCHEMA_MEDIA_TYPE = "application/schema+json";
    private static final String SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final String SCHEMA_DIALECT_FIELD = "$schema";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String FULL_REPRESENTATION = "full";
    private static final String OFFERING_PATH = "/odp/offerings/item";
    private static final int MAXIMUM_MESSAGE_LENGTH = 1024;
    private static final String AGENT_ROLE = "agent";
    private static final String DOCUMENT_FIELD = "document";
    private static final String ROOT_SCHEMA_URL = "https://schemas.example/root.json";
    private static final String FILTER_ADVERTISEMENT = "filter-advertisement";
    private static final String NORMALIZE_AGENT_RESPONSE = "normalize-agent-response";
    private static final String VALIDATE_ADVERTISEMENT = "validate-advertisement";
    private static final String VALIDATE_PROBLEM = "validate-problem";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ConformanceAdapter() {}

    public static void main(String[] arguments) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                if (!line.isBlank()) {
                    System.out.println( // NOPMD - Standard output is the adapter protocol channel.
                            JSON.writeValueAsString(evaluate(JSON.readTree(line))));
                }
                line = reader.readLine();
            }
        }
    }

    private static Map<String, Object> evaluate(JsonNode request) {
        int sequence = required(request, "sequence").asInt();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("protocol_version", "1");
        response.put("sequence", sequence);
        try {
            String subject = text(required(request, "vector"), "subject");
            JsonNode test = required(request, "case");
            String role = text(request, "role");
            Evaluation evaluation = evaluateCase(subject, test, role);
            if (SKIPPED_STATUS.equals(evaluation.status()) && evaluation.message() == null) {
                evaluation = new Evaluation(SKIPPED_STATUS, skipReason(subject, test));
            }
            response.put(STATUS_FIELD, evaluation.status());
            if (evaluation.message() != null) {
                response.put("message", evaluation.message());
            }
        } catch (RuntimeException exception) {
            response.put(STATUS_FIELD, "failed");
            response.put("message", truncate(exception.getMessage()));
        }
        return response;
    }

    private static Evaluation evaluateCase(String subject, JsonNode test, String role) {
        return switch (subject) {
            case "local-identifier" -> result(OdpUris.isLocalResourceIdentifier(text(test, "value")) == valid(test));
            case "identity-comparison" -> evaluateIdentity(test);
            case "service-origin" -> evaluateServiceOrigin(test);
            case "resource-reference" -> evaluateReference(test);
            case "service-document" -> parse(test, DOCUMENT_FIELD, OdpJson::parseServiceDocument);
            case "collection-envelope" -> parse(test, DOCUMENT_FIELD, OdpJson::parseCollection);
            case "auth-expands" -> parse(test, DOCUMENT_FIELD, value -> OdpJson.parsePage(value, Offering.class));
            case "representation-selection" -> evaluateSelection(test, role);
            case "detail-fields" -> evaluateDetailFields(test, role);
            case "media-negotiation" -> evaluateMedia(test, role);
            case "version-placement" -> evaluateVersionPlacement(test, role);
            case "protocol-version" -> evaluateProtocolVersion(test);
            case "security-contract" -> evaluateSecurity(test, role);
            case "offering-contract" ->
                FULL_REPRESENTATION.equals(optionalText(test, REPRESENTATION_FIELD))
                        ? parse(test, DOCUMENT_FIELD, OdpJson::parseOffering)
                        : evaluateTerseOffering(test, role);
            case "action-contract" -> evaluateActions(test, role);
            case "filter-sort-contract" -> evaluateFilter(test);
            case "search-capability-contract" -> evaluateSearchCapabilities(test, role);
            case "refinement-contract" -> evaluateRefinement(test);
            case "collection-hierarchy", "collection-membership" -> evaluateCatalog(test, role);
            case "collection-search-contract" ->
                "validate-request".equals(operation(test))
                        ? parse(test, REQUEST_FIELD, OdpJson::parseCollectionSearchRequest)
                        : skipped();
            case "composition-contract" -> evaluateComposition(test, role);
            case "offering-search-contract" ->
                "validate-request".equals(operation(test))
                        ? parse(test, REQUEST_FIELD, OdpJson::parseOfferingSearchRequest)
                        : skipped();
            case "attribute-schema-retrieval" ->
                !AGENT_ROLE.equals(role) && !"validate-reference".equals(operation(test))
                        ? new Evaluation(
                                SKIPPED_STATUS,
                                "Service publishes schema references; it does not retrieve or validate remote Attribute Schema graphs")
                        : evaluateAttributeSchema(test);
            case "pagination-contract" -> evaluatePagination(test, role);
            case "errors-limits-contract" -> evaluateErrorsAndLimits(test, role);
            case "role-baseline" -> evaluateBaseline(test, role);
            default -> skipped();
        };
    }

    private static ServiceDocument baselineDocument() {
        return document(List.of(
                new OperationDescriptor(AuthenticationRequirement.NOT_REQUIRED, OdpOperation.LIST_OFFERINGS),
                new OperationDescriptor(AuthenticationRequirement.NOT_REQUIRED, OdpOperation.GET_OFFERING)));
    }

    private static Evaluation evaluateSecurity(JsonNode test, String role) {
        if (!"validate-redirect".equals(operation(test))) return skipped();
        if (!AGENT_ROLE.equals(role))
            return new Evaluation(SKIPPED_STATUS, "Service does not retrieve supporting resources");
        String source = text(test, "from");
        String target = text(test, "to");
        AtomicInteger calls = new AtomicInteger();
        ObjectNode offering = JSON.createObjectNode()
                .put(VERSION_FIELD, Odp.VERSION)
                .put("id", ITEM_ID)
                .put(NAME_FIELD, ITEM_NAME);
        offering.putObject("schema").put("url", source);
        OdpServiceClient client = OdpServiceClient.create(
                URI.create(SERVICE_ORIGIN),
                request -> response(
                        request,
                        Odp.SERVICE_DOCUMENT_PATH.equals(request.uri().getPath())
                                ? OdpJson.write(baselineDocument())
                                : offering.toString(),
                        ODP_MEDIA_TYPE,
                        200),
                request -> {
                    calls.incrementAndGet();
                    return request.uri().toString().equals(source)
                            ? response(request, "", Map.of("Location", List.of(target)), 302)
                            : response(
                                    request,
                                    "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}",
                                    SCHEMA_MEDIA_TYPE,
                                    200);
                });
        OfferingDetails details = client.getOfferingDetails(ITEM_ID, null);
        return result((details.attributeSchema() != null) == valid(test) && calls.get() == (valid(test) ? 2 : 1));
    }

    private static Evaluation evaluateProtocolVersion(JsonNode test) {
        if (!text(test, "supported").split("\\.")[0].equals(Odp.VERSION.split("\\.")[0]))
            return new Evaluation(
                    SKIPPED_STATUS,
                    "The vector configures a different supported version; this SDK supports " + Odp.VERSION);
        ObjectNode offering = JSON.createObjectNode()
                .put(VERSION_FIELD, text(test, "received"))
                .put("id", ITEM_ID)
                .put(NAME_FIELD, ITEM_NAME);
        return parseValue(
                offering.toString(),
                OdpJson::parseOffering,
                valid(test) && required(test, "compatible").asBoolean());
    }

    private static Evaluation evaluateCatalog(JsonNode test, String role) {
        if (AGENT_ROLE.equals(role))
            return new Evaluation(
                    SKIPPED_STATUS,
                    "Agent exposes catalog operations but does not traverse hierarchy or compute inverse membership");
        List<org.offeringprotocol.odp.core.Collection> collections = new ArrayList<>();
        List<Offering> offerings = new ArrayList<>();
        if (test.has("chain_length")) {
            int edges = required(test, "chain_length").asInt();
            for (int index = 0; index <= edges; index++) {
                ObjectNode collection = JSON.createObjectNode()
                        .put(VERSION_FIELD, Odp.VERSION)
                        .put("id", "collection-" + index)
                        .put(NAME_FIELD, "Collection");
                if (index > 0) collection.putArray("parent_ids").add("collection-" + (index - 1));
                collections.add(OdpJson.parseCollection(collection.toString()));
            }
        } else {
            for (JsonNode value : required(test, "collections")) {
                ObjectNode collection = value.isString()
                        ? JSON.createObjectNode().put("id", textValue(value))
                        : (ObjectNode) value.deepCopy();
                collection.put(VERSION_FIELD, Odp.VERSION).put(NAME_FIELD, "Collection");
                collections.add(OdpJson.parseCollection(collection.toString()));
            }
        }
        if (test.has("offerings")) {
            for (JsonNode value : required(test, "offerings")) {
                ObjectNode offering = (ObjectNode) value.deepCopy();
                offering.put(VERSION_FIELD, Odp.VERSION).put(NAME_FIELD, "Offering");
                offerings.add(OdpJson.parseOffering(offering.toString()));
            }
        }
        try {
            var endpoints = StaticCatalog.create(offerings, collections);
            if (test.has("offering_ids")) {
                var response = new OdpService(baselineDocument(), endpoints)
                        .handle(new OdpHttpRequest(
                                GET_METHOD,
                                "/odp/collections/" + text(test, "query_collection_id") + "/offerings",
                                Map.of(),
                                Map.of(),
                                null));
                var page = OdpJson.parsePage(response.body(), Offering.class);
                return result(
                        JSON.valueToTree(page.items().stream().map(Offering::id).toList())
                                .equals(required(test, "offering_ids")));
            }
            return result(valid(test));
        } catch (IllegalArgumentException exception) {
            return result(test.has("valid") && !valid(test));
        }
    }

    private static Evaluation evaluateTerseOffering(JsonNode test, String role) {
        ObjectNode page = JSON.createObjectNode().put(VERSION_FIELD, Odp.VERSION);
        page.putArray(ITEMS_FIELD).add(required(test, DOCUMENT_FIELD));
        boolean accepted;
        if (AGENT_ROLE.equals(role)) {
            try {
                reading(page.toString(), ODP_MEDIA_TYPE).listOfferings(TERSE_REPRESENTATION, null, null);
                accepted = true;
            } catch (IllegalArgumentException | IllegalStateException exception) {
                accepted = false;
            }
        } else {
            accepted = serving(OdpJson.parseTree(page.toString()))
                            .handle(new OdpHttpRequest(GET_METHOD, OFFERINGS_PATH, Map.of(), Map.of(), null))
                            .status()
                    == 200;
        }
        return result(accepted == valid(test));
    }

    private static Evaluation evaluateFilter(JsonNode test) {
        if ("validate-expression".equals(operation(test)) || "validate-sort".equals(operation(test))) {
            try {
                List<FilterDefinition> filters = new ArrayList<>();
                if (test.has("definition"))
                    filters.add(OdpJson.parseFilterDefinition(
                            required(test, "definition").toString()));
                if (test.has("definitions")) {
                    for (JsonNode definition : required(test, "definitions"))
                        filters.add(OdpJson.parseFilterDefinition(
                                completeDefinition(definition, false).toString()));
                }
                List<SortDefinition> sorts = test.has("sort")
                        ? List.of(OdpJson.parseSortDefinition(
                                required(test, "sort").toString()))
                        : List.of();
                SearchCatalog catalog = new SearchCatalog(filters, sorts);
                if (test.has("expression")) {
                    ObjectNode request = JSON.createObjectNode().put(VERSION_FIELD, Odp.VERSION);
                    request.putArray(FILTERS_FIELD).add(required(test, "expression"));
                    catalog.validateRequest(OdpJson.parseOfferingSearchRequest(request.toString()));
                }
                return result(valid(test));
            } catch (IllegalArgumentException exception) {
                return result(!valid(test));
            }
        }
        if (!"validate-definition".equals(operation(test))) return skipped();
        ObjectNode document = (ObjectNode) JSON.readTree(OdpJson.write(baselineDocument()));
        document.withArray(OPERATIONS_FIELD)
                .addObject()
                .put(AUTHENTICATION_FIELD, NOT_REQUIRED_AUTH)
                .put(NAME_FIELD, SEARCH_OFFERINGS_OPERATION);
        document.putObject("search_capabilities")
                .putObject(FILTERS_FIELD)
                .putArray(INLINE_FIELD)
                .add(required(test, "definition"));
        return parseValue(document.toString(), OdpJson::parseServiceDocument, valid(test));
    }

    private static Evaluation evaluateRefinement(JsonNode test) {
        if (COMPUTE_COUNTS.equals(operation(test)))
            return new Evaluation(
                    SKIPPED_STATUS,
                    "Application catalog handlers compute contextual bucket counts; SDK validates the request and response");
        try {
            List<FilterDefinition> filters = new ArrayList<>();
            for (JsonNode definition : required(test, "definitions"))
                filters.add(OdpJson.parseFilterDefinition(
                        completeDefinition(definition, false).toString()));
            SearchCatalog catalog = new SearchCatalog(filters, List.of());
            ObjectNode request = (ObjectNode) required(test, REQUEST_FIELD).deepCopy();
            request.put(VERSION_FIELD, Odp.VERSION).put("query", "conformance");
            var parsed = OdpJson.parseOfferingSearchRequest(request.toString());
            catalog.validateRequest(parsed);
            if (test.has("response")) {
                ObjectNode response = (ObjectNode) required(test, "response").deepCopy();
                response.put(VERSION_FIELD, Odp.VERSION).putArray(ITEMS_FIELD);
                catalog.validateRefinements(
                        OdpJson.parseOfferingSearchResponse(response.toString()),
                        parsed,
                        test.path("continuation").asBoolean(false));
            }
            return result(valid(test));
        } catch (IllegalArgumentException exception) {
            return result(!valid(test));
        }
    }

    private static ObjectNode completeDefinition(JsonNode input, boolean sort) {
        ObjectNode definition = (ObjectNode) input.deepCopy();
        if (!definition.has("title")) definition.put("title", "Conformance definition");
        if (!definition.has(DESCRIPTION_FIELD)) definition.put(DESCRIPTION_FIELD, "Conformance definition");
        if (sort) {
            if (!definition.has("keys")) definition.putArray("keys").addObject().put("filter_id", "material");
            for (JsonNode key : definition.get("keys")) {
                ObjectNode object = (ObjectNode) key;
                if (!object.has("direction")) object.put("direction", "ascending");
                if (!object.has("missing")) object.put("missing", "last");
            }
        } else {
            if (!definition.has("type")) definition.put("type", "string");
            if (!definition.has("operators")) definition.putArray("operators").add("eq");
        }
        return definition;
    }

    private static ObjectNode completeAdvertisement(JsonNode advertisement) {
        ObjectNode result = (ObjectNode) advertisement.deepCopy();
        for (String kind : List.of(FILTERS_FIELD, "sorts")) {
            JsonNode source = result.get(kind);
            if (source == null) continue;
            JsonNode inline = source.isArray() ? source : source.get(INLINE_FIELD);
            if (inline != null && inline.isArray()) {
                var definitions = JSON.createArrayNode();
                for (JsonNode definition : inline)
                    definitions.add(completeDefinition(definition, "sorts".equals(kind)));
                if (source.isArray()) {
                    if (definitions.isEmpty()) result.remove(kind);
                    else result.putObject(kind).set(INLINE_FIELD, definitions);
                } else ((ObjectNode) source).set(INLINE_FIELD, definitions);
            }
        }
        return result;
    }

    private static Evaluation evaluateSearchCapabilities(JsonNode test, String role) {
        String operation = operation(test);
        String origin = test.has("service_origin") ? text(test, "service_origin") : SERVICE_ORIGIN;
        if (VALIDATE_ADVERTISEMENT.equals(operation)) {
            try {
                var capabilities = OdpJson.parseSearchCapabilities(
                        completeAdvertisement(required(test, "advertisement")).toString());
                boolean supported = false;
                for (JsonNode name : required(test, OPERATIONS_FIELD))
                    if (SEARCH_OFFERINGS_OPERATION.equals(name.asString())) supported = true;
                SearchCatalog.validateAdvertisement(capabilities, supported, origin);
                return result(valid(test));
            } catch (IllegalArgumentException exception) {
                return result(!valid(test));
            }
        }
        if (!AGENT_ROLE.equals(role))
            return new Evaluation(
                    SKIPPED_STATUS,
                    "Linked-source retrieval and tolerant capability merging are Agent operations; Services publish validated definitions");
        Map<String, String> pages = new LinkedHashMap<>();
        ObjectNode capabilities;
        boolean merge = "merge-capabilities".equals(operation);
        if (merge) capabilities = completeAdvertisement(required(test, "service"));
        else {
            String href = test.has("href") ? text(test, "href") : "/filters";
            capabilities = JSON.createObjectNode();
            capabilities.putObject(FILTERS_FIELD).putObject("linked").put("href", href);
            if (test.has("pages")) {
                for (JsonNode page : required(test, "pages")) {
                    ObjectNode expanded = (ObjectNode) page.deepCopy();
                    var definitions = expanded.putArray(ITEMS_FIELD);
                    for (JsonNode definition : page.path(ITEMS_FIELD))
                        definitions.add(completeDefinition(definition, false));
                    pages.put(URI.create(origin).resolve(href).getPath(), expanded.toString());
                    href = optionalText(page, "next");
                }
            } else {
                int count = required(test, "page_count").asInt();
                for (int index = 0; index < count; index++) {
                    ObjectNode page = JSON.createObjectNode().put(VERSION_FIELD, Odp.VERSION);
                    page.putArray(ITEMS_FIELD)
                            .add(completeDefinition(JSON.createObjectNode().put("id", "f" + index), false));
                    if (index + 1 < count) page.put("next", "/filters/" + (index + 1));
                    pages.put(index == 0 ? href : "/filters/" + index, page.toString());
                }
            }
        }
        ObjectNode document = (ObjectNode) JSON.readTree(OdpJson.write(baselineDocument()));
        document.withArray(OPERATIONS_FIELD)
                .addObject()
                .put(NAME_FIELD, SEARCH_OFFERINGS_OPERATION)
                .put(AUTHENTICATION_FIELD, NOT_REQUIRED_AUTH);
        document.withArray(OPERATIONS_FIELD)
                .addObject()
                .put(NAME_FIELD, "get-collection")
                .put(AUTHENTICATION_FIELD, NOT_REQUIRED_AUTH);
        if (!capabilities.isEmpty()) document.set("search_capabilities", capabilities);
        String collectionId = optionalText(test, "collection_id");
        if (collectionId != null) {
            ObjectNode collection = JSON.createObjectNode()
                    .put(VERSION_FIELD, Odp.VERSION)
                    .put("id", collectionId)
                    .put(NAME_FIELD, "Collection");
            collection.set("search_capabilities", completeAdvertisement(required(test, "selected_collection")));
            pages.put("/odp/collections/" + collectionId, collection.toString());
        }
        AtomicInteger calls = new AtomicInteger();
        OdpServiceClient client = OdpServiceClient.create(URI.create(origin), request -> {
            if (Odp.SERVICE_DOCUMENT_PATH.equals(request.uri().getPath()))
                return response(request, document.toString(), ODP_MEDIA_TYPE, 200);
            calls.incrementAndGet();
            String body = pages.get(request.uri().getPath());
            return response(request, body == null ? "{}" : body, ODP_MEDIA_TYPE, body == null ? 404 : 200);
        });
        var resolved = client.resolveSearchCapabilities(collectionId, null);
        if (merge) {
            JsonNode expected = required(test, EXPECTED_FIELD);
            return result(JSON.valueToTree(
                                    new ArrayList<>(resolved.catalog().filters().keySet()))
                            .equals(expected.get("filter_ids"))
                    && JSON.valueToTree(
                                    new ArrayList<>(resolved.catalog().sorts().keySet()))
                            .equals(expected.get("sort_ids"))
                    && resolved.issues().size() == expected.path("issues").size());
        }
        return result(resolved.issues().isEmpty() == valid(test) && calls.get() <= 16);
    }

    private static Evaluation evaluateActions(JsonNode test, String role) {
        if (!AGENT_ROLE.equals(role))
            return new Evaluation(
                    SKIPPED_STATUS,
                    "Action quarantine and OpenAPI retrieval are Agent operations; Service validates advertised descriptors");
        ObjectNode offering = JSON.createObjectNode()
                .put(VERSION_FIELD, Odp.VERSION)
                .put("id", ITEM_ID)
                .put(NAME_FIELD, ITEM_NAME);
        ObjectNode document = (ObjectNode) JSON.readTree(OdpJson.write(baselineDocument()));
        ObjectNode openapi = JSON.createObjectNode();
        if (VALIDATE_ACTIONS.equals(operation(test))) {
            offering.set("actions", required(test, "actions"));
        } else if (RESOLVE_OPENAPI.equals(operation(test))) {
            offering.putArray("actions")
                    .addObject()
                    .put("id", "invoke")
                    .put("rel", "invoke")
                    .put(AUTHENTICATION_FIELD, NOT_REQUIRED_AUTH)
                    .set(OPENAPI_FIELD, required(test, "target"));
            if (test.has("service_openapi")) {
                ((ObjectNode) document.get("http")).putObject(OPENAPI_FIELD).put("url", text(test, "service_openapi"));
            }
            JsonNode description = required(test, DOCUMENT_FIELD);
            openapi.put(OPENAPI_FIELD, text(description, OPENAPI_FIELD));
            openapi.putObject("info").put("title", "Conformance").put("version", "1");
            ObjectNode paths = openapi.putObject("paths");
            int index = 0;
            for (JsonNode id : required(description, "operation_ids")) {
                ObjectNode operation = paths.putObject("/operation-" + index).putObject("post");
                index++;
                operation.put("operationId", textValue(id));
                operation.putObject("responses").putObject("200").put(DESCRIPTION_FIELD, "Success");
            }
        } else {
            return skipped();
        }
        OdpServiceClient client = OdpServiceClient.create(
                URI.create(SERVICE_ORIGIN),
                request -> response(
                        request,
                        Odp.SERVICE_DOCUMENT_PATH.equals(request.uri().getPath())
                                ? document.toString()
                                : offering.toString(),
                        ODP_MEDIA_TYPE,
                        200),
                request -> response(request, openapi.toString(), "application/json", 200));
        if (VALIDATE_ACTIONS.equals(operation(test))) {
            OfferingDetails details = client.getOfferingDetails(ITEM_ID, null);
            List<String> usable =
                    details.actions().stream().map(action -> action.id()).toList();
            List<String> issues = details.issues().stream()
                    .filter(issue -> issue.scope() == OfferingIssue.Scope.ACTION)
                    .map(OfferingIssue::actionId)
                    .toList();
            return result(JSON.valueToTree(usable).equals(required(test, "expected_usable"))
                    && new java.util.HashSet<>(issues)
                            .equals(JSON.convertValue(
                                    required(test, "expected_issues"),
                                    new tools.jackson.core.type.TypeReference<Set<String>>() {})));
        }
        boolean resolved;
        try {
            client.resolveAction(ITEM_ID, "invoke", null);
            resolved = true;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            resolved = false;
        }
        return result(resolved == valid(test));
    }

    private static OdpService serving(Object value) {
        Map<OdpOperation, OdpService.Endpoint> endpoints = new EnumMap<>(OdpOperation.class);
        for (OdpOperation operation : OdpOperation.values()) {
            endpoints.put(operation, new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, request -> value));
        }
        return new OdpService(baselineDocument(), endpoints);
    }

    private static OdpServiceClient reading(String value, String mediaType) {
        return OdpServiceClient.create(
                URI.create(SERVICE_ORIGIN),
                request -> response(
                        request,
                        Odp.SERVICE_DOCUMENT_PATH.equals(request.uri().getPath())
                                ? OdpJson.write(baselineDocument())
                                : value,
                        Odp.SERVICE_DOCUMENT_PATH.equals(request.uri().getPath()) ? ODP_MEDIA_TYPE : mediaType,
                        200));
    }

    private static Evaluation evaluateSelection(JsonNode test, String role) {
        if (AGENT_ROLE.equals(role)) {
            return evaluateAgentSelection(test);
        }
        AtomicInteger calls = new AtomicInteger();
        List<String> selections = new ArrayList<>();
        Map<OdpOperation, OdpService.Endpoint> endpoints = new EnumMap<>(OdpOperation.class);
        for (OdpOperation operation : OdpOperation.values()) {
            endpoints.put(operation, new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, request -> {
                calls.incrementAndGet();
                selections.add(request.representation());
                return request.identifier() == null || operation == OdpOperation.LIST_COLLECTION_OFFERINGS
                        ? new Page<>(null, Odp.VERSION, List.of(), null, Map.of())
                        : OdpJson.parseTree("{\"odp_version\":\"1.0\",\"id\":\"item\",\"name\":\"Item\"}");
            }));
        }
        String path =
                switch (operation(test)) {
                    case "get-offering" -> OFFERING_PATH;
                    case "get-collection" -> "/odp/collections/item";
                    case "list-collections" -> "/odp/collections";
                    case "list-collection-offerings" -> "/odp/collections/item/offerings";
                    case "search-collections" -> "/odp/collections/search";
                    case SEARCH_OFFERINGS_OPERATION -> "/odp/offerings/search";
                    case "list-offerings" -> OFFERINGS_PATH;
                    default -> throw new IllegalArgumentException("Unknown representation operation");
                };
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (test.has(REPRESENTATION_FIELD)) {
            List<String> values = new ArrayList<>();
            test.get(REPRESENTATION_FIELD).forEach(value -> values.add(textValue(value)));
            query.put(REPRESENTATION_FIELD, values);
        }
        var response = new OdpService(baselineDocument(), endpoints)
                .handle(new OdpHttpRequest(GET_METHOD, path, query, Map.of(), null));
        String expected = text(test, EXPECTED_FIELD);
        return result(
                "400".equals(expected)
                        ? response.status() == 400 && calls.get() == 0
                        : response.status() == 200 && selections.equals(List.of(expected)));
    }

    private static Evaluation evaluateAgentSelection(JsonNode test) {
        JsonNode representations = test.get(REPRESENTATION_FIELD);
        if (representations != null && representations.size() > 1)
            return new Evaluation(
                    SKIPPED_STATUS,
                    "Repeated query parameters are Service input; Agent APIs accept one representation");
        String representation = representations == null ? null : textValue(representations.get(0));
        List<String> queries = new ArrayList<>();
        ServiceDocument advertised = document(java.util.Arrays.stream(OdpOperation.values())
                .map(operation -> new OperationDescriptor(AuthenticationRequirement.NOT_REQUIRED, operation))
                .toList());
        OdpServiceClient client = OdpServiceClient.create(URI.create(SERVICE_ORIGIN), request -> {
            if (Odp.SERVICE_DOCUMENT_PATH.equals(request.uri().getPath()))
                return response(request, OdpJson.write(advertised), ODP_MEDIA_TYPE, 200);
            queries.add(request.uri().getRawQuery());
            String body = operation(test).startsWith("get-")
                    ? "{\"odp_version\":\"1.0\",\"id\":\"item\",\"name\":\"Item\"}"
                    : "{\"odp_version\":\"1.0\",\"items\":[]}";
            return response(request, body, ODP_MEDIA_TYPE, 200);
        });
        String expected = text(test, EXPECTED_FIELD);
        try {
            switch (operation(test)) {
                case "get-offering" -> client.getOffering(ITEM_ID, representation, null);
                case "get-collection" -> client.getCollection(ITEM_ID, representation, null);
                case "list-offerings" -> client.listOfferings(representation, null, null);
                case "list-collections" -> client.listCollections(representation, null, null);
                case "list-collection-offerings" -> client.listCollectionOfferings(ITEM_ID, representation, null, null);
                case "search-collections" ->
                    client.searchCollections(
                            OdpJson.parseCollectionSearchRequest("{\"odp_version\":\"1.0\",\"query\":\"example\"}"),
                            representation,
                            null);
                case SEARCH_OFFERINGS_OPERATION ->
                    client.searchOfferings(
                            OdpJson.parseOfferingSearchRequest("{\"odp_version\":\"1.0\",\"query\":\"example\"}"),
                            representation,
                            null);
                default -> throw new IllegalArgumentException("Unknown representation operation");
            }
        } catch (IllegalArgumentException exception) {
            return result("400".equals(expected) && queries.isEmpty());
        }
        return result(!"400".equals(expected) && queries.equals(List.of("representation=" + expected)));
    }

    private static Evaluation evaluateDetailFields(JsonNode test, String role) {
        String name = text(test, "name");
        if (!"detail-fields-prohibited-in-full".equals(name))
            return new Evaluation(
                    SKIPPED_STATUS,
                    "Only full-response prohibition is enforced; optional detail hint validation and paired-response completeness are not provided");
        JsonNode body = required(test, FULL_REPRESENTATION);
        boolean accepted;
        if (AGENT_ROLE.equals(role)) {
            try {
                OdpServiceClient client = reading(body.toString(), ODP_MEDIA_TYPE);
                client.getOffering(ITEM_ID, FULL_REPRESENTATION, null);
                accepted = true;
            } catch (IllegalArgumentException exception) {
                accepted = false;
            }
        } else {
            var response = serving(OdpJson.parseTree(body.toString()))
                    .handle(new OdpHttpRequest(
                            GET_METHOD,
                            OFFERING_PATH,
                            Map.of(REPRESENTATION_FIELD, List.of(FULL_REPRESENTATION)),
                            Map.of(),
                            null));
            accepted = response.status() == 200;
            if (!accepted && response.status() != 500) return result(false);
        }
        return result(accepted == valid(test));
    }

    private static Evaluation evaluateVersionPlacement(JsonNode test, String role) {
        ObjectNode item = JSON.createObjectNode().put("id", ITEM_ID).put(NAME_FIELD, ITEM_NAME);
        if (required(test, "odp_version_present").asBoolean()) item.put(VERSION_FIELD, Odp.VERSION);
        boolean topLevel = required(test, "top_level").asBoolean();
        ObjectNode payload = item;
        if (!topLevel || "search-response".equals(text(test, NAME_FIELD))) {
            payload = JSON.createObjectNode().put(VERSION_FIELD, Odp.VERSION);
            payload.putArray(ITEMS_FIELD).add(item);
            if (topLevel) payload.putArray(ITEMS_FIELD);
        }
        boolean page = payload.has(ITEMS_FIELD);
        boolean accepted;
        if (AGENT_ROLE.equals(role)) {
            try {
                var client = reading(payload.toString(), ODP_MEDIA_TYPE);
                if (page) client.listOfferings(TERSE_REPRESENTATION, null, null);
                else client.getOffering(ITEM_ID, FULL_REPRESENTATION, null);
                accepted = true;
            } catch (IllegalArgumentException | IllegalStateException exception) {
                accepted = false;
            }
        } else {
            accepted = serving(OdpJson.parseTree(payload.toString()))
                            .handle(new OdpHttpRequest(
                                    GET_METHOD, page ? OFFERINGS_PATH : OFFERING_PATH, Map.of(), Map.of(), null))
                            .status()
                    == 200;
        }
        return result(accepted == valid(test));
    }

    private static Evaluation evaluateMedia(JsonNode test, String role) {
        if (test.has(AGENT_ROLE)) {
            if (!AGENT_ROLE.equals(role)) return new Evaluation(SKIPPED_STATUS, "Agent response-consumption case");
            String mediaType = text(test, "response_content_type_essence");
            if (test.has("parameters")) {
                for (var entry : test.get("parameters").properties()) {
                    mediaType += "; " + entry.getKey() + "=" + textValue(entry.getValue());
                }
            }
            ObjectNode payload = JSON.createObjectNode()
                    .put(VERSION_FIELD, test.has("body_odp_version") ? text(test, "body_odp_version") : Odp.VERSION);
            payload.put("id", ITEM_ID).put(NAME_FIELD, ITEM_NAME);
            try {
                Offering offering =
                        reading(payload.toString(), mediaType).getOffering(ITEM_ID, FULL_REPRESENTATION, null);
                return result("process".equals(text(test, AGENT_ROLE))
                        && (!test.has("effective_odp_version")
                                || text(test, "effective_odp_version").equals(offering.odpVersion())));
            } catch (IllegalArgumentException | IllegalStateException exception) {
                return result("reject".equals(text(test, AGENT_ROLE)));
            }
        }
        if (AGENT_ROLE.equals(role)) return new Evaluation(SKIPPED_STATUS, "Service request-negotiation case");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        boolean post = test.has("request_content_type_essence") || test.has("request_content_type_missing");
        if (test.has("request_content_type_essence"))
            headers.put("Content-Type", List.of(text(test, "request_content_type_essence")));
        if (!post && !test.path("accept_missing").asBoolean()) {
            headers.put(
                    "Accept",
                    List.of(
                            test.path("accepts_odp").asBoolean()
                                    ? ODP_MEDIA_TYPE
                                    : test.path("accepts_wildcard").asBoolean() ? "*/*" : "text/html"));
        }
        var response = serving(new Page<>(null, Odp.VERSION, List.of(), null, Map.of()))
                .handle(new OdpHttpRequest(
                        post ? "POST" : GET_METHOD,
                        post ? "/odp/offerings/search" : OFFERINGS_PATH,
                        Map.of(),
                        headers,
                        post ? "{\"odp_version\":\"1.0\",\"query\":\"item\"}" : null));
        String expected = text(test, "response");
        return result(response.status()
                == (Set.of("process", "serve-odp").contains(expected) ? 200 : Integer.parseInt(expected)));
    }

    private static Evaluation evaluateComposition(JsonNode test, String role) {
        if (NORMALIZE_AGENT_RESPONSE.equals(operation(test)) && AGENT_ROLE.equals(role)) {
            try {
                JsonNode actual = JSON.readTree(OdpJson.normalizeAgentResponse(
                        required(test, DOCUMENT_FIELD).toString(),
                        required(test, "kind").asString()));
                validateAgentResponse(actual.toString(), required(test, "kind").asString());
                return result(actual.equals(required(test, EXPECTED_FIELD)));
            } catch (JacksonException exception) {
                throw new IllegalArgumentException("Unable to decode normalized Agent response", exception);
            }
        }
        if (!VALIDATE_ADVERTISEMENT.equals(operation(test))
                && (!FILTER_ADVERTISEMENT.equals(operation(test)) || !AGENT_ROLE.equals(role))) {
            return skipped();
        }
        String document = """
                {"description":"ODP Java conformance adapter","http":{"endpoint_base":"/odp"},
                "language":"en","localizations":["en"],"name":"Conformance Service",
                "odp_version":"1.0","operations":[
                {"authentication":"not-required","name":"get-offering"},
                {"authentication":"not-required","name":"list-offerings"}],"protocols":
                """ + required(test, "protocols") + "}";
        if (VALIDATE_ADVERTISEMENT.equals(operation(test))) {
            return parseValue(document, OdpJson::parseServiceDocument, valid(test));
        }
        try {
            ServiceDocument parsed = OdpJson.parseAgentServiceDocument(document);
            JsonNode actual =
                    parsed.protocols() == null ? JSON.createObjectNode() : JSON.valueToTree(parsed.protocols());
            removeNullProperties(actual);
            return result(actual.equals(required(test, EXPECTED_FIELD)));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to encode Agent protocol projection", exception);
        }
    }

    private static void validateAgentResponse(String document, String kind) {
        switch (kind) {
            case "service-document" -> OdpJson.parseAgentServiceDocument(document);
            case "collection" -> OdpJson.parseCollection(document);
            case "offering" -> OdpJson.parseOffering(document);
            case "collection-page" -> OdpJson.parsePage(document, org.offeringprotocol.odp.core.Collection.class);
            case "offering-page" -> OdpJson.parsePage(document, Offering.class);
            case "filter-page" -> OdpJson.parsePage(document, FilterDefinition.class);
            case "sort-page" -> OdpJson.parsePage(document, SortDefinition.class);
            case "problem" -> OdpJson.parseProblemDetails(document);
            default -> throw new IllegalArgumentException("Unknown Agent response kind");
        }
    }

    private static void removeNullProperties(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> nullProperties = new ArrayList<>();
            object.properties().forEach(entry -> {
                if (entry.getValue().isNull()) {
                    nullProperties.add(entry.getKey());
                } else {
                    removeNullProperties(entry.getValue());
                }
            });
            nullProperties.forEach(object::remove);
            return;
        }
        node.forEach(ConformanceAdapter::removeNullProperties);
    }

    private static Evaluation evaluateAttributeSchema(JsonNode test) {
        return switch (operation(test)) {
            case "validate-reference" -> {
                String offering = ("{\"id\":\"item\",\"name\":\"Item\",\"odp_version\":\"1.0\"," + "\"schema\":%s}")
                        .formatted(required(test, "reference"));
                yield parseValue(offering, OdpJson::parseOffering, valid(test));
            }
            case "validate-response" -> {
                var details = attributeSchemaDetails(
                        Map.of(
                                ROOT_SCHEMA_URL,
                                new SchemaResponse(
                                        required(test, DOCUMENT_FIELD).toString(),
                                        text(test, "content_type"),
                                        required(test, STATUS_FIELD).asInt())),
                        ROOT_SCHEMA_URL,
                        "{\"name\":\"root\"}",
                        false);
                yield result((details.details().attributeSchema() != null) == valid(test));
            }
            case "validate-schema-reference-profile" -> {
                Map<String, SchemaResponse> documents = new LinkedHashMap<>();
                String rootUrl = null;
                int index = 0;
                for (JsonNode document : required(test, "documents")) {
                    String url = document.has("$id")
                            ? text(document, "$id")
                            : "https://schemas.example/document-" + index + ".json";
                    if (rootUrl == null) {
                        rootUrl = url;
                    }
                    documents.put(url, new SchemaResponse(document.toString(), SCHEMA_MEDIA_TYPE, 200));
                    index++;
                }
                var details = attributeSchemaDetails(
                        documents, rootUrl, "{\"children\":[{\"name\":\"child\"}],\"name\":\"root\"}", false);
                yield result((details.details().attributeSchema() != null) == valid(test));
            }
            case "validation-scope" -> {
                boolean terse = TERSE_REPRESENTATION.equals(text(test, REPRESENTATION_FIELD));
                var details = attributeSchemaDetails(
                        Map.of(ROOT_SCHEMA_URL, new SchemaResponse("""
                                        {"$schema":"https://json-schema.org/draft/2020-12/schema",
                                        "properties":{"memory":{"type":"number"}},"type":"object"}
                                        """, SCHEMA_MEDIA_TYPE, 200)),
                        ROOT_SCHEMA_URL,
                        "{\"memory\":\"invalid\"}",
                        terse);
                boolean complete = details.supportingRequests() > 0
                        && details.details().offering().attributes() == null
                        && details.details().issues().stream()
                                .anyMatch(issue -> issue.scope() == OfferingIssue.Scope.ATTRIBUTES);
                yield result(complete
                        == required(test, "complete_instance_validation").asBoolean());
            }
            case "failure-scope" -> {
                var details = attributeSchemaDetails(
                        Map.of(
                                ROOT_SCHEMA_URL,
                                new SchemaResponse("{\"title\":\"Unavailable\"}", "application/problem+json", 503)),
                        ROOT_SCHEMA_URL,
                        "{\"name\":\"root\"}",
                        false);
                Map<String, Boolean> actual = Map.of(
                        "offering_usable",
                                ITEM_ID.equals(details.details().offering().id()),
                        "attributes_usable", details.details().offering().attributes() != null,
                        "report_issue",
                                details.details().issues().stream()
                                        .anyMatch(issue -> issue.scope() == OfferingIssue.Scope.ATTRIBUTE_SCHEMA));
                Map<String, Boolean> expected = new LinkedHashMap<>();
                required(test, EXPECTED_FIELD)
                        .properties()
                        .forEach(entry ->
                                expected.put(entry.getKey(), entry.getValue().asBoolean()));
                yield result(actual.equals(expected));
            }
            default -> skipped();
        };
    }

    private static AttributeSchemaEvaluation attributeSchemaDetails(
            Map<String, SchemaResponse> documents, String rootUrl, String attributes, boolean terse) {
        String serviceDocument = """
                {"description":"ODP Java conformance adapter","http":{"endpoint_base":"/odp"},
                "language":"en","localizations":["en"],"name":"Conformance Service",
                "odp_version":"1.0","operations":[{"authentication":"not-required","name":"get-offering"},
                {"authentication":"not-required","name":"list-offerings"}]}
                """;
        String offering = ("{\"attributes\":%s,\"id\":\"item\",\"name\":\"Item\",\"odp_version\":\"1.0\","
                        + "\"schema\":{\"url\":\"%s\"}}")
                .formatted(attributes, rootUrl);
        OdpTransport service = request -> response(
                request,
                "/.well-known/odp".equals(request.uri().getPath())
                        ? serviceDocument
                        : terse ? "{\"id\":\"item\",\"name\":\"Item\",\"odp_version\":\"1.0\"}" : offering,
                ODP_MEDIA_TYPE,
                200);
        AtomicInteger supportingRequests = new AtomicInteger();
        OdpTransport supporting = request -> {
            supportingRequests.incrementAndGet();
            SchemaResponse document = documents.getOrDefault(
                    request.uri().toString(),
                    new SchemaResponse("{\"title\":\"Not Found\"}", "application/problem+json", 404));
            return response(request, document.body(), document.contentType(), document.status());
        };
        OdpServiceClient client = OdpServiceClient.create(URI.create(SERVICE_ORIGIN), service, supporting);
        OfferingDetails details = terse
                ? new OfferingDetails(
                        client.getOffering(ITEM_ID, TERSE_REPRESENTATION, null), null, List.of(), List.of())
                : client.getOfferingDetails(ITEM_ID, null);
        return new AttributeSchemaEvaluation(details, supportingRequests.get());
    }

    private static HttpResponse<byte[]> response(HttpRequest request, String body, String contentType, int status) {
        return response(request, body, Map.of("Content-Type", List.of(contentType)), status);
    }

    private static HttpResponse<byte[]> response(
            HttpRequest request, String body, Map<String, List<String>> headers, int status) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return request;
            }

            @Override
            public Optional<HttpResponse<byte[]>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(headers, (name, value) -> true);
            }

            @Override
            public byte[] body() {
                return body.getBytes(StandardCharsets.UTF_8);
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
        };
    }

    private static Evaluation parseValue(String value, Parser parser, boolean expected) {
        boolean actual;
        try {
            parser.parse(value);
            actual = true;
        } catch (IllegalArgumentException exception) {
            actual = false;
        }
        return result(actual == expected);
    }

    private record AttributeSchemaEvaluation(OfferingDetails details, int supportingRequests) {}

    private record SchemaResponse(String body, String contentType, int status) {}

    private static Evaluation evaluateIdentity(JsonNode test) {
        ResourceIdentity left = decode(required(test, "left"), ResourceIdentity.class);
        ResourceIdentity right = decode(required(test, "right"), ResourceIdentity.class);
        return result(left.equals(right) == required(test, "same_identity").asBoolean());
    }

    private static Evaluation evaluateServiceOrigin(JsonNode test) {
        boolean actual;
        String value = text(test, "value");
        try {
            actual = OdpUris.deriveServiceOrigin(URI.create(value)).equals(value);
        } catch (IllegalArgumentException exception) {
            actual = false;
        }
        return result(actual == valid(test));
    }

    private static Evaluation evaluateReference(JsonNode test) {
        boolean actual;
        try {
            OdpUris.resolveResourceReference(text(test, "value"), SERVICE_ORIGIN);
            actual = true;
        } catch (IllegalArgumentException exception) {
            actual = false;
        }
        return result(actual == valid(test));
    }

    private static Evaluation evaluatePagination(JsonNode test, String role) {
        return switch (operation(test)) {
            case "conditional-get" -> {
                if (AGENT_ROLE.equals(role))
                    yield new Evaluation(SKIPPED_STATUS, "Agent has no conditional-response cache API");
                OdpService service = serving(new Page<>(null, Odp.VERSION, List.of(), null, Map.of()));
                var first = service.handle(new OdpHttpRequest(GET_METHOD, OFFERINGS_PATH, Map.of(), Map.of(), null));
                String etag = first.headers().get("ETag");
                var conditional = service.handle(new OdpHttpRequest(
                        GET_METHOD, OFFERINGS_PATH, Map.of(), Map.of("If-None-Match", List.of(etag)), null));
                yield result(
                        conditional.status() == required(test, STATUS_FIELD).asInt()
                                && conditional.body().isEmpty());
            }
            case "validate-page" -> parse(test, "page", value -> OdpJson.parsePage(value, JsonNode.class));
            case "validate-limit" -> {
                int limit = required(test, "limit").asInt();
                boolean accepted;
                if (AGENT_ROLE.equals(role)) {
                    try {
                        reading("{\"odp_version\":\"1.0\",\"items\":[]}", ODP_MEDIA_TYPE)
                                .listOfferings(TERSE_REPRESENTATION, limit, null);
                        accepted = true;
                    } catch (IllegalArgumentException exception) {
                        accepted = false;
                    }
                } else {
                    accepted = serving(new Page<>(null, Odp.VERSION, List.of(), null, Map.of()))
                                    .handle(new OdpHttpRequest(
                                            GET_METHOD,
                                            OFFERINGS_PATH,
                                            Map.of("limit", List.of(Integer.toString(limit))),
                                            Map.of(),
                                            null))
                                    .status()
                            == 200;
                }
                yield result(accepted == valid(test));
            }
            case "validate-next" -> {
                boolean actual;
                try {
                    OdpUris.resolveContinuation(text(test, "next"), text(test, "service_origin"));
                    actual = true;
                } catch (IllegalArgumentException exception) {
                    actual = false;
                }
                yield result(actual == valid(test));
            }
            default -> skipped();
        };
    }

    private static Evaluation evaluateRetry(JsonNode test) {
        int attempt = required(test, "attempt").asInt();
        int status = required(test, STATUS_FIELD).asInt();
        String delay = required(test, "retry_after_seconds").asText();
        AtomicInteger calls = new AtomicInteger();
        OdpServiceClient client = OdpServiceClient.create(URI.create(SERVICE_ORIGIN), request -> {
            if (Odp.SERVICE_DOCUMENT_PATH.equals(request.uri().getPath())) {
                return response(request, OdpJson.write(baselineDocument()), ODP_MEDIA_TYPE, 200);
            }
            if (calls.incrementAndGet() <= attempt) {
                return response(request, "{}", Map.of("Retry-After", List.of(delay)), status);
            }
            return response(request, "{\"odp_version\":\"1.0\",\"items\":[]}", ODP_MEDIA_TYPE, 200);
        });
        try {
            client.listOfferings(TERSE_REPRESENTATION, null, null);
        } catch (OdpRequestException exception) {
            if (exception.status() != status) {
                return result(false);
            }
        }
        return result((calls.get() > attempt) == required(test, RETRY_OPERATION).asBoolean());
    }

    private static Evaluation evaluateErrorsAndLimits(JsonNode test, String role) {
        if (LIMIT_FAILURE_SCOPE.equals(operation(test))) {
            if (!AGENT_ROLE.equals(role)) {
                return new Evaluation(SKIPPED_STATUS, "Item iteration is Agent behavior, not Service behavior");
            }
            int count = required(test, "prior_items").asInt();
            AtomicInteger requests = new AtomicInteger();
            var iterator = OdpPagination.iterate(
                    () -> new Page<>(
                            null,
                            Odp.VERSION,
                            java.util.stream.IntStream.range(0, count).boxed().toList(),
                            "/next",
                            Map.of()),
                    next -> {
                        requests.incrementAndGet();
                        reading(" ".repeat(524_289), ODP_MEDIA_TYPE).listOfferings(TERSE_REPRESENTATION, null, null);
                        return new Page<>(null, Odp.VERSION, List.<Integer>of(), null, Map.of());
                    },
                    Long.MAX_VALUE);
            List<Integer> delivered = new ArrayList<>();
            try {
                iterator.forEachRemaining(delivered::add);
                return result(false);
            } catch (org.offeringprotocol.odp.core.OdpResponseLimitException exception) {
                try {
                    iterator.hasNext();
                    return result(false);
                } catch (org.offeringprotocol.odp.core.OdpResponseLimitException repeated) {
                    return result(exception.equals(repeated)
                            && "RESPONSE_LIMIT_EXCEEDED".equals(exception.code())
                            && !exception.retryable()
                            && requests.get() == 1
                            && delivered.size()
                                    == required(required(test, EXPECTED_FIELD), "preserved_items")
                                            .asInt());
                }
            }
        }
        if (RETRY_OPERATION.equals(operation(test))) {
            return AGENT_ROLE.equals(role)
                    ? evaluateRetry(test)
                    : new Evaluation(
                            SKIPPED_STATUS, "Automatic retrieval retry is Agent behavior, not Service behavior");
        }
        if (VALIDATE_PROBLEM.equals(operation(test))) {
            boolean actual;
            try {
                var problem =
                        OdpJson.parseProblemDetails(required(test, "problem").toString());
                actual = problem.status() == required(test, "http_status").asInt();
            } catch (IllegalArgumentException exception) {
                actual = false;
            }
            return result(actual == valid(test));
        }
        if (!"validate-limit".equals(operation(test))) {
            return skipped();
        }
        int bytes = required(test, "bytes").asInt();
        return switch (text(test, "resource")) {
            case REQUEST_FIELD ->
                AGENT_ROLE.equals(role)
                        ? new Evaluation(
                                SKIPPED_STATUS,
                                "Vector checks the receiving Service request-byte limit, not an Agent response")
                        : result((serviceRequestStatus(bytes) == 200) == valid(test));
            case "offering" -> {
                String prefix = "{\"odp_version\":\"1.0\",\"id\":\"item\",\"name\":\"Item\",\"padding\":\"";
                String body = prefix + "x".repeat(bytes - prefix.length() - 2) + "\"}";
                boolean accepted;
                if (AGENT_ROLE.equals(role)) {
                    try {
                        reading(body, ODP_MEDIA_TYPE).getOffering(ITEM_ID, FULL_REPRESENTATION, null);
                        accepted = true;
                    } catch (IllegalArgumentException | IllegalStateException exception) {
                        accepted = false;
                    }
                } else {
                    accepted = serving(OdpJson.parseTree(body))
                                    .handle(new OdpHttpRequest(GET_METHOD, OFFERING_PATH, Map.of(), Map.of(), null))
                                    .status()
                            == 200;
                }
                yield result(accepted == valid(test));
            }
            case "schema_graph" -> {
                if (!AGENT_ROLE.equals(role))
                    yield new Evaluation(SKIPPED_STATUS, "Service does not retrieve Attribute Schema graphs");
                Map<String, SchemaResponse> documents = new LinkedHashMap<>();
                int documentCount = 5;
                for (int index = 0; index < documentCount; index++) {
                    ObjectNode schema = JSON.createObjectNode().put(SCHEMA_DIALECT_FIELD, SCHEMA_DIALECT);
                    if (index + 1 < documentCount)
                        schema.put("$ref", "https://schemas.example/" + (index + 1) + ".json");
                    schema.put(DESCRIPTION_FIELD, "");
                    int budget = bytes / documentCount + (index == 0 ? bytes % documentCount : 0);
                    schema.put(
                            DESCRIPTION_FIELD,
                            "x".repeat(budget - schema.toString().length()));
                    documents.put(
                            "https://schemas.example/" + index + ".json",
                            new SchemaResponse(schema.toString(), SCHEMA_MEDIA_TYPE, 200));
                }
                var details = attributeSchemaDetails(
                        documents, "https://schemas.example/0.json", "{\"name\":\"root\"}", false);
                yield result((details.details().attributeSchema() != null) == valid(test)
                        && details.supportingRequests() == documentCount);
            }
            default -> throw new IllegalArgumentException("Unmapped resource limit");
        };
    }

    private static int serviceRequestStatus(int byteCount) {
        Map<OdpOperation, OdpService.Endpoint> endpoints = new EnumMap<>(OdpOperation.class);
        endpoints.put(
                OdpOperation.LIST_OFFERINGS,
                new OdpService.Endpoint(
                        AuthenticationRequirement.NOT_REQUIRED,
                        request -> new Page<Offering>(null, Odp.VERSION, List.of(), null, Map.of())));
        endpoints.put(
                OdpOperation.GET_OFFERING,
                new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, request -> null));
        endpoints.put(
                OdpOperation.SEARCH_OFFERINGS,
                new OdpService.Endpoint(
                        AuthenticationRequirement.NOT_REQUIRED,
                        request -> new Page<Offering>(null, Odp.VERSION, List.of(), null, Map.of())));
        OdpService service = new OdpService(document(List.of()), endpoints);
        String prefix = "{\"odp_version\":\"1.0\",\"query\":\"gpu\"}";
        String body = prefix + " ".repeat(Math.max(0, byteCount - prefix.length()));
        return service.handle(new OdpHttpRequest(
                        "POST",
                        "/odp/offerings/search",
                        Map.of(),
                        Map.of("Content-Type", List.of(ODP_MEDIA_TYPE)),
                        body))
                .status();
    }

    private static Evaluation evaluateBaseline(JsonNode test, String role) {
        if (!text(test, "role").equals(role)) {
            return skipped();
        }
        if (AGENT_ROLE.equals(role)) {
            return new Evaluation(
                    SKIPPED_STATUS,
                    "An abstract behavior declaration does not execute the Agent implementation; verify individual behavior vectors");
        }
        List<OperationDescriptor> operations = new ArrayList<>();
        required(test, OPERATIONS_FIELD)
                .forEach(value -> operations.add(new OperationDescriptor(
                        AuthenticationRequirement.NOT_REQUIRED, OdpOperation.fromValue(textValue(value)))));
        boolean actual;
        try {
            OdpJson.parseServiceDocument(OdpJson.write(document(operations)));
            OdpJson.parsePage(required(test, "list_response").toString(), Offering.class);
            OdpJson.parseOffering(required(test, "get_response").toString());
            actual = operations.stream()
                    .map(OperationDescriptor::name)
                    .collect(java.util.stream.Collectors.toSet())
                    .containsAll(Set.of(OdpOperation.LIST_OFFERINGS, OdpOperation.GET_OFFERING));
        } catch (IllegalArgumentException exception) {
            actual = false;
        }
        return result(actual == valid(test));
    }

    private static ServiceDocument document(List<OperationDescriptor> operations) {
        return ServiceDocument.builder(
                        "Conformance Service", "ODP conformance Service", "en", new ServiceDocument.Http("/odp", null))
                .operations(operations)
                .build();
    }

    private static Evaluation parse(JsonNode test, String field, Parser parser) {
        boolean actual;
        try {
            parser.parse(required(test, field).toString());
            actual = true;
        } catch (IllegalArgumentException exception) {
            actual = false;
        }
        return result(actual == valid(test));
    }

    private static <T> T decode(JsonNode value, Class<T> type) {
        try {
            return JSON.readValue(value.toString(), type);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Conformance case has an invalid field", exception);
        }
    }

    private static JsonNode required(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Conformance case omitted " + name);
        }
        return value;
    }

    private static String text(JsonNode object, String name) {
        return textValue(required(object, name));
    }

    private static String optionalText(JsonNode object, String name) {
        JsonNode value = object.get(name);
        return value == null ? null : textValue(value);
    }

    private static String textValue(JsonNode value) {
        String result = value.stringValue();
        if (result == null) {
            throw new IllegalArgumentException("Conformance case field is not a string");
        }
        return result;
    }

    private static String operation(JsonNode test) {
        return optionalText(test, "operation");
    }

    private static boolean valid(JsonNode test) {
        return required(test, "valid").asBoolean();
    }

    private static Evaluation result(boolean matches) {
        return matches
                ? new Evaluation("passed", null)
                : new Evaluation("failed", "Public Java API result did not match the vector");
    }

    private static Evaluation skipped() {
        return new Evaluation(SKIPPED_STATUS, null);
    }

    private static String skipReason(String subject, JsonNode test) {
        return switch (subject) {
            case "collection-search-contract", "offering-search-contract" ->
                "Application-owned search: StaticCatalog does not implement search and custom handlers own matching and ordering";
            case "filter-sort-contract" ->
                "Application handlers evaluate expressions against mapped Offering values; SDK validates expressions, not queries";
            case "invalid-edge-handling" ->
                "Not implemented: Agent Collection graph traversal; individual Collection retrieval does not traverse edges";
            case "detail-fields" ->
                "No paired-representation validator: APIs validate individual responses; custom producers own exhaustive cross-representation projections";
            case "composition-contract" ->
                switch (operation(test)) {
                    case "classify-live-response", "validate-sequence" ->
                        "Application-owned authentication/payment orchestration; SDK exposes HTTP status and headers without executing those protocols";
                    default -> "Vector describes Agent response normalization, not Service publication";
                };
            case "pagination-contract" ->
                switch (operation(test)) {
                    case "validate-sequence" ->
                        "No arbitrary sequence validator: StaticCatalog generates unique snapshot pages; custom handlers own stable sequences";
                    case "validate-lifetime" ->
                        "StaticCatalog has a fixed continuation lifetime, not a configurable arbitrary-lifetime validator";
                    case "validate-storage-model" ->
                        "A storage-model label is not executable SDK behavior; callers follow opaque next references";
                    default -> throw new IllegalArgumentException("Unmapped pagination operation " + operation(test));
                };
            case "errors-limits-contract" ->
                "Adapter coverage missing for resource-specific byte budgets; network and schema tests cover the implementation separately";
            case "security-contract" ->
                switch (operation(test)) {
                    case "validate-destination" ->
                        "Vector requires DNS/peer injection unavailable through the public transport; Apache transport tests exercise the actual connector";
                    case "forwarded-sensitive-fields" ->
                        "Caller transports own credential attachment; SDK has no Action execution or arbitrary sensitive-field forwarding API; SupportingResourceTest checks anonymous supporting requests";
                    case "cache-reusable" ->
                        "Not implemented: HTTP response cache and authentication-context partitioning";
                    case "payment-authorized", "action-invocable" ->
                        "Application-owned execution policy: this SDK resolves Actions but does not execute Actions or authorize payments";
                    default -> throw new IllegalArgumentException("Unmapped security operation " + operation(test));
                };
            case "role-baseline" -> "Vector describes the other protocol role";
            default ->
                throw new IllegalArgumentException(
                        "Unmapped conformance subject or operation: " + subject + "/" + operation(test));
        };
    }

    private static String truncate(String value) {
        String message = value == null ? "Conformance evaluation failed" : value;
        return message.length() <= MAXIMUM_MESSAGE_LENGTH ? message : message.substring(0, MAXIMUM_MESSAGE_LENGTH);
    }

    @FunctionalInterface
    private interface Parser {
        Object parse(String value);
    }

    private record Evaluation(String status, String message) {}
}
