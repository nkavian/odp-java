package org.offeringprotocol.odp.agent;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.offeringprotocol.odp.core.OdpAddresses;

/**
 * SEC-08: the destination of every connection is resolved and checked against the IANA
 * special-purpose address registries before a request is sent, so a Service name cannot point an
 * Agent at an address its own network treats as internal. Which addresses those are is
 * {@link OdpAddresses}; what this adds is resolving the name first and judging every address it
 * answers with, not only the first.
 */
final class SecureDestinations {
    private SecureDestinations() {}

    /** A transport that reaches public destinations only. */
    static OdpTransport transport(boolean allowLocalNetwork) {
        return new ApacheTransport(allowLocalNetwork);
    }

    static void require(URI target, boolean allowLocalNetwork) {
        resolve(target, allowLocalNetwork);
    }

    static InetAddress[] resolve(URI target, boolean allowLocalNetwork) {
        String host = target.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("ODP request target must name a host");
        }
        // URI keeps the brackets around an IPv6 literal; address resolution does not want them.
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("ODP request host did not resolve", exception);
        }
        if (addresses.length == 0) {
            throw new IllegalStateException("ODP request host did not resolve");
        }
        return validate(host, addresses, allowLocalNetwork);
    }

    static InetAddress[] validate(String host, InetAddress[] addresses, boolean allowLocalNetwork) {
        boolean local = isLocalDevelopmentHost(host);
        for (InetAddress address : addresses) {
            if (local && allowLocalNetwork) {
                if (!address.isLoopbackAddress()) {
                    throw new IllegalStateException("ODP local-development host resolved outside the loopback network");
                }
            } else if (!OdpAddresses.isPublic(address)) {
                throw new IllegalStateException("ODP request host resolved to a non-public address");
            }
        }
        return addresses;
    }

    private static boolean isLocalDevelopmentHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(value)
                || "127.0.0.1".equals(value) // NOPMD - ODP explicitly permits loopback for local development.
                || "::1".equals(value) // NOPMD - ODP explicitly permits loopback for local development.
                || "[::1]".equals(value);
    }
}
