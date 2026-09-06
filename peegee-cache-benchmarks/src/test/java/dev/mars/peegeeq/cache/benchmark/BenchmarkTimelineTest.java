package dev.mars.peegeeq.cache.benchmark;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static dev.mars.peegeeq.cache.benchmark.BenchmarkTimeline.PhaseKind.*;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkTimelineTest {
    @Test
    void assignsBoundaryEventsToTheNextHalfOpenWindowWithoutMixingPhases() {
        var timeline = new BenchmarkTimeline(List.of(
                phase("warm-up", WARMUP, 3), phase("baseline", BASELINE, 5),
                phase("peak", LOAD, 4), phase("reduced", RECOVERY, 2)), Duration.ofSeconds(2));

        assertEquals(8, timeline.windowCount());
        assertEquals(Duration.ofSeconds(14), timeline.duration());
        assertWindow(timeline, 0, 0, "warm-up", 0, 2, false);
        assertWindow(timeline, 2, 1, "warm-up", 2, 3, true);
        assertWindow(timeline, 3, 2, "baseline", 3, 5, false);
        assertWindow(timeline, 7, 4, "baseline", 7, 8, true);
        assertWindow(timeline, 8, 5, "peak", 8, 10, false);
        assertWindow(timeline, 12, 7, "reduced", 12, 14, false);
        assertTrue(timeline.windowAt(Duration.ofSeconds(14).toNanos()).isEmpty());
        assertTrue(timeline.windowAt(Long.MAX_VALUE).isEmpty());
    }

    @Test
    void retainsWarmupAndTransitionClassificationForAnalysis() {
        var timeline = new BenchmarkTimeline(List.of(
                phase("warm", WARMUP, 1), phase("ramp", TRANSITION, 1),
                phase("soak", SUSTAINED, 1), phase("drain", DRAIN, 1)), Duration.ofSeconds(5));
        assertEquals(WARMUP, timeline.windowAt(0).orElseThrow().phase().kind());
        assertEquals(TRANSITION, timeline.windowAt(1_000_000_000).orElseThrow().phase().kind());
        assertEquals(SUSTAINED, timeline.windowAt(2_000_000_000).orElseThrow().phase().kind());
        assertEquals(DRAIN, timeline.windowAt(3_000_000_000L).orElseThrow().phase().kind());
        assertTrue(timeline.windowAt(0).orElseThrow().partial());
    }

    @Test
    void freezesThePhaseSchedule() {
        var phases = new ArrayList<>(List.of(phase("load", LOAD, 1)));
        var timeline = new BenchmarkTimeline(phases, Duration.ofMillis(250));
        phases.clear();
        assertEquals(4, timeline.windowCount());
        assertEquals("load", timeline.windowAt(0).orElseThrow().phase().name());
        assertThrows(UnsupportedOperationException.class, () -> timeline.phases().clear());
    }

    @Test
    void handlesNanosecondWindowsAndLargeSchedulesWithoutMaterialisingEveryWindow() {
        var timeline = new BenchmarkTimeline(List.of(
                new BenchmarkTimeline.Phase("long soak", SUSTAINED, Duration.ofNanos(Long.MAX_VALUE))),
                Duration.ofNanos(2));
        assertEquals(Long.MAX_VALUE / 2 + 1, timeline.windowCount());
        var last = timeline.windowAt(Long.MAX_VALUE - 1).orElseThrow();
        assertEquals(Long.MAX_VALUE - 1, last.startNanos());
        assertEquals(Long.MAX_VALUE, last.endNanos());
        assertTrue(last.partial());
    }

    @Test
    void rejectsAmbiguousOrUnrepresentableSchedules() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkTimeline(List.of(), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkTimeline(
                List.of(phase("load", LOAD, 1)), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkTimeline(
                List.of(phase("load", LOAD, 1)), Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> phase("load", LOAD, 0));
        assertThrows(IllegalArgumentException.class, () -> phase(" ", LOAD, 1));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkTimeline(
                List.of(phase("load", LOAD, 1), phase("load", RECOVERY, 1)), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkTimeline(
                List.of(phase("huge", LOAD, Long.MAX_VALUE)), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkTimeline(List.of(
                new BenchmarkTimeline.Phase("a", LOAD, Duration.ofNanos(Long.MAX_VALUE)),
                phase("b", LOAD, 1)), Duration.ofNanos(1)));
        var timeline = new BenchmarkTimeline(List.of(phase("a", LOAD, 1)), Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> timeline.windowAt(-1));
    }

    private static BenchmarkTimeline.Phase phase(String name, BenchmarkTimeline.PhaseKind kind, long seconds) {
        return new BenchmarkTimeline.Phase(name, kind, Duration.ofSeconds(seconds));
    }

    private static void assertWindow(BenchmarkTimeline timeline, long eventSeconds, long index,
                                     String phase, long startSeconds, long endSeconds, boolean partial) {
        var window = timeline.windowAt(Duration.ofSeconds(eventSeconds).toNanos()).orElseThrow();
        assertEquals(index, window.index());
        assertEquals(phase, window.phase().name());
        assertEquals(Duration.ofSeconds(startSeconds).toNanos(), window.startNanos());
        assertEquals(Duration.ofSeconds(endSeconds).toNanos(), window.endNanos());
        assertEquals(partial, window.partial());
    }
}
