package org.offeringprotocol.odp.agent;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpJsonNode;
import org.offeringprotocol.odp.core.OdpUris;
import org.offeringprotocol.odp.core.SearchCapabilities.FilterDefinition;
import org.offeringprotocol.odp.core.SearchCapabilities.SortDefinition;
import org.offeringprotocol.odp.core.SearchCatalog;

final class SearchCapabilityResolver {
    private static final String FILTERS_KIND = "filters";
    private static final String SORTS_KIND = "sorts";
    private static final int MAXIMUM_PAGES = 16;
    private static final int MAXIMUM_PAGE_ITEMS = 100;
    private static final int MAXIMUM_FILTERS = 1024;
    private static final int MAXIMUM_SORTS = 128;
    private final String origin;
    private final Function<URI, String> loader;
    private final List<SearchCapabilityResult.Issue> issues = new ArrayList<>();
    private final Map<String, OdpJsonNode> filters = new LinkedHashMap<>();
    private final Map<String, OdpJsonNode> sorts = new LinkedHashMap<>();
    private final Map<String, String> sortScopes = new LinkedHashMap<>();
    private final Map<String, Set<String>> publishedIdentifiers =
            Map.of(FILTERS_KIND, new HashSet<>(), SORTS_KIND, new HashSet<>());

    SearchCapabilityResolver(String origin, Function<URI, String> loader) {
        this.origin = origin;
        this.loader = loader;
    }

    SearchCapabilityResult resolve(boolean supported, OdpJsonNode service, OdpJsonNode collection) {
        addScope("service", service, supported);
        addScope("collection", collection, supported);
        List<FilterDefinition> resolvedFilters = filters.values().stream()
                .map(value -> OdpJson.parseFilterDefinition(value.toString()))
                .toList();
        List<SortDefinition> resolvedSorts = new ArrayList<>();
        sorts.forEach((id, value) -> {
            SortDefinition sort = OdpJson.parseSortDefinition(value.toString());
            if (sort.keys().stream().anyMatch(key -> !filters.containsKey(key.filterId()))) {
                issue(sortScopes.get(id), SORTS_KIND, id, "Sort references an unavailable Filter");
            } else {
                resolvedSorts.add(sort);
            }
        });
        return new SearchCapabilityResult(new SearchCatalog(resolvedFilters, resolvedSorts), issues);
    }

    private void addScope(String scope, OdpJsonNode capabilities, boolean supported) {
        if (capabilities == null) return;
        if (!supported || !capabilities.isObject() || capabilities.isEmpty()) {
            issue(
                    scope,
                    "capabilities",
                    null,
                    "Search capabilities require a valid advertisement and search-offerings");
            return;
        }
        addSource(scope, FILTERS_KIND, capabilities.get(FILTERS_KIND), filters, MAXIMUM_FILTERS);
        addSource(scope, SORTS_KIND, capabilities.get(SORTS_KIND), sorts, MAXIMUM_SORTS);
    }

