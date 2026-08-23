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

/** Configuration for the trusted reverse-proxy identity boundary. */
public final class TrustedProxyAuthenticationConfig {

    public static final String DEFAULT_USER_HEADER = "X-PeeGeeQ-User";
    public static final String DEFAULT_ROLES_HEADER = "X-PeeGeeQ-Roles";

    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9.]+");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");

    private final Set<String> trustedProxyCidrs;
    private final List<IpCidr> trustedNetworks;
    private final String userHeaderName;
    private final String rolesHeaderName;
    private final Set<String> allowedRoles;

    public TrustedProxyAuthenticationConfig(
            Set<String> trustedProxyCidrs,
            String userHeaderName,
            String rolesHeaderName,
            Set<String> allowedRoles) {
        Objects.requireNonNull(trustedProxyCidrs, "trustedProxyCidrs");
        if (trustedProxyCidrs.isEmpty()) {
            throw new IllegalArgumentException("At least one trusted proxy CIDR is required");
        }
        this.userHeaderName = requireHeaderName(userHeaderName, "userHeaderName");
        this.rolesHeaderName = requireHeaderName(rolesHeaderName, "rolesHeaderName");
        if (this.userHeaderName.equalsIgnoreCase(this.rolesHeaderName)) {
            throw new IllegalArgumentException("Identity header names must differ");
        }

        LinkedHashSet<String> copiedCidrs = new LinkedHashSet<>();
        ArrayList<IpCidr> networks = new ArrayList<>();
        for (String cidr : trustedProxyCidrs) {
            String value = Objects.requireNonNull(cidr, "trustedProxyCidr").trim();
            if (!copiedCidrs.add(value)) {
                throw new IllegalArgumentException("Duplicate trusted proxy CIDR");
            }
            networks.add(IpCidr.parse(value));
        }
        this.trustedProxyCidrs = Set.copyOf(copiedCidrs);
        this.trustedNetworks = List.copyOf(networks);

        Objects.requireNonNull(allowedRoles, "allowedRoles");
        if (allowedRoles.isEmpty()) {
            throw new IllegalArgumentException("At least one management role is required");
        }
        LinkedHashSet<String> normalizedRoles = new LinkedHashSet<>();
        for (String role : allowedRoles) {
            String normalized = Objects.requireNonNull(role, "allowedRole").trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || !normalized.equals(role)) {
                throw new IllegalArgumentException("Allowed roles must be normalized lowercase values");
            }
            if (!normalizedRoles.add(normalized)) {
                throw new IllegalArgumentException("Duplicate allowed role");
            }
        }
        this.allowedRoles = Set.copyOf(normalizedRoles);
    }

    public static TrustedProxyAuthenticationConfig defaults(Set<String> trustedProxyCidrs) {
        return new TrustedProxyAuthenticationConfig(
                trustedProxyCidrs,
                DEFAULT_USER_HEADER,
                DEFAULT_ROLES_HEADER,
                Set.of("viewer", "operator"));
    }

    public Set<String> trustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public String userHeaderName() {
        return userHeaderName;
    }

    public String rolesHeaderName() {
        return rolesHeaderName;
    }

    public Set<String> allowedRoles() {
        return allowedRoles;
    }

    boolean trusts(InetAddress address) {
        return trustedNetworks.stream().anyMatch(network -> network.contains(address));
    }

    private static String requireHeaderName(String value, String field) {
        String header = Objects.requireNonNull(value, field).trim();
        if (!HEADER_NAME.matcher(header).matches()) {
            throw new IllegalArgumentException("Invalid identity header name");
        }
        return header;
    }

    private record IpCidr(byte[] network, int prefixLength) {

        private IpCidr {
            network = network.clone();
        }

        static IpCidr parse(String cidr) {
            int separator = cidr.indexOf('/');
            if (separator <= 0 || separator == cidr.length() - 1 || separator != cidr.lastIndexOf('/')) {
                throw new IllegalArgumentException("Trusted proxy must be a literal CIDR");
            }
            String addressPart = cidr.substring(0, separator);
            byte[] address = parseLiteral(addressPart);
            int prefix;
            try {
                prefix = Integer.parseInt(cidr.substring(separator + 1));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid CIDR prefix", exception);
            }
            int maximum = address.length * Byte.SIZE;
            if (prefix < 0 || prefix > maximum) {
                throw new IllegalArgumentException("CIDR prefix is outside the address width");
            }
            byte[] network = address.clone();
            for (int bit = prefix; bit < maximum; bit++) {
                network[bit / Byte.SIZE] &= (byte) ~(1 << (7 - bit % Byte.SIZE));
            }
            return new IpCidr(network, prefix);
        }

        boolean contains(InetAddress candidate) {
            byte[] address = Objects.requireNonNull(candidate, "immediatePeer").getAddress();
            if (address.length != network.length) {
                return false;
            }
            int wholeBytes = prefixLength / Byte.SIZE;
            for (int index = 0; index < wholeBytes; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % Byte.SIZE;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (Byte.SIZE - remainingBits);
            return (address[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }

        private static byte[] parseLiteral(String literal) {
            if (literal.indexOf(':') >= 0) {
                if (!IPV6_LITERAL.matcher(literal).matches()) {
                    throw new IllegalArgumentException("Trusted proxy CIDR contains a non-literal IPv6 address");
                }
            } else {
                if (!IPV4_LITERAL.matcher(literal).matches()) {
                    throw new IllegalArgumentException("Trusted proxy CIDR contains a non-literal IPv4 address");
                }
                validateIpv4(literal);
            }
            try {
                return InetAddress.getByName(literal).getAddress();
            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException("Trusted proxy CIDR contains an invalid address", exception);
            }
        }

        private static void validateIpv4(String literal) {
            String[] octets = literal.split("\\.", -1);
            if (octets.length != 4) {
                throw new IllegalArgumentException("IPv4 CIDR must contain four octets");
            }
            for (String octet : octets) {
                if (octet.isEmpty() || octet.length() > 3) {
                    throw new IllegalArgumentException("Invalid IPv4 CIDR octet");
                }
                int parsed = Integer.parseInt(octet);
                if (parsed > 255) {
                    throw new IllegalArgumentException("Invalid IPv4 CIDR octet");
                }
            }
        }
    }
}
