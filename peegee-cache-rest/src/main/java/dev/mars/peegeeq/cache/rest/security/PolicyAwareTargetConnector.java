package dev.mars.peegeeq.cache.rest.security;

import io.vertx.core.Future;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

/** Resolves, validates, selects, and connects without allowing a second resolver decision. */
public final class PolicyAwareTargetConnector {

    private final SetupTargetPolicy policy;
    private final TargetAddressResolver resolver;
    private final PinnedDatabaseConnector connector;

    public PolicyAwareTargetConnector(
            SetupTargetPolicy policy,
            TargetAddressResolver resolver,
            PinnedDatabaseConnector connector) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.connector = Objects.requireNonNull(connector, "connector");
    }

    public Future<Void> connect(SetupTarget target) throws Exception {
        Objects.requireNonNull(target, "target");
        List<InetAddress> answers = List.copyOf(resolver.resolve(target.hostname()));
        policy.validate(target.hostname(), target.port(), answers, target.trustProfileId());
        InetAddress pinnedAddress = answers.getFirst();
        return connector.connect(
                pinnedAddress,
                target.port(),
                target.hostname(),
                target.trustProfileId(),
                target.tlsMode());
    }
}
