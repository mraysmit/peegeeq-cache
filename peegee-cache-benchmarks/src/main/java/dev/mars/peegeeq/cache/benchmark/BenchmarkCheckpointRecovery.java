package dev.mars.peegeeq.cache.benchmark;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static dev.mars.peegeeq.cache.benchmark.BenchmarkRunEvidence.*;

/**
 * Read-only structural/metadata inspection, off event loops. Histories are streamed, not retained.
 * UNFINALISED is not proof of a crash: establish owner liveness separately. Never repairs, resumes,
 * deletes or rewrites evidence. This is not full measurement/accounting/analysis validation.
 */
public final class BenchmarkCheckpointRecovery {
    public enum Classification { FINALISED, UNFINALISED }
    public record Inspection(UUID executionId, Status status, Validity validity, Instant checkpointAt,
                             Classification classification, long measurementCount, long diagnosticCount) {
        public boolean requiresOwnerReview() { return classification == Classification.UNFINALISED; }
    }
    private record Execution(Status status, Validity validity, Instant checkpointAt) { }

    private BenchmarkCheckpointRecovery() { }

    public static Inspection inspect(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Evidence must be a regular file");
        var factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(65_536).maxNestingDepth(64).build()).build();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try (var input = Files.newInputStream(path); var parser = factory.createParser(input)) {
            require(parser.nextToken() == JsonToken.START_OBJECT, "Expected evidence object");
            Integer version = null;
            UUID executionId = null;
            Execution execution = null;
            Long measurements = null;
            Long diagnostics = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.FIELD_NAME, "Expected evidence field");
                String field = parser.currentName();
                require(parser.nextToken() != null, "Truncated evidence field");
                switch (field) {
                    case "schemaVersion" -> {
                        require(parser.currentToken() == JsonToken.VALUE_NUMBER_INT, "Expected integer schemaVersion");
                        version = parser.getIntValue();
                    }
                    case "executionId" -> executionId = UUID.fromString(string(parser));
                    case "execution" -> execution = execution(parser);
                    case "measurements" -> measurements = countObjects(parser);
                    case "diagnostics" -> diagnostics = countObjects(parser);
                    default -> parser.skipChildren();
                }
            }
            require(parser.nextToken() == null, "Trailing content after evidence");
            require(version != null && version == 1, "Missing or unsupported schemaVersion");
            require(executionId != null && execution != null && measurements != null && diagnostics != null,
                    "Missing recovery metadata or histories");
            return new Inspection(executionId, execution.status(), execution.validity(), execution.checkpointAt(),
                    execution.status() == Status.RUNNING ? Classification.UNFINALISED : Classification.FINALISED,
                    measurements, diagnostics);
        } catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException invalid) {
            throw new IOException("Invalid checkpoint recovery metadata", invalid);
        }
    }

    private static Execution execution(JsonParser parser) throws IOException {
        require(parser.currentToken() == JsonToken.START_OBJECT, "Expected execution object");
        Status status = null;
        Validity validity = null;
        Boolean finalised = null;
        Instant started = null;
        Instant checkpoint = null;
        String detail = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            require(parser.currentToken() == JsonToken.FIELD_NAME, "Expected execution field");
            String field = parser.currentName();
            require(parser.nextToken() != null, "Truncated execution field");
            switch (field) {
                case "status" -> status = Status.valueOf(string(parser));
                case "measurementValidity" -> validity = Validity.valueOf(string(parser));
                case "startedAtUtc" -> started = Instant.parse(string(parser));
                case "checkpointAtUtc" -> checkpoint = Instant.parse(string(parser));
                case "detail" -> detail = string(parser);
                case "finalised" -> {
                    require(parser.currentToken() == JsonToken.VALUE_TRUE || parser.currentToken() == JsonToken.VALUE_FALSE,
                            "Expected boolean finalised");
                    finalised = parser.getBooleanValue();
                }
                default -> parser.skipChildren();
            }
        }
        require(status != null && validity != null && started != null && checkpoint != null && finalised != null && detail != null,
                "Incomplete execution metadata");
        require(finalised == (status != Status.RUNNING), "Contradictory terminal status");
        require(!checkpoint.isBefore(started), "Checkpoint precedes execution start");
        require(!((status == Status.FAILED || status == Status.STOPPED || validity == Validity.INVALID) && detail.isBlank()),
                "Terminal failure/stop or invalid evidence needs a reason");
        return new Execution(status, validity, checkpoint);
    }

    private static long countObjects(JsonParser parser) throws IOException {
        require(parser.currentToken() == JsonToken.START_ARRAY, "Expected history array");
        long count = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            require(parser.currentToken() == JsonToken.START_OBJECT, "Expected history record object");
            parser.skipChildren();
            count = Math.incrementExact(count);
        }
        return count;
    }

    private static String string(JsonParser parser) throws IOException {
        require(parser.currentToken() == JsonToken.VALUE_STRING, "Expected metadata string");
        return parser.getText();
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }
}
