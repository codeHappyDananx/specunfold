package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowStateStoreTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test void resumeCandidatesExcludeCompletedStateAndKeepPayloadCompact() throws Exception {
        Path workspace = directory.toRealPath();
        WorkflowStateStore store = new WorkflowStateStore();
        assertTrue(store.activeExecutions(workspace, "demo").isEmpty());
        assertFalse(Files.exists(workspace.resolve(".specunfold")));
        ObjectNode active = json.createObjectNode().put("executionId", "active").put("profile", "demo")
                .put("workflow", "delivery").put("status", "BLOCKED").put("currentNodeId", "prepare");
        active.putObject("facts").put("large", "not in candidate");
        active.putArray("history").add("not in candidate");
        store.save(workspace, "demo", "active", active);
        store.save(workspace, "demo", "done", active.deepCopy().put("executionId", "done").put("status", "DONE"));
        ArrayNode candidates = store.activeExecutions(workspace, "demo");
        assertEquals(1, candidates.size());
        assertEquals("active", candidates.get(0).path("executionId").asText());
        assertFalse(candidates.get(0).has("facts"));
        assertFalse(candidates.get(0).has("history"));
        assertTrue(store.load(workspace, "demo", "active").has("facts"));
    }

    @Test void corruptIdentityCannotBeSelectedForResume() throws Exception {
        Path workspace = directory.toRealPath();
        WorkflowStateStore store = new WorkflowStateStore();
        store.save(workspace, "demo", "active", json.createObjectNode().put("executionId", "other").put("profile", "demo"));
        assertThrows(IllegalStateException.class, () -> store.activeExecutions(workspace, "demo"));
    }
}
