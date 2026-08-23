package dev.mars.peegeeq.cache.rest.security;

import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrustedProxyAuthenticatorTest {

    private final TrustedProxyAuthenticator authenticator = new TrustedProxyAuthenticator(
            TrustedProxyAuthenticationConfig.defaults(Set.of("10.24.0.0/16", "2001:db8:42::/48")));

    @Test
    void acceptsAndNormalizesIdentityOnlyFromConfiguredProxyCidr() throws Exception {
        MultiMap headers = identityHeaders("  Alex.Chen  ", " OPERATOR, viewer ");

        AuthenticatedManagementIdentity identity = authenticator.authenticate(
                InetAddress.getByName("10.24.9.31"), headers);

        assertEquals("Alex.Chen", identity.actor());
        assertEquals(Set.of("operator", "viewer"), identity.roles());
        assertEquals("10.24.9.31", identity.sourceAddress());
    }

    @Test
    void acceptsConfiguredIpv6ProxyCidr() throws Exception {
        AuthenticatedManagementIdentity identity = authenticator.authenticate(
                InetAddress.getByName("2001:db8:42::17"),
                identityHeaders("ipv6-operator", "viewer"));

        assertEquals("ipv6-operator", identity.actor());
    }

    @Test
    void rejectsIdentityHeadersFromUntrustedPeer() throws Exception {
        ManagementAuthenticationException failure = assertThrows(
                ManagementAuthenticationException.class,
                () -> authenticator.authenticate(
                        InetAddress.getByName("10.25.9.31"),
                        identityHeaders("attacker", "operator")));

        assertEquals("UNTRUSTED_IDENTITY_SOURCE", failure.code());
        assertEquals(401, failure.status());
    }

    @Test
    void rejectsMissingIdentityWithoutLeakingPartialValues() throws Exception {
        ManagementAuthenticationException noHeaders = assertThrows(
                ManagementAuthenticationException.class,
                () -> authenticator.authenticate(
                        InetAddress.getByName("10.24.1.1"), MultiMap.caseInsensitiveMultiMap()));
        ManagementAuthenticationException missingRoles = assertThrows(
                ManagementAuthenticationException.class,
                () -> authenticator.authenticate(
                        InetAddress.getByName("10.24.1.1"),
                        MultiMap.caseInsensitiveMultiMap().add("X-PeeGeeQ-User", "partial-secret-user")));

        assertEquals("AUTHENTICATION_REQUIRED", noHeaders.code());
        assertEquals("INVALID_IDENTITY", missingRoles.code());
        assertEquals("Invalid trusted identity headers", missingRoles.getMessage());
    }

    @Test
    void rejectsDuplicateIdentityHeaders() throws Exception {
        MultiMap duplicateUser = identityHeaders("first", "viewer")
                .add("x-peegeeq-user", "second");
        MultiMap duplicateRoles = identityHeaders("first", "viewer")
                .add("x-peegeeq-roles", "operator");

        assertInvalidIdentity(duplicateUser);
        assertInvalidIdentity(duplicateRoles);
    }

    @Test
    void rejectsMalformedOversizedAndUnknownRoleValues() throws Exception {
        assertInvalidIdentity(identityHeaders("control\u0007character", "viewer"));
        assertInvalidIdentity(identityHeaders("x".repeat(129), "viewer"));
        assertInvalidIdentity(identityHeaders("valid-user", "viewer,,operator"));
        assertInvalidIdentity(identityHeaders("valid-user", "viewer,viewer"));
        assertInvalidIdentity(identityHeaders("valid-user", "viewer,super-admin"));
        assertInvalidIdentity(identityHeaders("valid-user", "v".repeat(257)));
        assertInvalidIdentity(identityHeaders(
                "valid-user",
                "r1,r2,r3,r4,r5,r6,r7,r8,r9,r10,r11,r12,r13,r14,r15,r16,r17"));
    }

    @Test
    void ignoresRoleLikeDataOutsideConfiguredAuthoritativeHeaders() throws Exception {
        MultiMap headers = identityHeaders("viewer-only", "viewer")
                .add("role", "operator")
                .add("X-Body-Roles", "operator")
                .add("X-Query-Roles", "operator");

        AuthenticatedManagementIdentity identity = authenticator.authenticate(
                InetAddress.getByName("10.24.4.5"), headers);

        assertEquals(Set.of("viewer"), identity.roles());
    }

    private void assertInvalidIdentity(MultiMap headers) throws Exception {
        ManagementAuthenticationException failure = assertThrows(
                ManagementAuthenticationException.class,
                () -> authenticator.authenticate(InetAddress.getByName("10.24.1.1"), headers));
        assertEquals("INVALID_IDENTITY", failure.code());
        assertEquals("Invalid trusted identity headers", failure.getMessage());
    }

    private static MultiMap identityHeaders(String user, String roles) {
        return MultiMap.caseInsensitiveMultiMap()
                .add("X-PeeGeeQ-User", user)
                .add("X-PeeGeeQ-Roles", roles);
    }
}
