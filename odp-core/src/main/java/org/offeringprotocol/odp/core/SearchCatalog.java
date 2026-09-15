package org.offeringprotocol.odp.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.offeringprotocol.odp.core.SearchCapabilities.FilterDefinition;
import org.offeringprotocol.odp.core.SearchCapabilities.SortDefinition;

/** Validated definitions for one Service and optional selected Collection search scope. */
public final class SearchCatalog {
    private static final String EXISTS_OPERATOR = "exists";
    private static final String IN_OPERATOR = "in";
    private static final String UTC_OFFSET = "Z";
    private static final int MAXIMUM_FILTERS = 1024;
    private static final int MAXIMUM_SORTS = 128;
    private static final Pattern DECIMAL = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern INSTANT = Pattern.compile(
            "([0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:)([0-9]{2})(\\.[0-9]+)?([Zz]|[+-][0-9]{2}:[0-9]{2})");
    private final Map<String, FilterDefinition> catalogFilters;
    private final Map<String, ResolvedSort> catalogSorts;

    public SearchCatalog(List<FilterDefinition> filterDefinitions, List<SortDefinition> sortDefinitions) {
        if (filterDefinitions.size() > MAXIMUM_FILTERS || sortDefinitions.size() > MAXIMUM_SORTS) {
            throw new IllegalArgumentException("Search capability catalog exceeds its limit");
        }
        Map<String, FilterDefinition> filterMap = new LinkedHashMap<>();
        for (FilterDefinition candidate : filterDefinitions) {
            FilterDefinition definition = OdpJson.parseFilterDefinition(OdpJson.write(candidate));
            if (filterMap.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate Filter identifier " + definition.id());
            }
        }
        Map<String, ResolvedSort> sortMap = new LinkedHashMap<>();
        for (SortDefinition candidate : sortDefinitions) {
            SortDefinition definition = OdpJson.parseSortDefinition(OdpJson.write(candidate));
            List<FilterDefinition> resolved = definition.keys().stream()
                    .map(key -> {
                        FilterDefinition filter = filterMap.get(key.filterId());
                        if (filter == null) throw new IllegalArgumentException("Sort references an unavailable Filter");
                        return filter;
                    })
                    .toList();
            if (sortMap.putIfAbsent(definition.id(), new ResolvedSort(definition, resolved)) != null) {
                throw new IllegalArgumentException("Duplicate Sort identifier " + definition.id());
            }
        }
        catalogFilters = Collections.unmodifiableMap(filterMap);
        catalogSorts = Collections.unmodifiableMap(sortMap);
    }

    public Map<String, FilterDefinition> filters() {
        return catalogFilters;
    }

    public Map<String, ResolvedSort> sorts() {
        return catalogSorts;
    }

    public static void validateAdvertisement(SearchCapabilities capabilities, boolean searchSupported, String origin) {
        if (capabilities == null) return;
        if (!searchSupported) throw new IllegalArgumentException("Search capabilities require search-offerings");
        OdpJson.parseSearchCapabilities(OdpJson.write(capabilities));
        List<SearchCapabilities.Link> links = new java.util.ArrayList<>();
        if (capabilities.filters() != null && capabilities.filters().linked() != null)
            links.add(capabilities.filters().linked());
        if (capabilities.sorts() != null && capabilities.sorts().linked() != null)
            links.add(capabilities.sorts().linked());
        for (SearchCapabilities.Link link : links) {
            if (origin != null) OdpUris.resolveContinuation(link.href(), origin);
            else if (!link.href().startsWith("/") || link.href().startsWith("//")) {
                throw new IllegalArgumentException("Absolute capability links require a trusted Service origin");
            }
        }
    }

    public void validateRequest(SearchRequests.Offerings request) {
        OdpJson.parseOfferingSearchRequest(OdpJson.write(request));
        if (request.filters() != null) {
            for (SearchCapabilities.FilterExpression expression : request.filters()) {
                FilterDefinition filter = requireFilter(expression.id());
                if (!filter.operators().contains(expression.operator())) {
                    throw new IllegalArgumentException("Filter operator is not advertised");
                }
                if (EXISTS_OPERATOR.equals(expression.operator())) {
                    scalarKey("boolean", expression.value());
                } else if (IN_OPERATOR.equals(expression.operator())) {
                    if (!expression.value().isArray())
                        throw new IllegalArgumentException("The in operator requires an array");
                    Set<Object> values = new HashSet<>();
                    for (OdpJsonNode value : expression.value()) {
                        if (!values.add(scalarKey(filter.type(), value))) {
                            throw new IllegalArgumentException("Filter values must be unique");
                        }
                    }
                } else {
                    scalarKey(filter.type(), expression.value());
                }
            }
        }
        if (request.sort() != null && !catalogSorts.containsKey(request.sort())) {
            throw new IllegalArgumentException("Sort is unavailable");
        }
        if (request.refinements() != null) {
            for (String id : request.refinements()) {
                if (!Boolean.TRUE.equals(requireFilter(id).refinable())) {
                    throw new IllegalArgumentException("Filter is not refinable");
                }
            }
        }
    }

