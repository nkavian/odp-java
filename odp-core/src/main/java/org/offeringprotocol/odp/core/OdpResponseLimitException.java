package org.offeringprotocol.odp.core;

public final class OdpResponseLimitException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public OdpResponseLimitException(String message) {
        super(message);
    }

    public String code() {
        return "RESPONSE_LIMIT_EXCEEDED";
    }

    public boolean retryable() {
        return false;
    }
}
