package dev.mars.peegeeq.cache.benchmark;

/**
 * Cumulative logical-request accounting for one run/scenario, not interval-local counts.
 * Rejection occurs before admission; expiry-before-start occurs after admission. Success,
 * failure and timeout are mutually exclusive terminal outcomes of started requests.
 * A late physical completion does not reclassify a timed-out logical request.
 */
public record BenchmarkWorkCounts(long scheduled, long admitted, long started,
                                  long succeeded, long failed, long timedOut,
                                  long rejected, long expiredBeforeStart) {
    public BenchmarkWorkCounts {
        if (scheduled < 0 || admitted < 0 || started < 0 || succeeded < 0 || failed < 0
                || timedOut < 0 || rejected < 0 || expiredBeforeStart < 0) {
            throw new IllegalArgumentException("Work counters must not be negative");
        }
        // Subtract from the owning population to reject overflow as well as over-counting.
        if (admitted > scheduled || rejected > scheduled - admitted
                || started > admitted || expiredBeforeStart > admitted - started
                || succeeded > started || failed > started - succeeded
                || timedOut > started - succeeded - failed) {
            throw new IllegalArgumentException("Work counters violate request conservation");
        }
    }

    public static BenchmarkWorkCounts zero() { return new BenchmarkWorkCounts(0, 0, 0, 0, 0, 0, 0, 0); }
    public long pendingAdmission() { return scheduled - admitted - rejected; }
    public long queued() { return admitted - started - expiredBeforeStart; }
    public long completed() { return succeeded + failed + timedOut; }
    public long inFlight() { return started - completed(); }
    public long outstanding() { return scheduled - rejected - expiredBeforeStart - completed(); }

    boolean follows(BenchmarkWorkCounts before) {
        return scheduled >= before.scheduled && admitted >= before.admitted && started >= before.started
                && succeeded >= before.succeeded && failed >= before.failed && timedOut >= before.timedOut
                && rejected >= before.rejected && expiredBeforeStart >= before.expiredBeforeStart;
    }
}