    private void addSource(
            String scope, String kind, OdpJsonNode source, Map<String, OdpJsonNode> target, int maximum) {
        if (source == null) return;
        try {
            boolean inline = source.has("inline");
            if (!source.isObject() || inline == source.has("linked")) {
                throw new IllegalArgumentException("A capability source requires exactly one of inline or linked");
            }
            List<OdpJsonNode> definitions;
            if (inline) {
                OdpJsonNode items = source.get("inline");
                int limit = FILTERS_KIND.equals(kind) ? 32 : 16;
                if (!items.isArray() || items.isEmpty() || items.size() > limit) {
                    throw new IllegalArgumentException("Invalid inline capability source size");
                }
                definitions = new ArrayList<>();
                items.forEach(definitions::add);
            } else {
                OdpJsonNode href = source.at("/linked/href");
                if (!href.isString()) throw new IllegalArgumentException("A linked source requires href");
                definitions = load(href.asString(), kind, maximum - target.size());
            }
            Map<String, OdpJsonNode> accepted = new LinkedHashMap<>();
            Set<String> identifiers = new HashSet<>();
            List<String> unsupported = new ArrayList<>();
            for (OdpJsonNode definition : definitions) {
                OdpJsonNode id = definition.path("id");
                if (!id.isString() || !identifiers.add(id.asString())) {
                    throw new IllegalArgumentException("Invalid or duplicate capability identifier within source");
                }
                if (!supportedDefinition(definition, kind)) {
                    unsupported.add(id.asString());
                    continue;
                }
                if (FILTERS_KIND.equals(kind)) OdpJson.parseFilterDefinition(definition.toString());
                else OdpJson.parseSortDefinition(definition.toString());
                accepted.put(id.asString(), definition);
            }
            Set<String> conflicts = new HashSet<>(identifiers);
            conflicts.retainAll(publishedIdentifiers.get(kind));
            long additions = accepted.keySet().stream()
                    .filter(id -> !conflicts.contains(id))
                    .count();
            long removals = conflicts.stream().filter(target::containsKey).count();
            if (target.size() - removals + additions > maximum) {
                throw new IllegalArgumentException("Effective capability catalog exceeds its limit");
            }
            publishedIdentifiers.get(kind).addAll(identifiers);
            for (String id : conflicts) {
                target.remove(id);
                issue(scope, kind, id, "Duplicate capability identifier across sources");
            }
            accepted.forEach((id, definition) -> {
                if (!conflicts.contains(id)) {
                    target.put(id, definition);
                    if (SORTS_KIND.equals(kind)) sortScopes.put(id, scope);
                }
            });
            unsupported.forEach(id -> issue(scope, kind, id, "Unsupported capability definition"));
        } catch (OdpRequestException exception) {
            issues.add(new SearchCapabilityResult.Issue(
                    scope, kind, null, exception.getMessage(), SearchCapabilityResult.HttpFailure.from(exception)));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            if (Thread.currentThread().isInterrupted())
                throw new IllegalStateException("Capability resolution interrupted", exception);
            issue(scope, kind, null, exception.getMessage());
        }
    }

    private static boolean supportedDefinition(OdpJsonNode definition, String kind) {
        String normalized = OdpJson.normalizeAgentResponse(
                "{\"odp_version\":\"1.0\",\"items\":[" + definition + "]}",
                FILTERS_KIND.equals(kind) ? "filter-page" : "sort-page");
        return !OdpJson.parseTree(normalized).path("items").isEmpty();
    }

    private List<OdpJsonNode> load(String href, String kind, int budget) {
        List<OdpJsonNode> definitions = new ArrayList<>();
        int supported = 0;
        Set<URI> visited = new HashSet<>();
        URI next = OdpUris.resolveContinuation(href, origin);
        for (int pageNumber = 0; pageNumber < MAXIMUM_PAGES; pageNumber++) {
            if (!visited.add(next)) throw new IllegalArgumentException("Capability pagination loop");
            String json = loader.apply(next);
            var page = OdpJson.parsePage(json, OdpJsonNode.class);
            if (page.items().size() > MAXIMUM_PAGE_ITEMS) {
                throw new IllegalArgumentException("Linked capability source exceeds its definition limit");
            }
            for (OdpJsonNode definition : page.items()) {
                if (definition.has("odp_version"))
                    throw new IllegalArgumentException("Nested definition contains odp_version");
                if (supportedDefinition(definition, kind)) supported++;
                if (supported > budget) {
                    throw new IllegalArgumentException("Linked capability source exceeds its definition limit");
                }
                definitions.add(definition);
            }
            if (page.next() == null) return definitions;
            next = OdpUris.resolveContinuation(page.next(), origin);
        }
        throw new IllegalArgumentException("Linked capability source exceeds 16 pages");
    }

    private void issue(String scope, String kind, String identifier, String message) {
        issues.add(new SearchCapabilityResult.Issue(scope, kind, identifier, message));
    }
}
