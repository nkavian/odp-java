package org.offeringprotocol.odp.agent;

import java.util.List;
import org.offeringprotocol.odp.core.SearchCatalog;

public record SearchCapabilityResult(SearchCatalog catalog, List<Issue> issues) {
    public SearchCapabilityResult {
        issues = List.copyOf(issues);
    }

    public record Issue(String scope, String kind, String identifier, String message, HttpFailure responseFailure) {
        public Issue(String scope, String kind, String identifier, String message) {
            this(scope, kind, identifier, message, null);
        }
    }

    public record HttpFailure(
            int status, java.net.http.HttpHeaders headers, org.offeringprotocol.odp.core.ProblemDetails problem) {
        static HttpFailure from(OdpRequestException exception) {
            return new HttpFailure(exception.status(), exception.headers(), exception.problem());
        }
    }
}
