package org.offeringprotocol.odp.agent;

import java.util.List;
import org.offeringprotocol.odp.core.OfferingPage;
import org.offeringprotocol.odp.core.SearchCatalog;

public record OfferingSearchDetails(
        OfferingPage page,
        SearchCatalog catalog,
        List<SearchCatalog.ResolvedRefinement> refinements,
        List<SearchCapabilityResult.Issue> issues) {
    public OfferingSearchDetails {
        refinements = List.copyOf(refinements);
        issues = List.copyOf(issues);
    }
}
