package dev.mars.peegeeq.cache.benchmark;

import java.util.ArrayList;
import java.util.List;

/** Preflight budget for the selected atomic growing-JSON strategy. */
public record BenchmarkPersistencePolicy(int version, int intervalsPerCheckpoint,
                                         long maximumFinalBytes, long maximumCumulativeWriteBytes,
                                         long minimumFreeDiskBytes) {
    public record Assessment(boolean supported, long checkpoints, long estimatedFinalBytes,
                             long estimatedCumulativeWriteBytes, long estimatedPeakDiskBytes,
                             List<String> reasons) {
        public Assessment { reasons = List.copyOf(reasons); }
    }

    public BenchmarkPersistencePolicy {
        if (version != 1 || intervalsPerCheckpoint <= 0 || maximumFinalBytes <= 0
                || maximumCumulativeWriteBytes <= 0 || minimumFreeDiskBytes <= 0) {
            throw new IllegalArgumentException("Persistence version and budgets must be positive");
        }
    }

    /**
     * Models initial publication plus equal-sized checkpoint batches. This intentionally exposes
     * growing-copy cost and rejects unsupported soaks before launch instead of hiding write pressure.
     */
    public Assessment assess(long intervalCount, long estimatedBytesPerInterval, long metadataBytes) {
        var reasons = new ArrayList<String>();
        if (intervalCount < 0 || estimatedBytesPerInterval <= 0 || metadataBytes <= 0) {
            throw new IllegalArgumentException("Persistence estimates must be non-negative/positive");
        }
        try {
            long checkpoints = intervalCount == 0 ? 0 : 1 + (intervalCount - 1) / intervalsPerCheckpoint;
            long finalBytes = Math.addExact(metadataBytes, Math.multiplyExact(intervalCount, estimatedBytesPerInterval));
            long batchBytes = Math.multiplyExact((long) intervalsPerCheckpoint, estimatedBytesPerInterval);
            long triangular = Math.multiplyExact(checkpoints, Math.addExact(checkpoints, 1)) / 2;
            long writes = Math.addExact(metadataBytes,
                    Math.addExact(Math.multiplyExact(checkpoints, metadataBytes), Math.multiplyExact(triangular, batchBytes)));
            // A final partial batch makes the equal-batch model conservative; cap the last overestimate at final size.
            if (intervalCount % intervalsPerCheckpoint != 0 && checkpoints > 0) {
                writes = Math.subtractExact(writes,
                        Math.multiplyExact((long) intervalsPerCheckpoint - intervalCount % intervalsPerCheckpoint,
                                estimatedBytesPerInterval));
            }
            long peak = Math.multiplyExact(finalBytes, 2);
            if (finalBytes > maximumFinalBytes) reasons.add("estimated final evidence exceeds configured byte budget");
            if (writes > maximumCumulativeWriteBytes) reasons.add("estimated growing-copy rewrite work exceeds budget");
            if (peak > minimumFreeDiskBytes) reasons.add("estimated atomic temporary disk requirement exceeds budget");
            return new Assessment(reasons.isEmpty(), checkpoints, finalBytes, writes, peak, reasons);
        } catch (ArithmeticException overflow) {
            return new Assessment(false, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                    List.of("persistence estimate exceeds signed 64-bit range"));
        }
    }
}
