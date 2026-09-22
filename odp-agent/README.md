# ODP Agent

Agent-oriented directory-to-Service discovery, live Service inspection, catalog navigation, and
Offering discovery.

Use `OdpAgent` for a bounded convenience search across multiple Services. Use `OdpServiceClient`
when the application already knows a Service origin or needs explicit control over inspection,
capability checks, Collections, Offerings, localization, and continuations.

## Install

Follow the canonical [installation guide](../README.md#installation), selecting `odp-agent` and
exactly one JSON provider.

The Agent module brings in `odp-directory` and `odp-core` transitively. Replace
`odp-json-jackson2` with `odp-json-jackson3` in a Jackson 3 application. Exactly one provider must
be present at runtime; no programmatic configuration is required.

## Discover Offerings across Services

`OdpAgent` searches the canonical directory, inspects each selected Service, and searches Services
that advertise `search-offerings`.

```java
DirectoryClient directory = DirectoryClient.create();
OdpAgent agent = new OdpAgent(directory);

for (OdpAgent.DiscoveryEvent event : agent.searchOfferings("plants", 10, 10)) {
    if (event instanceof OdpAgent.OfferingEvent offering) {
        consume(offering.service(), offering.offering());
    } else if (event instanceof OdpAgent.IssueEvent issue) {
        report(issue.service(), issue.message());
    }
}
```

The second argument limits directory Services and the third limits Offerings retained from each
Service. Both must be from 1 through 100. Results preserve directory order. A failed Service emits
an `IssueEvent`; it does not discard successful results from other Services.

This convenience method does not reinterpret a Service that lacks `search-offerings` as a listing
request. Applications that want that fallback should use `DirectoryClient`, inspect each Service,
and explicitly choose search or list from the advertised operations.

Use the sandbox directory by constructing the Agent with an explicitly selected client:

```java
OdpAgent agent = new OdpAgent(
        DirectoryClient.create(DirectoryEnvironment.SANDBOX));
```

## Inspect one Service

For mixed Service/Collection discovery, call `DirectoryClient.search`. A `CollectionResult`
contains its owning Service origin and remote Collection ID. Inspect that Service and use
`getCollection` to retrieve current details. `OdpAgent.searchOfferings` remains Service-only;
it does not treat Collection results as separate Services. See the
[Directory guide](../odp-directory/README.md#search-services-and-collections) for the mixed API.

Creating a Service client retrieves `/.well-known/odp`, validates the document, and records the
Service's advertised operations.

```java
OdpServiceClient service = OdpServiceClient.create(
        URI.create("https://service.example"));

ServiceInspection inspection = service.inspection();
System.out.println(inspection.document().name());
System.out.println(inspection.document().protocols());

if (inspection.supports(OdpOperation.LIST_OFFERINGS)) {
    Page<Offering> page = service.listOfferings("terse", 25, "en");
    consume(page.items());
}
```

The input may be the Service origin or another URL on that origin. Production Services must use
HTTPS; loopback HTTP is accepted for local development. The client accepts at most five
same-origin redirects and bounds Service Document and catalog response bodies.

The Service Document is fetched once during `OdpServiceClient.create(...)` and retained for that
client's lifetime. Recreate the client when the application needs a refreshed Service Document.
The client has no built-in HTTP response cache, in memory or on disk. Catalog and supporting-document
requests use the transport on each call. Applications that add caching must honor HTTP cache
directives and validators and keep responses isolated by authentication context. The retained
Service inspection is a snapshot, not a freshness-managed HTTP cache.

Read-only ODP requests retry HTTP 429 and 503 at most three times within a 30-second retry
window. `Retry-After` is honored when its delay fits within that window. A 503 without that header
uses exponential backoff with jitter. Missing delay information on 429, malformed delays,
non-transient responses, and transport failures return without automatic retry. Interruption stops
waiting. This policy also applies to supporting-document retrieval; it never executes or retries
an Offering Action.

## Navigate Collections and Offerings

The client exposes only explicit network operations; it never calls an unadvertised operation.

```java
Page<Collection> collections = service.listCollections("terse", 25, "en");
Collection collection = service.getCollection("indoor-plants", "full", "en");
Page<Offering> members = service.listCollectionOfferings(
        collection.id(), "terse", 25, "en");

Page<Offering> offerings = service.listOfferings("terse", 25, "en");
Offering details = service.getOffering("rubber-plant", "full", "en");
```

Representation is `terse` or `full`. Passing `null` selects `full` for individual Offering and
Collection retrieval, and `terse` for list and search methods. Language is sent through
`Accept-Language` when it is nonblank. Limits must be from 1 through 100.

Search requests preserve the protocol's structured filters, Collection scope, sort identifier,
and refinements:

```java
SearchRequests.Offerings request = new SearchRequests.Offerings(
        Odp.VERSION,
        "indoor plant",
        null,
        "indoor-plants",
        null,
        null,
        null,
        10);

OfferingPage matches = service.searchOfferings(request, "terse", "en");
```

Call `searchCollections(...)` with `SearchRequests.Collections` for Collection search. Check
`inspection.supports(...)` before invoking optional Collection or search operations; the client
also rejects an unsupported call locally.

### Interpret a full Offering

Use `getOfferingDetails(...)` when the application needs the Offering's Service-defined attributes
or Actions:

```java
OfferingDetails details = service.getOfferingDetails("rubber-plant", "en");

if (details.attributeSchema() != null) {
    consume(details.offering().attributes(), details.attributeSchema());
}
for (DiscoveredAction action : details.actions()) {
    System.out.println(action.id() + " " + action.rel());
}
for (OfferingIssue issue : details.issues()) {
    report(issue.scope(), issue.message());
}
```

The Agent retrieves the complete bounded JSON Schema Draft 2020-12 graph, bundles external `$ref`
documents into the returned schema, and validates full Offering attributes. `$dynamicRef` is limited
to fragment references such as `#node`. An unavailable, unsupported, or non-matching schema removes
only the uninterpretable attributes and produces a scoped issue; the Offering remains usable.

Attribute Schema processing is limited to 256 KiB per document, 16 documents, eight reference
levels, and one MiB for the complete graph. Each Attribute Schema request has a 30-second timeout
and accepts at most 16 JSON nesting levels. These are fixed SDK safety ceilings.

Actions are normalized to absolute compact HTTP or OpenAPI targets.
Invalid descriptors and duplicate Action identifiers are omitted and reported through
`OfferingDetails.issues()`. Unrelated Actions and Offering fields remain available.

Resolve the supporting document for one explicitly selected Action without invoking it:

```java
ResolvedAction action = service.resolveAction("rubber-plant", "purchase", "en");

if (action.requestSchema() != null) {
    prepareBody(action.action().http(), action.requestSchema());
} else if (action.openApiDocument() != null) {
    prepareOperation(action.action().openapi(), action.operation());
}
```

Compact HTTP request schemas follow the same bounded resolution rules as Attribute Schemas. OpenAPI
targets require a JSON OpenAPI 3.1 document containing exactly one matching `operationId`; each
OpenAPI document is limited to one MiB and 32 JSON nesting levels.

## Search with advertised capabilities

Use `resolveSearchCapabilities(collectionId, language)` to obtain indexed Filter Definitions,
Sort Definitions with their resolved Filters, and scoped issues. Pass `null` for the Collection
to use only Service-wide definitions. The resolver does not visit ancestors or descendants.
Linked sources use the Service transport, including its authentication handling, and are accepted
only after all pages have been validated. Invalid sources do not discard valid sources.

```java
SearchCapabilityResult capabilities = service.resolveSearchCapabilities(null, "en");
capabilities.catalog().filters().forEach((id, definition) -> showFilter(definition));

SearchRequests.Offerings request = new SearchRequests.Offerings(
    "1.0", "office plants", null, null, null, null, null, 20);
OfferingSearchDetails result = service.searchOfferingsDetails(request, "terse", "en");
```

`searchOfferingsDetails` resolves capabilities, validates the request against them, and returns
Offerings together with normalized refinements and issues. Invalid refinement groups are omitted
without discarding the Offerings or valid groups. Each normalized group includes its Filter
Definition, so the caller can interpret the values and units without joining identifiers itself.
Scoped HTTP failures retain the status, headers, and parsed problem in `responseFailure()`; the SDK
does not enroll, pay, or automatically retry them. Thread interruption stops resolution.

The lower-level `searchOfferings` sends the supplied request without fetching capability sources.
It validates the response structure and requested refinement identifiers, but does not perform
definition-dependent validation. Callers managing their own capability catalog can use
`SearchCatalog.validateRequest` and `validateRefinements` directly. Catalogs are request-context
dependent: do not reuse them across different Services, selected Collections, or access contexts.
Create a fresh Service client when changing its authentication context; its Service Document is
the snapshot retrieved during inspection.

## Continue a response

Continuation values are opaque. Pass `next` unchanged to the matching continuation method:

```java
Page<Offering> page = service.listOfferings("terse", 25, "en");
consume(page.items());
while (page.next() != null) {
    page = service.continueOfferings(page.next(), "terse", "en");
    consume(page.items());
}
```

Pass the original representation explicitly so each continuation response receives the same
validation. The client does not interpret or rewrite the opaque URL to recover this selection.
Use `continueCollections` for Collection pages. `OdpPagination` in Core can collect a bounded
traversal and rejects loops after at most 16 pages. Applications following pages directly should
apply their own total page and item limits.

To consume items incrementally without an asynchronous framework:

```java
Iterator<Offering> offerings = OdpPagination.iterate(
        () -> service.listOfferings("terse", 25, "en"),
        next -> service.continueOfferings(next, "terse", "en"),
        100);
while (offerings.hasNext()) {
    consume(offerings.next());
}
```

The iterator blocks while fetching a page, does not prefetch, and stops after the total item limit.
Stop calling it to stop fetching. Previously delivered items remain usable if a subsequent page
fails; the iterator rethrows that failure without fetching again. The same helper accepts Collection
pages. For Offering search, supply `() -> service.searchOfferings(request, "terse", "en").asPage()`
as the first loader; subsequent pages use `continueOfferings`. Refinement groups remain on the original
`OfferingPage`, not on individual iterated items. Applications own asynchronous wrapping and must
not use an iterator concurrently.

## Authentication and payment transport

The default client performs anonymous HTTP requests. ODP advertises authentication requirements and
payment protocols but does not implement AEP, MPP, or x402 credentials in this module.

The built-in transport uses Apache HttpClient 5 internally. It validates every resolved address,
connects to a validated address without a second DNS lookup, and verifies the connected peer before
sending the HTTP request. HTTPS certificate and hostname verification remain enabled. System proxies,
automatic redirects, automatic retries, and cookie storage are disabled. Response bodies are bounded
while reading, including decompressed bodies and error responses. Connections are pooled by hostname
and pinned address. If connection establishment fails, another validated address can be attempted
within the request timeout, after repeating DNS validation. An HTTP request is not replayed after a
response or a failure while sending or reading it.

Apache's runtime dependencies are HttpCore, HttpCore HTTP/2, and the SLF4J API. No Kotlin runtime or
logging backend is required. Its types are not part of the ODP public API.

Use the built-in transport explicitly when composing clients:

```java
OdpTransport transport = OdpServiceClient.defaultTransport();

OdpServiceClient service = OdpServiceClient.create(
        URI.create("https://service.example"),
        transport);
```

The transport receives the complete ODP `HttpRequest` and must return an
`HttpResponse<byte[]>`. An application that supports AEP, MPP, or x402 supplies its
protocol-aware transport and performs challenge handling before returning the final response. Keep
credentials scoped to the intended Service and authenticated principal. `OdpAgent` accepts a
`ServiceClientFactory` when federated discovery needs the same custom transport for each Service.

Custom transports are responsible for the same destination and credential protections. Override
`send(request, maximumBytes)` to enforce the byte budget during the download; the compatibility
default delegates to `send(request)` and cannot prevent that implementation from buffering too much
data. Client-side checks still reject an oversized returned body.

Attribute Schema, Action request-schema, and OpenAPI requests use the default anonymous transport so
credentials added by the catalog transport are not forwarded to supporting-resource origins. Supply
an explicit anonymous supporting transport as the third argument when the application needs to
control that network boundary:

```java
OdpServiceClient service = OdpServiceClient.create(
        URI.create("https://service.example"),
        protocolAwareTransport,
        anonymousSupportingTransport);
```

Supporting-document resolution does not invoke an Action. The application remains responsible for
Action selection, user approval, authentication, payment, request construction, and invocation.

Service inspection filters unrecognized enrollment, payment, and trust protocol descriptors for
compatible ODP versions. Recognized descriptors remain subject to the complete Service Document
contract.

## Errors

Non-success Service responses throw `OdpRequestException`, which preserves the HTTP status,
headers, and parsed ODP Problem Details when supplied. Invalid protocol documents throw
`OdpValidationException`. Invalid local arguments use `IllegalArgumentException`; unsupported
operations and transport-boundary failures use `IllegalStateException`.

Response byte and nesting-depth limits throw `OdpResponseLimitException` with
`code()` equal to `RESPONSE_LIMIT_EXCEEDED` and `retryable()` equal to `false`.
This is a local rejection, not an HTTP error. Attribute Schema limit failures remain
scoped issues: affected attributes are omitted, while other Offering fields remain usable.

## Related documentation

- [Directory integration](../odp-directory/README.md)
- [Core models and validation](../odp-core/README.md)
- [Runnable Agent example](../examples/README.md#agent-discovery)
- [Maven Central artifact](https://central.sonatype.com/artifact/org.offeringprotocol/odp-agent)
