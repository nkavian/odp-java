# ODP Core

Transport-independent Offering Discovery Protocol models, validation, identity, resource-reference,
Problem Details, and pagination primitives.

Most Agent and Service applications receive `odp-core` transitively through their role module.
Depend on Core directly when implementing protocol tooling, validating stored documents, or using
ODP models without Agent or Service HTTP behavior.

## Install

Follow the canonical [installation guide](../README.md#installation), selecting `odp-core` and
exactly one JSON provider.

Use `odp-json-jackson2` with Jackson 2 applications or replace it with `odp-json-jackson3` for
Jackson 3. Add exactly one provider. `OdpJson` discovers the provider through Java `ServiceLoader`
and rejects a runtime with no provider or multiple providers. `odp-core` requires Java 17 or newer
and does not select a JSON library or application framework.

## Validate and decode documents

`OdpJson` validates incoming JSON against the exact ODP schemas bundled in the published JAR before
decoding it into immutable Java models.

SDK-generated requests and Service Document builders use protocol version `1.0`. Incoming `1.x`
documents are validated using the `1.0` schemas. Parsed models retain the received version.
Unsupported major versions and malformed version strings are rejected.

```java
try {
    ServiceDocument document = OdpJson.parseServiceDocument(responseBody);
    use(document);
} catch (OdpValidationException exception) {
    for (ValidationIssue issue : exception.issues()) {
        System.err.printf("%s: %s%n", issue.path(), issue.message());
    }
}
```

Typed parsers are available for Service Documents, Collections, Offerings, Offering search
responses, search requests, page envelopes, and ODP Problem Details. `OdpJson.write(value)` encodes
the corresponding Java records while omitting absent optional members. Unknown additive members
permitted by the protocol are retained in each model's `additional` map.

JSON-valued protocol members use `OdpJsonNode`, so public ODP models do not expose either Jackson
major version. Use `OdpJson.parseTree`, `OdpJson.valueToTree`, and `OdpJson.treeToValue` at the
application boundary when those members require tree access.

Validation failures are reported as `OdpValidationException` with a document type and structured
issues. Invalid local method arguments use `IllegalArgumentException`.

## Build a Service Document

Use `ServiceDocument.builder(...)` when protocol tooling needs to construct a document directly.
The builder sets the current ODP version and defaults `localizations` to the selected language:

```java
ServiceDocument document = ServiceDocument.builder(
                "Example Plant Store",
                "Indoor plants selected for homes and offices.",
                "en",
                new ServiceDocument.Http("/odp", null))
        .keywords(List.of("plants", "indoor-plants"))
        .operations(operations)
        .build();
```

Service applications should normally use the higher-level `OdpService.builder(...)`, which derives
the operation descriptors from the handlers the application configures.

## Resource identity and references

`ResourceIdentity` composes the Service origin, resource type, and Service-owned identifier into a
stable identity suitable for application storage:

```java
ResourceIdentity identity = ResourceIdentity.create(
        URI.create("https://service.example/.well-known/odp"),
        "offering",
        "gpu-h100");
```

`OdpUris` derives a Service origin, resolves Service-owned resource references, validates opaque
continuations, and builds the fixed URL for an advertised operation. Resource references accept
root-relative paths or secure absolute URLs. Continuations must remain on the Service origin, and
operation identifiers must be safe local path segments.

## Pagination

ODP continuation values are opaque. Pass each `next` value unchanged to the appropriate page
loader:

```java
Page<Offering> first = client.listOfferings("terse", 25, "en");
List<Offering> offerings = OdpPagination.items(
        first,
        next -> client.continueOfferings(next, "terse", "en"));
```

For incremental consumption, use a synchronous, single-use iterator:

```java
Iterator<Offering> offerings = OdpPagination.iterate(
        () -> client.listOfferings("terse", 25, "en"),
        next -> client.continueOfferings(next, "terse", "en"),
        100);
while (offerings.hasNext()) {
    consume(offerings.next());
}
```

The first page is fetched on the first `hasNext()` or `next()` call. Further pages are fetched only
when needed. The final argument limits total items, independently of the Service page size; zero
performs no requests. Stop calling the iterator to stop fetching. A failed page terminates the
traversal without losing items already delivered; subsequent calls rethrow that failure without
another request. Response limits throw `OdpResponseLimitException` directly, with code
`RESPONSE_LIMIT_EXCEEDED` and `retryable()` set to `false`. Other loader failures are retained as the cause of an
`IllegalStateException`. Iterators are not thread-safe. Applications own any asynchronous wrapping.

Both helpers detect continuation loops and enforce a local maximum of 16 pages per traversal.
Applications following explicit pages can choose their own traversal bounds.

## Search validation

`SearchCatalog` accepts the effective Filter and Sort definitions for one search scope and exposes
indexed definitions, with each Sort's Filter references resolved. `validateRequest` checks Filter
operators and typed values, Sort availability, and refinable identifiers. `validateRefinements`
checks the returned groups against the request and returns groups paired with their Filter
Definitions. Decimal equality is numeric; date-time equality compares instants.

This class performs no network or database work. The Agent module resolves inline and linked
sources. A Service supplies definitions from its own catalog and remains responsible for executing
queries and computing accurate refinement counts.

## Payment option vocabulary

`PaymentOption` contains the closed human-facing option vocabulary that a Service can advertise for
MPP or x402, such as `INFLOW`, `SOLANA`, or `BASE`. These values summarize compatibility for
discovery and filtering. Live MPP and x402 responses remain authoritative for exact payment terms.

`ServiceDocument.TrustProtocol` represents advertised trust support. A Service that accepts Visa
Trusted Agent Protocol requests declares a single `tap` descriptor in `protocols.trust`.

Service authoring uses `OdpJson.parseServiceDocument` and rejects protocol names outside the
declared ODP version. Agent readers use `OdpJson.parseAgentServiceDocument`; it filters unrecognized
enrollment, payment, and trust descriptors before validating every recognized descriptor.

## Related documentation

- [Agent integration](../odp-agent/README.md)
- [Directory integration](../odp-directory/README.md)
- [Service integration](../odp-service/README.md)
- [Normative ODP specifications](https://www.offeringprotocol.org/)
- [Maven Central artifact](https://central.sonatype.com/artifact/org.offeringprotocol/odp-core)
