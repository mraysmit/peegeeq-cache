package dev.mars.peegeeq.cache.benchmark;

import java.time.Duration;

/** Explicit resource/time budgets for recorder calibration, not deployment performance targets. */
public record BenchmarkCalibrationConfig(int concurrency, int bucketCount, Duration warmup,
        Duration measurement, Duration window, int checkpointWindows, long maximumEvidenceBytes) {
    public record Window(String phase, long durationNanos) { }

    public BenchmarkCalibrationConfig {
        try {
            if (concurrency < 1 || concurrency > 64 || bucketCount < 1 || bucketCount > 65_536
                    || checkpointWindows < 1 || checkpointWindows > 128 || maximumEvidenceBytes < 131_072
                    || warmup.isNegative() || measurement.isZero() || measurement.isNegative()
                    || window.toNanos() < 1_000_000) throw new IllegalArgumentException("Invalid calibration resource/time budget");
            Math.multiplyExact(2, Math.addExact(warmup.toNanos(), measurement.toNanos()));
            Math.multiplyExact(2, window.toNanos());
            Math.toIntExact(Math.multiplyExact(65_536L + bucketCount * 200L + concurrency * 2048L, checkpointWindows));
        } catch (ArithmeticException invalid) { throw new IllegalArgumentException("Calibration budget overflows", invalid); }
    }

    public long windowCount() { return windows(warmup.toNanos()) + windows(measurement.toNanos()); }
    private long windows(long nanos) { return nanos == 0 ? 0 : 1 + (nanos - 1) / window.toNanos(); }

    public Window windowAt(long index) {
        if (index < 0 || index >= windowCount()) throw new IndexOutOfBoundsException("Calibration window " + index);
        long warmupWindows = windows(warmup.toNanos());
        boolean warming = index < warmupWindows;
        long local = warming ? index : index - warmupWindows;
        long phaseNanos = warming ? warmup.toNanos() : measurement.toNanos();
        return new Window(warming ? "warmup" : "observe", Math.min(window.toNanos(), phaseNanos - local * window.toNanos()));
    }

    public BenchmarkCheckpointWriter.Limits writerLimits() {
        return new BenchmarkCheckpointWriter.Limits(checkpointWindows + 2,
                Math.toIntExact((65_536L + bucketCount * 200L + concurrency * 2048L) * checkpointWindows), 2);
    }
}