    public List<ResolvedRefinement> validateRefinements(
            OfferingPage page, SearchRequests.Offerings request, boolean continuation) {
        validateRefinementContext(page, request, continuation);
        if (page.refinements() == null) return List.of();
        OdpJson.parseOfferingSearchResponse(
                OdpJson.write(new OfferingPage(null, Odp.VERSION, List.of(), null, page.refinements(), Map.of())));
        return page.refinements().stream()
                .map(group -> {
                    FilterDefinition filter = requireFilter(group.filterId());
                    if (!Boolean.TRUE.equals(filter.refinable())) {
                        throw new IllegalArgumentException("Filter is not refinable");
                    }
                    Set<Object> values = new HashSet<>();
                    for (OfferingPage.RefinementBucket bucket : group.values()) {
                        if (!values.add(scalarKey(filter.type(), bucket.value()))) {
                            throw new IllegalArgumentException("Refinement values must be unique");
                        }
                    }
                    return new ResolvedRefinement(filter, group.values());
                })
                .toList();
    }

    public static void validateRefinementContext(
            OfferingPage page, SearchRequests.Offerings request, boolean continuation) {
        if (page.refinements() == null) return;
        if (continuation || request == null || request.refinements() == null) {
            throw new IllegalArgumentException("Refinements require an initial request that asks for them");
        }
        Set<String> groups = new HashSet<>();
        for (OfferingPage.RefinementGroup group : page.refinements()) {
            if (!request.refinements().contains(group.filterId()) || !groups.add(group.filterId())) {
                throw new IllegalArgumentException("Refinement group is unrequested or repeated");
            }
        }
    }

    private FilterDefinition requireFilter(String id) {
        FilterDefinition filter = catalogFilters.get(id);
        if (filter == null) throw new IllegalArgumentException("Filter is unavailable: " + id);
        return filter;
    }

    private static Object scalarKey(String type, OdpJsonNode value) {
        try {
            return parseScalarKey(type, value);
        } catch (java.time.DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid Filter value for " + type, exception);
        }
    }

    private static Object parseScalarKey(String type, OdpJsonNode value) {
        String raw = value.toString();
        String text = value.isString() ? value.asString() : null;
        return switch (type) {
            case "string" -> {
                if (!value.isString()) throw invalidValue(type);
                yield text;
            }
            case "boolean" -> {
                if (!"true".equals(raw) && !"false".equals(raw)) throw invalidValue(type);
                yield Boolean.valueOf(raw);
            }
            case "integer", "number", "decimal" -> {
                boolean decimal = "decimal".equals(type);
                if (decimal
                        ? !value.isString() || !DECIMAL.matcher(text).matches()
                        : !NUMBER.matcher(raw).matches()) throw invalidValue(type);
                BigDecimal number = new BigDecimal(decimal ? text : raw).stripTrailingZeros();
                if ("integer".equals(type) && number.scale() > 0) throw invalidValue(type);
                yield number;
            }
            case "date" -> {
                if (!value.isString() || !DATE.matcher(text).matches()) throw invalidValue(type);
                yield LocalDate.parse(text);
            }
            case "date-time" -> {
                if (!value.isString()) throw invalidValue(type);
                var matcher = INSTANT.matcher(text);
                if (!matcher.matches()) throw invalidValue(type);
                boolean leap = "60".equals(matcher.group(2));
                String whole = matcher.group(1) + (leap ? "59" : matcher.group(2));
                long seconds =
                        LocalDateTime.parse(whole.toUpperCase(Locale.ROOT)).toEpochSecond(ZoneOffset.UTC);
                String offset = matcher.group(4);
                if (!UTC_OFFSET.equalsIgnoreCase(offset)) {
                    int hours = Integer.parseInt(offset.substring(1, 3));
                    int minutes = Integer.parseInt(offset.substring(4, 6));
                    if (hours > 23 || minutes > 59) throw invalidValue(type);
                    seconds -= (offset.charAt(0) == '-' ? -1 : 1) * (hours * 3600L + minutes * 60L);
                }
                BigDecimal fraction =
                        matcher.group(3) == null ? BigDecimal.ZERO : new BigDecimal("0" + matcher.group(3));
                yield List.of(BigDecimal.valueOf(seconds).add(fraction).stripTrailingZeros(), leap);
            }
            default -> throw invalidValue(type);
        };
    }

    private static IllegalArgumentException invalidValue(String type) {
        return new IllegalArgumentException("Invalid Filter value for " + type);
    }

    public record ResolvedSort(SortDefinition definition, List<FilterDefinition> filters) {
        public ResolvedSort {
            filters = List.copyOf(filters);
        }
    }

    public record ResolvedRefinement(FilterDefinition filter, List<OfferingPage.RefinementBucket> values) {
        public ResolvedRefinement {
            values = List.copyOf(values);
        }
    }
}
