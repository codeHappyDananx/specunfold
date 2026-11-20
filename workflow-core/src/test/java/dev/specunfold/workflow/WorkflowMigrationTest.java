package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowMigrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;
    private Path home, workspace, profile;

    @Test void firstWorkflowNeedsUserConfirmationAndResumptionDoesNotAskAgain() throws Exception {
        fixture();
        WorkflowService service = new WorkflowService(home);
        ObjectNode request = args("bootstrap");
        request.remove("workflowConfirmedByUser"); request.remove("workflowConfirmationSource");
        assertThrows(IllegalArgumentException.class, () -> service.handle(request));
        assertFalse(Files.exists(workspace.resolve(".specunfold")));
        request.put("workflowConfirmedByUser", true).put("workflowConfirmationSource", "agent_assumption");
        assertThrows(IllegalArgumentException.class, () -> service.handle(request));
        assertFalse(Files.exists(workspace.resolve(".specunfold")));
        request.put("workflowConfirmationSource", "user_confirmed_proposal");
        JsonNode first = service.handle(request);
        ObjectNode resume = current("bootstrap", first);
        resume.remove("workflowConfirmedByUser"); resume.remove("workflowConfirmationSource");
        assertEquals(first.path("executionId"), new WorkflowService(home).handle(resume).path("executionId"));
    }

    @Test void startupIsStatelessAndOnlyOneExecutionCanResumeAutomatically() throws Exception {
        fixture();
        WorkflowService service = new WorkflowService(home);
        JsonNode idle = service.handle(args("context"));
        assertEquals("IDLE", idle.path("status").asText());
        assertFalse(Files.exists(workspace.resolve(".specunfold")));
        JsonNode first = service.handle(args("bootstrap"));
        JsonNode resumed = new WorkflowService(home).handle(args("context"));
        assertEquals(first.path("executionId"), resumed.path("executionId"));
        assertEquals("producer", resumed.path("nodeId").asText());
        JsonNode second = service.handle(args("bootstrap"));
        JsonNode selection = new WorkflowService(home).handle(args("context"));
        assertEquals("SELECT_EXECUTION", selection.path("status").asText());
        assertFalse(selection.has("executionId"));
        assertEquals(2, selection.path("executions").size());
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (JsonNode candidate : selection.path("executions")) ids.add(candidate.path("executionId").asText());
        assertTrue(ids.contains(first.path("executionId").asText()));
        assertTrue(ids.contains(second.path("executionId").asText()));
    }

    @Test void currentStateOnlyAdvancesRevisionWhenEffectiveStateChangesAndSurvivesRestart() throws Exception {
        fixture();
        WorkflowService service = new WorkflowService(home);
        ObjectNode start = args("bootstrap");
        ObjectNode supplied = start.putObject("currentState");
        supplied.put("goal", "Create a checked output").put("design", "one file");
        supplied.putArray("constraints").add("workspace only");
        supplied.putObject("context").put("language", "text");
        supplied.putArray("acceptance").add("output exists");
        JsonNode first = service.handle(start);
        JsonNode snapshot = first.path("currentState");
        assertEquals("Create a checked output", snapshot.path("goal").asText());
        assertEquals("one file", snapshot.path("design").asText());
        assertEquals(supplied.path("constraints"), snapshot.path("constraints"));
        assertEquals(supplied.path("context"), snapshot.path("context"));
        assertEquals(supplied.path("acceptance"), snapshot.path("acceptance"));
        int revision = snapshot.path("revision").intValue();
        ObjectNode same = current("context", first); same.set("currentState", supplied.deepCopy());
        assertEquals(revision, service.handle(same).path("currentState").path("revision").intValue());
        ObjectNode changed = current("context", first); changed.putObject("currentState").put("design", "two files");
        JsonNode updated = service.handle(changed);
        assertEquals(revision + 1, updated.path("currentState").path("revision").intValue());
        JsonNode restored = new WorkflowService(home).handle(current("context", updated));
        assertEquals(updated.path("currentState"), restored.path("currentState"));
        assertEquals("Create a checked output", restored.path("currentState").path("goal").asText());
    }

    @Test void compactExecutionPackageRunsDirectlyAndStateHistoryIsExplicit() throws Exception {
        fixture();
        WorkflowService service = new WorkflowService(home);
        JsonNode first = service.handle(args("bootstrap"));
        compact(first);
        Files.write(workspace.resolve("output.txt"), "output".getBytes(StandardCharsets.UTF_8));
        ObjectNode run = submission(first, "ready");
        run.putArray("artifactPaths").add("output.txt");
        JsonNode next = service.handle(run);
        assertEquals("consumer", next.path("nodeId").asText());
        assertTrue(next.path("stateDelta").isObject());
        compact(next);
        compact(service.handle(current("next", next)));
        JsonNode briefState = service.handle(current("state", next));
        assertEquals(1, briefState.path("state").path("historyCount").intValue());
        assertEquals(1, briefState.path("state").path("factCount").intValue());
        assertEquals(1, briefState.path("state").path("artifactCount").intValue());
        assertFalse(briefState.path("state").has("history"));
        assertFalse(briefState.path("state").has("facts"));
        ObjectNode details = current("state", next); details.put("section", "history");
        JsonNode history = service.handle(details).path("state").path("history");
        assertEquals(1, history.size());
        assertEquals("producer", history.get(0).path("nodeId").asText());
    }

    @Test void ordinarySpecReadsSelectNodeOrPathWithoutCreatingExecutionState() throws Exception {
        fixture();
        WorkflowService service = new WorkflowService(home);
        JsonNode first = service.handle(args("spec"));
        assertEquals("STATELESS", first.path("status").asText());
        assertEquals("spec/producer.md", first.path("specs").get(0).path("path").asText());
        assertFalse(first.path("specs").get(0).has("content"));
        ObjectNode node = args("spec"); node.put("nodeId", "consumer").put("includeContent", true);
        assertEquals("# Consumer\n\nCheck the produced output.", service.handle(node).path("specs").get(0).path("content").asText());
        ObjectNode byPath = args("spec"); byPath.put("specPath", "spec/consumer.md").put("includeContent", true);
        assertEquals(service.handle(node).path("specs"), service.handle(byPath).path("specs"));
        byPath.put("specPath", "spec/not-declared.md");
        assertThrows(IllegalArgumentException.class, () -> service.handle(byPath));
        assertFalse(Files.exists(workspace.resolve(".specunfold")));
    }

    @Test void overlappingIntentRoutesRequireAnExplicitWorkflow() throws Exception {
        fixture();
        ObjectNode map = (ObjectNode) JSON.readTree(Files.readAllBytes(profile.resolve("spec-map.json")));
        ((ObjectNode) map.get("workflows")).set("alternate", map.path("workflows").path("main").deepCopy());
        map.putArray("intentRoutes").addObject().putArray("whenAny").add("inspect");
        ObjectNode main = (ObjectNode) map.path("intentRoutes").get(0); main.put("workflow", "main");
        ObjectNode alternate = ((com.fasterxml.jackson.databind.node.ArrayNode) map.get("intentRoutes")).addObject();
        alternate.put("workflow", "alternate").putArray("whenAny").add("inspect");
        write("spec-map.json", map.toString());
        write("workflows/alternate.json", new String(Files.readAllBytes(profile.resolve("workflows/main.json")), StandardCharsets.UTF_8));
        WorkflowService service = new WorkflowService(home);
        ObjectNode request = args("bootstrap"); request.put("intent", "inspect the output");
        assertThrows(IllegalArgumentException.class, () -> service.handle(request));
        assertFalse(Files.exists(workspace.resolve(".specunfold")));
        request.put("workflow", "main");
        assertEquals("main", service.handle(request).path("workflow").asText());
    }

    @Test void operationModeUsesOnlyProfileDeclaredApplicableNodes() throws Exception {
        fixture();
        ObjectNode definition = (ObjectNode) JSON.readTree(Files.readAllBytes(profile.resolve("workflows/main.json")));
        ((ObjectNode) definition.path("nodes").get(0)).putArray("operationModes").add("build");
        ((ObjectNode) definition.path("nodes").get(1)).putArray("operationModes").add("review");
        write("workflows/main.json", definition.toString());
        ObjectNode request = args("bootstrap"); request.put("operationMode", "review");
        WorkflowService service = new WorkflowService(home);
        JsonNode first = service.handle(request);
        assertEquals("consumer", first.path("nodeId").asText());
        assertEquals("consumer", new WorkflowService(home).handle(current("context", first)).path("nodeId").asText());
        request.put("operationMode", "build");
        JsonNode build = service.handle(request);
        assertEquals("producer", build.path("nodeId").asText());
        Files.write(workspace.resolve("output.txt"), "output".getBytes(StandardCharsets.UTF_8));
        ObjectNode run = submission(build, "ready"); run.putArray("artifactPaths").add("output.txt");
        assertEquals("DONE", service.handle(run).path("status").asText());
    }

    @Test void missingCompletedArtifactBlocksProgressAndOnlyConfirmedRecoveryReopensProducer() throws Exception {
        fixture();
        WorkflowService service = new WorkflowService(home);
        JsonNode producer = service.handle(args("bootstrap"));
        Files.write(workspace.resolve("output.txt"), "output".getBytes(StandardCharsets.UTF_8));
        ObjectNode run = submission(producer, "ready"); run.putArray("artifactPaths").add("output.txt");
        JsonNode consumer = service.handle(run);
        Files.delete(workspace.resolve("output.txt"));
        JsonNode blocked = new WorkflowService(home).handle(current("context", consumer));
        assertEquals("BLOCKED", blocked.path("status").asText());
        assertEquals("output.txt", blocked.path("missingArtifacts").get(0).path("path").asText());
        assertEquals("producer", blocked.path("missingArtifacts").get(0).path("producerNodeId").asText());
        assertEquals("BLOCKED", service.handle(submission(consumer, "checked")).path("status").asText());
        ObjectNode recovery = current("context", blocked); recovery.putArray("reopenNodeIds").add("producer");
        assertThrows(IllegalArgumentException.class, () -> service.handle(recovery));
        recovery.put("recoveryConfirmed", true);
        JsonNode reopened = service.handle(recovery);
        assertEquals("producer", reopened.path("nodeId").asText());
        assertEquals("READY", reopened.path("status").asText());
        ObjectNode facts = current("state", reopened); facts.put("section", "facts");
        assertTrue(service.handle(facts).path("state").path("facts").isEmpty());
        Files.write(workspace.resolve("output.txt"), "restored".getBytes(StandardCharsets.UTF_8));
        ObjectNode rerun = submission(reopened, "ready"); rerun.putArray("artifactPaths").add("output.txt");
        JsonNode resumed = service.handle(rerun);
        assertEquals("consumer", resumed.path("nodeId").asText());
        ObjectNode checked = submission(resumed, "checked"); ((ObjectNode) checked.get("facts")).put("ready", true);
        assertEquals("DONE", service.handle(checked).path("status").asText());
    }

    private void fixture() throws Exception {
        home = Files.createDirectories(directory.resolve("home"));
        workspace = Files.createDirectories(directory.resolve("workspace"));
        profile = Files.createDirectories(home.resolve("resources/profiles/demo"));
        write("spec-map.json", "{\"workflowResolution\":{\"defaultWorkflow\":\"main\"},\"intentRoutes\":[],\"workflows\":{\"main\":{\"nodes\":{\"producer\":{\"baseSpec\":[\"spec/producer.md\"]},\"consumer\":{\"baseSpec\":[\"spec/consumer.md\"]}}}}}");
        write("workflows/main.json", "{\"nodes\":[{\"id\":\"producer\",\"type\":\"RUN_NODE\",\"title\":\"Produce\",\"objective\":\"Create output\",\"prompt\":\"Write output\",\"requiredFacts\":[\"facts.ready\"],\"expectedArtifacts\":[\"output.txt\"]},{\"id\":\"consumer\",\"type\":\"RUN_NODE\",\"title\":\"Check\",\"objective\":\"Check output\",\"prompt\":\"Review output\",\"requiredFacts\":[\"facts.ready\",\"facts.checked\"]}]}");
        write("spec/producer.md", "# Producer\n\nCreate the output in the workspace.");
        write("spec/consumer.md", "# Consumer\n\nCheck the produced output.");
    }

    private ObjectNode args(String action) {
        ObjectNode request = JSON.createObjectNode().put("action", action).put("workspaceRoot", workspace.toString()).put("profile", "demo");
        if ("bootstrap".equals(action)) request.put("workflowConfirmedByUser", true).put("workflowConfirmationSource", "user_explicit_request");
        return request;
    }

    private ObjectNode current(String action, JsonNode response) {
        return args(action).put("executionId", response.path("executionId").asText());
    }

    private ObjectNode submission(JsonNode response, String fact) {
        assertTrue(response.path("runTemplate").isObject(), "Execution package must provide a directly usable runTemplate");
        ObjectNode request = response.path("runTemplate").deepCopy();
        assertEquals("run", request.path("action").asText());
        assertEquals(response.path("executionId"), request.path("executionId"));
        assertEquals(response.path("nodeId"), request.path("nodeId"));
        assertEquals(response.path("specTicket"), request.path("specTicket"));
        request.put("status", "DONE");
        request.putObject("facts").put(fact, true);
        request.putObject("evidence").put("review", "checked");
        request.putArray("artifactPaths");
        return request;
    }

    private void compact(JsonNode response) {
        for (String field : new String[] {"facts", "evidence", "artifactPaths", "history", "state"}) {
            assertFalse(response.has(field), "Compact package must not include accumulated " + field);
        }
        assertTrue(response.path("runTemplate").isObject());
        for (JsonNode spec : response.path("specs")) assertFalse(spec.has("content"));
    }

    private void write(String relative, String body) throws Exception {
        Path file = profile.resolve(relative); Files.createDirectories(file.getParent());
        Files.write(file, body.getBytes(StandardCharsets.UTF_8));
    }
}
