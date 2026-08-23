package dev.mars.peegeeq.cache.rest.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Allowlist and address-class policy for outbound PostgreSQL setup targets. */
public final class SetupTargetPolicy {

    private static final Pattern IPV4 = Pattern.compile("[0-9.]+");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");
    private static final Pattern HOST_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private final Set<String> allowedDnsSuffixes;
    private final List<Cidr> allowedCidrs;
    private final Set<Integer> allowedPorts;
    private final boolean allowLoopback;
    private final boolean allowPrivate;
    private final boolean allowLinkLocal;
    private final boolean allowPublic;
    private final Set<String> trustProfileIds;

    public SetupTargetPolicy(
            Set<String> allowedDnsSuffixes,
            Set<String> allowedCidrs,
            Set<Integer> allowedPorts,
            boolean allowLoopback,
            boolean allowPrivate,
            boolean allowLinkLocal,
            boolean allowPublic,
            Set<String> trustProfileIds) {
        this.allowedDnsSuffixes = normalizeSuffixes(allowedDnsSuffixes);
        Objects.requireNonNull(allowedCidrs, "allowedCidrs");
        ArrayList<Cidr> cidrs = new ArrayList<>();
        for (String cidr : allowedCidrs) {
            cidrs.add(Cidr.parse(Objects.requireNonNull(cidr, "allowedCidr").trim()));
        }
        this.allowedCidrs = List.copyOf(cidrs);
        if (this.allowedDnsSuffixes.isEmpty() && this.allowedCidrs.isEmpty()) {
            throw new IllegalArgumentException("Target policy requires a DNS suffix or CIDR allowlist");
        }
        Objects.requireNonNull(allowedPorts, "allowedPorts");
        if (allowedPorts.isEmpty() || allowedPorts.stream().anyMatch(port -> port == null || port < 1 || port > 65535)) {
            throw new IllegalArgumentException("Target policy requires valid allowed ports");
        }
        this.allowedPorts = Set.copyOf(allowedPorts);
        this.allowLoopback = allowLoopback;
        this.allowPrivate = allowPrivate;
        this.allowLinkLocal = allowLinkLocal;
        this.allowPublic = allowPublic;
        Objects.requireNonNull(trustProfileIds, "trustProfileIds");
        if (trustProfileIds.isEmpty() || trustProfileIds.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("At least one server-known trust profile is required");
        }
        this.trustProfileIds = Set.copyOf(trustProfileIds);
    }

    public static SetupTargetPolicy privateNetworks(
            Set<String> suffixes,
            Set<String> cidrs,
            Set<Integer> ports,
            Set<String> trustProfiles) {
        return new SetupTargetPolicy(
                suffixes, cidrs, ports, false, true, false, false, trustProfiles);
    }

    public void validate(
            String hostname,
            int port,
            List<InetAddress> resolvedAddresses,
            String trustProfileId) {
        String normalizedHost;
        try {
            normalizedHost = normalizeHostname(hostname);
        } catch (IllegalArgumentException exception) {
            throw forbidden();
        }
        if (!allowedPorts.contains(port)
                || !trustProfileIds.contains(trustProfileId)
                || !hostAllowed(normalizedHost)
                || resolvedAddresses == null
                || resolvedAddresses.isEmpty()) {
            throw forbidden();
        }
        for (InetAddress address : resolvedAddresses) {
            if (address == null || !addressAllowed(address)) {
                throw forbidden();
            }
        }
    }

    private boolean hostAllowed(String host) {
        if (isLiteral(host)) {
            return true;
        }
        return allowedDnsSuffixes.stream()
                .anyMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix));
    }

    private boolean addressAllowed(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        boolean addressClassAllowed;
        if (address.isLoopbackAddress()) {
            addressClassAllowed = allowLoopback;
        } else if (address.isLinkLocalAddress()) {
            addressClassAllowed = allowLinkLocal;
        } else if (isPrivate(address)) {
            addressClassAllowed = allowPrivate;
        } else {
            addressClassAllowed = allowPublic;
        }
        return addressClassAllowed && allowedCidrs.stream().anyMatch(cidr -> cidr.contains(address));
    }

    private static boolean isPrivate(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 10
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        }
        return (bytes[0] & 0xfe) == 0xfc;
    }

    private static Set<String> normalizeSuffixes(Set<String> suffixes) {
        Objects.requireNonNull(suffixes, "allowedDnsSuffixes");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String suffix : suffixes) {
            String value = normalizeHostname(suffix);
            if (isLiteral(value) || !normalized.add(value)) {
                throw new IllegalArgumentException("Invalid or duplicate DNS suffix");
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeHostname(String hostname) {
        String value = Objects.requireNonNull(hostname, "hostname").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > 253 || value.endsWith(".") || value.contains(":")) {
            if (isLiteral(value) && !value.endsWith(".")) {
                return value;
            }
            throw new IllegalArgumentException("Invalid database hostname");
        }
        for (String label : value.split("\\.", -1)) {
            if (!HOST_LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("Invalid database hostname");
            }
        }
        return value;
    }

    private static boolean isLiteral(String value) {
        return IPV4.matcher(value).matches() || (value.indexOf(':') >= 0 && IPV6.matcher(value).matches());
    }

    private static ManagementSecurityException forbidden() {
        return new ManagementSecurityException(403, "TARGET_FORBIDDEN", "Database target is forbidden by policy");
    }

    private record Cidr(byte[] network, int prefix) {
        private Cidr {
            network = network.clone();
        }

        static Cidr parse(String text) {
            int slash = text.indexOf('/');
            if (slash <= 0 || slash != text.lastIndexOf('/') || slash == text.length() - 1) {
                throw new IllegalArgumentException("Invalid target CIDR");
            }
            byte[] address = literalAddress(text.substring(0, slash));
            int prefix;
            try {
                prefix = Integer.parseInt(text.substring(slash + 1));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid target CIDR prefix", exception);
            }
            int bits = address.length * Byte.SIZE;
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Target CIDR prefix exceeds address width");
            }
            byte[] network = address.clone();
            for (int bit = prefix; bit < bits; bit++) {
                network[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
            }
            return new Cidr(network, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((candidate[bit / 8] & mask) != (network[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }

        private static byte[] literalAddress(String literal) {
            if (!isLiteral(literal)) {
                throw new IllegalArgumentException("Target CIDR must contain a literal address");
            }
            if (IPV4.matcher(literal).matches()) {
                String[] octets = literal.split("\\.", -1);
                if (octets.length != 4) {
                    throw new IllegalArgumentException("Invalid IPv4 target CIDR");
                }
                for (String octet : octets) {
                    if (octet.isEmpty() || octet.length() > 3 || Integer.parseInt(octet) > 255) {
                        throw new IllegalArgumentException("Invalid IPv4 target CIDR");
                    }
                }
            }
            try {
                return InetAddress.getByName(literal).getAddress();
            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException("Invalid target CIDR address", exception);
            }
        }
    }
}
