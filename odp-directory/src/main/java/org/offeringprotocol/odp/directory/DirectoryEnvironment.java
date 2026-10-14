package org.offeringprotocol.odp.directory;

import java.net.URI;

public enum DirectoryEnvironment {
    PRODUCTION("https://directory.offeringprotocol.org"),
    SANDBOX("https://sandbox.inflowpay.ai");

    private final URI canonicalOrigin;

    DirectoryEnvironment(String origin) {
        this.canonicalOrigin = URI.create(origin);
    }

    public URI origin() {
        return canonicalOrigin;
    }
}
