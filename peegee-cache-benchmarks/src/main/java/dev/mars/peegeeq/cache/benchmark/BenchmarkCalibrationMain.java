package dev.mars.peegeeq.cache.benchmark;

import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import java.nio.file.Path;
import java.time.Duration;

/** One JVM invocation is one calibration fork; use the JVM's -Xmx flag for heap experiments. */
public final class BenchmarkCalibrationMain {
    private BenchmarkCalibrationMain() { }

    public static void main(String[] args) {
        BenchmarkCalibrationConfig config;
        Path output;
        int fork;
        try {
            if (args.length != 9) throw new IllegalArgumentException("Expected nine arguments");
            output = Path.of(args[0]);
            config = new BenchmarkCalibrationConfig(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                    Duration.ofMillis(Long.parseLong(args[3])), Duration.ofMillis(Long.parseLong(args[4])),
                    Duration.ofMillis(Long.parseLong(args[5])), Integer.parseInt(args[6]), Long.parseLong(args[7]));
            fork = Integer.parseInt(args[8]);
            if (fork < 0) throw new IllegalArgumentException("forkIndex must not be negative");
        } catch (RuntimeException invalid) {
            System.err.println("Usage: BenchmarkCalibrationMain outputDirectory concurrency bucketCount warmupMillis measurementMillis windowMillis checkpointWindows maximumEvidenceBytes forkIndex");
            System.err.println(invalid.getMessage());
            System.exit(2);
            return;
        }
        var vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        // Process completion must not retain the context whose event loop is being shut down.
        var completion = Promise.<Path>promise();
        BenchmarkRecorderCalibration.run(vertx, output, config, fork).onComplete(result -> vertx.close().onComplete(closed -> {
            if (result.failed()) {
                if (closed.failed() && closed.cause() != result.cause()) result.cause().addSuppressed(closed.cause());
                completion.fail(result.cause());
            } else if (closed.failed()) {
                completion.fail(closed.cause());
            } else {
                completion.complete(result.result());
            }
        }));
        completion.future().onSuccess(path -> {
            System.out.println("CALIBRATION_JSON=" + path.toAbsolutePath());
            System.exit(0);
        }).onFailure(failure -> {
            failure.printStackTrace(System.err);
            System.exit(1);
        });
    }
}
