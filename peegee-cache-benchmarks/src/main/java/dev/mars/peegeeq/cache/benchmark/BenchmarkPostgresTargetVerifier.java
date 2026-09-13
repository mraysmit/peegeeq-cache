package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;

import java.util.Objects;

/** Read-only PostgreSQL identity/TLS verification; it never closes or mutates the supplied pool. */
public final class BenchmarkPostgresTargetVerifier {
    private BenchmarkPostgresTargetVerifier() { }

    public static Future<BenchmarkCampaignRunner.TargetIdentity> verify(
            Pool pool, BenchmarkDeploymentTarget target) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(target, "target");
        return pool.query("SELECT current_database(), current_setting('server_version'), "
                        + "COALESCE(inet_server_addr()::text, 'local'), "
                        + "EXISTS (SELECT 1 FROM pg_stat_ssl WHERE pid = pg_backend_pid() AND ssl)")
                .execute().compose(rows -> {
                    var iterator = rows.iterator();
                    if (!iterator.hasNext()) return Future.failedFuture("PostgreSQL identity query returned no row");
                    var row = iterator.next();
                    String database = row.getString(0);
                    String version = row.getString(1);
                    String address = row.getString(2);
                    boolean tls = row.getBoolean(3);
                    if (target.kind() == BenchmarkDeploymentTarget.Kind.EXTERNAL
                            && !target.database().equals(database)) {
                        return Future.failedFuture("Connected database identity does not match explicit target");
                    }
                    if (target.tlsRequired() && !tls) {
                        return Future.failedFuture("Connected PostgreSQL session did not verify required TLS");
                    }
                    String endpoint = target.kind() == BenchmarkDeploymentTarget.Kind.EXTERNAL
                            ? target.host() + ":" + target.port() + "/" + database : address + "/" + database;
                    return Future.succeededFuture(new BenchmarkCampaignRunner.TargetIdentity(
                            target.id(), "PostgreSQL " + version, endpoint, tls));
                });
    }
}
