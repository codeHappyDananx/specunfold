package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Each execution is locked while updating; replacement becomes visible as one filesystem operation. */
final class WorkflowStateStore {
    private final ObjectMapper mapper = new ObjectMapper();

    Lock lock(Path workspace, String profile, String executionId) throws IOException {
        Path directory = directory(workspace, profile);
        Path lockPath = directory.resolve(identifier(executionId) + ".lock");
        rejectLink(lockPath);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { return new Lock(channel, channel.lock()); }
        catch (IOException | RuntimeException e) { channel.close(); throw e; }
    }

    ObjectNode load(Path workspace, String profile, String executionId) throws IOException {
        Path path = file(workspace, profile, executionId);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Execution does not exist: " + executionId);
        if (!path.toRealPath().startsWith(workspace)) throw new IllegalArgumentException("Execution state escapes workspace");
        com.fasterxml.jackson.databind.JsonNode state = mapper.readTree(Files.readAllBytes(path));
        if (state == null || !state.isObject()) throw new IllegalStateException("Execution state must be a JSON object");
        return (ObjectNode) state;
    }

    ArrayNode activeExecutions(Path workspace, String profile) throws IOException {
        ArrayNode result = mapper.createArrayNode();
        Path directory = workspace.resolve(".specunfold/workflow-state").resolve(identifier(profile));
        Path existing = directory;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (!existing.toRealPath().startsWith(workspace)) throw new IllegalArgumentException("State directory escapes workspace");
        if (!Files.exists(directory)) return result;
        List<Path> paths;
        try (Stream<Path> files = Files.list(directory)) {
            paths = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted().collect(Collectors.toList());
        }
        for (Path path : paths) {
            String name = path.getFileName().toString();
            String id = identifier(name.substring(0, name.length() - 5));
            ObjectNode state = load(workspace, profile, id);
            if (!id.equals(state.path("executionId").asText()) || !profile.equals(state.path("profile").asText())) {
                throw new IllegalStateException("Execution state identity mismatch: " + id);
            }
            if ("DONE".equals(state.path("status").asText())) continue;
            ObjectNode candidate = result.addObject();
            for (String field : new String[] {"executionId", "workflow", "currentNodeId", "status", "intent", "createdAt"}) {
                JsonNode value = state.get(field);
                if (value != null) candidate.set(field, value.deepCopy());
            }
        }
        return result;
    }

    void save(Path workspace, String profile, String executionId, ObjectNode state) throws IOException {
        Path target = file(workspace, profile, executionId);
        Path temporary = Files.createTempFile(target.getParent(), executionId + "-", ".tmp");
        try {
            Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state));
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path file(Path workspace, String profile, String executionId) throws IOException {
        Path path = directory(workspace, profile).resolve(identifier(executionId) + ".json");
        rejectLink(path);
        return path;
    }

    private Path directory(Path workspace, String profile) throws IOException {
        Path directory = workspace.resolve(".specunfold/workflow-state").resolve(identifier(profile));
        Path ancestor = directory;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(workspace)) throw new IllegalArgumentException("State directory escapes workspace");
        Files.createDirectories(directory);
        if (!directory.toRealPath().startsWith(workspace)) throw new IllegalArgumentException("State directory escapes workspace");
        return directory;
    }

    private void rejectLink(Path path) {
        if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("State or lock file cannot be a symbolic link");
    }

    private String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid profile or execution identifier: " + value);
        }
        return value;
    }

    static final class Lock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        Lock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        @Override public void close() throws IOException {
            try { lock.release(); } finally { channel.close(); }
        }
    }
}
