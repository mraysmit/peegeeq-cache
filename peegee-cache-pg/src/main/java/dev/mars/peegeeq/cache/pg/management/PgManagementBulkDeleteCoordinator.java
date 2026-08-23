package dev.mars.peegeeq.cache.pg.management;

import dev.mars.peegeeq.cache.api.management.*;
import io.vertx.core.Future;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local, digest-only, actor-bound bulk-delete preview storage and execution. */
final class PgManagementBulkDeleteCoordinator {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);
    private static final int TOKEN_BYTES = 32;

    private final PgManagementMutationRepository repository;
    private final String setupId;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, StoredPreview> previews = new ConcurrentHashMap<>();

    PgManagementBulkDeleteCoordinator(
            PgManagementMutationRepository repository,
            String setupId,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.setupId = Objects.requireNonNull(setupId, "setupId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    boolean containsTokenDigest(String token) {
        return previews.containsKey(digest(token));
    }

    boolean containsRawToken(String token) {
        return previews.containsKey(token);
    }

    Future<BulkDeletePreview> previewEntry(
            EntryDeleteFilter selection,
            ManagementActionContext context) {
        return repository.resolveEntryDelete(selection).map(resolved -> store(
                Resource.ENTRY,
                context.actor(),
                selection.namespace(),
                resolved,
                "DELETE " + selection.namespace()));
    }

    Future<BulkDeletePreview> previewCounter(
            CounterDeleteSelection selection,
            ManagementActionContext context) {
        PgBulkDeleteSelection resolved = new PgBulkDeleteSelection(
                selection.targets(), (long) selection.targets().size() * Long.BYTES);
        return Future.succeededFuture(store(
                Resource.COUNTER,
                context.actor(),
                null,
                resolved,
                "DELETE " + selection.targets().size() + " COUNTERS"));
    }

    Future<BulkDeleteResult> executeEntry(
            ConfirmedEntryDelete request,
            ManagementActionContext context) {
        try {
            StoredPreview preview = consume(
                    request.previewToken(), request.confirmationPhrase(), Resource.ENTRY,
                    request.namespace(), context);
            return execute(preview, true);
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    Future<BulkDeleteResult> executeCounter(
            ConfirmedCounterDelete request,
            ManagementActionContext context) {
        try {
            StoredPreview preview = consume(
                    request.previewToken(), request.confirmationPhrase(), Resource.COUNTER,
                    null, context);
            return execute(preview, false);
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
    }

    private BulkDeletePreview store(
            Resource resource,
            String actor,
            String namespace,
            PgBulkDeleteSelection resolved,
            String confirmationPhrase) {
        if (resolved.targets().size() > 10_000) {
            throw new ManagementBulkDeleteException(
                    ManagementBulkDeleteException.Code.SCOPE_TOO_LARGE);
        }
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expiresAt = clock.instant().plus(TOKEN_TTL);
        StoredPreview preview = new StoredPreview(
                resource,
                actor,
                setupId,
                namespace,
                expiresAt,
                confirmationPhrase,
                resolved.targets());
        previews.put(digest(token), preview);
        List<String> samples = resolved.targets().stream()
                .limit(20)
                .map(target -> target.key().key())
                .toList();
        return new BulkDeletePreview(
                token,
                expiresAt,
                setupId,
                namespace,
                resolved.targets().size(),
                resolved.totalBytes(),
                samples,
                confirmationPhrase);
    }

    private StoredPreview consume(
            String token,
            String confirmationPhrase,
            Resource resource,
            String namespace,
            ManagementActionContext context) {
        String digest = digest(token);
        StoredPreview preview = previews.get(digest);
        if (preview == null) {
            throw new ManagementBulkDeleteException(
                    ManagementBulkDeleteException.Code.TOKEN_INVALID);
        }
        if (preview.resource() != resource
                || !preview.setupId().equals(setupId)
                || !preview.actor().equals(context.actor())
                || !Objects.equals(preview.namespace(), namespace)) {
            throw new ManagementBulkDeleteException(
                    ManagementBulkDeleteException.Code.SCOPE_MISMATCH);
        }
        if (!clock.instant().isBefore(preview.expiresAt())) {
            previews.remove(digest, preview);
            throw new ManagementBulkDeleteException(
                    ManagementBulkDeleteException.Code.TOKEN_EXPIRED);
        }
        if (!preview.confirmationPhrase().equals(confirmationPhrase)) {
            throw new ManagementBulkDeleteException(
                    ManagementBulkDeleteException.Code.CONFIRMATION_MISMATCH);
        }
        if (!previews.remove(digest, preview)) {
            throw new ManagementBulkDeleteException(
                    ManagementBulkDeleteException.Code.TOKEN_INVALID);
        }
        return preview;
    }

    private Future<BulkDeleteResult> execute(StoredPreview preview, boolean entries) {
        Future<List<TargetResult>> chain = Future.succeededFuture(new ArrayList<>());
        for (VersionedCacheKeyTarget target : preview.targets()) {
            chain = chain.compose(results -> {
                Future<VersionedMutationResult<Void>> deletion = entries
                        ? repository.deleteEntry(new VersionedEntryDeleteRequest(
                        target.key(), target.version()))
                        : repository.deleteCounter(new VersionedCounterDeleteRequest(
                        target.key(), target.version()));
                return deletion.transform(outcome -> {
                    results.add(outcome.succeeded()
                            ? TargetResult.from(target, outcome.result().outcome())
                            : new TargetResult(target.key().key(), Result.FAILED));
                    return Future.succeededFuture(results);
                });
            });
        }
        return chain.map(PgManagementBulkDeleteCoordinator::summarize);
    }

    private static BulkDeleteResult summarize(List<TargetResult> results) {
        long deleted = results.stream().filter(result -> result.result() == Result.DELETED).count();
        long conflicts = results.stream().filter(result -> result.result() == Result.VERSION_CHANGED).count();
        long missing = results.stream().filter(result -> result.result() == Result.NOT_FOUND).count();
        long failed = results.stream().filter(result -> result.result() == Result.FAILED).count();
        List<BulkDeleteConflict> details = results.stream()
                .filter(result -> result.result() != Result.DELETED)
                .limit(1_000)
                .map(result -> new BulkDeleteConflict(
                        result.key(),
                        switch (result.result()) {
                            case VERSION_CHANGED -> BulkDeleteConflict.Reason.VERSION_CHANGED;
                            case NOT_FOUND -> BulkDeleteConflict.Reason.NOT_FOUND;
                            case FAILED -> BulkDeleteConflict.Reason.FAILED;
                            case DELETED -> throw new IllegalStateException("deleted target is not a conflict");
                        }))
                .toList();
        return new BulkDeleteResult(
                results.size(), deleted, conflicts, missing, failed, details);
    }

    private static String digest(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private enum Resource { ENTRY, COUNTER }
    private enum Result { DELETED, VERSION_CHANGED, NOT_FOUND, FAILED }

    private record StoredPreview(
            Resource resource,
            String actor,
            String setupId,
            String namespace,
            Instant expiresAt,
            String confirmationPhrase,
            List<VersionedCacheKeyTarget> targets) {
        private StoredPreview {
            targets = List.copyOf(targets);
        }
    }

    private record TargetResult(String key, Result result) {
        private static TargetResult from(
                VersionedCacheKeyTarget target,
                ManagementMutationOutcome outcome) {
            return new TargetResult(target.key().key(), switch (outcome) {
                case APPLIED -> Result.DELETED;
                case VERSION_MISMATCH, CONDITION_NOT_MET -> Result.VERSION_CHANGED;
                case NOT_FOUND -> Result.NOT_FOUND;
            });
        }
    }
}
