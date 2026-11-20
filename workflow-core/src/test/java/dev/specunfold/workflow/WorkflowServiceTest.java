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

class WorkflowServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;
    private Path home, workspace, profile;

    @Test
    void disclosesOnDemandBlocksGatesAndResumesAfterRestart() throws Exception {
        fixture();
        WorkflowService service = new WorkflowService(home);
        assertEquals("VALID", service.handle(args("validate")).path("status").asText());
        ObjectNode bootstrap = args("bootstrap"); bootstrap.put("intent", "prepare a delivery");
        JsonNode first = service.handle(bootstrap);
        assertEquals("prepare", first.path("nodeId").asText());
        assertFalse(first.path("specs").get(0).has("content"));
        assertEquals(2, first.get("specs").size());
        ObjectNode spec = current("spec", first); spec.put("includeContent", true);
        assertEquals("# Preparation\n\nRead the specification.", service.handle(spec).path("specs").get(0).path("content").asText());
        ObjectNode forged = current("run", first); forged.put("status", "DONE").put("specTicket", "forged");
        assertEquals("BLOCKED", service.handle(forged).path("status").asText());
        ObjectNode incomplete = current("run", first); incomplete.put("status", "DONE");
        JsonNode blocked = service.handle(incomplete);
        assertEquals("BLOCKED", blocked.path("status").asText());
        assertEquals("prepare", blocked.path("nodeId").asText());
        ObjectNode prepared = current("run", blocked); prepared.put("status", "DONE");
        prepared.putObject("facts").put("prepared", true); prepared.putObject("evidence").put("note", "read and understood"); prepared.putArray("artifactPaths");
        JsonNode confirmation = service.handle(prepared);
        assertEquals("confirm", confirmation.path("nodeId").asText());
        service = new WorkflowService(home);
        JsonNode resumed = service.handle(current("next", confirmation));
        assertEquals(confirmation.get("specTicket"), resumed.get("specTicket"));
        ObjectNode declined = current("run", resumed); declined.put("status", "DONE"); declined.putObject("decision").put("approved", false);
        JsonNode refused = service.handle(declined);
        assertEquals("BLOCKED", refused.path("status").asText());
        assertEquals("confirm", refused.path("nodeId").asText());
        ObjectNode accepted = current("run", refused); accepted.put("status", "DONE"); accepted.putObject("decision").put("approved", true);
        JsonNode verify = service.handle(accepted);
        assertEquals("verify", verify.path("nodeId").asText());
        ObjectNode missingArtifact = current("run", verify); missingArtifact.put("status", "DONE");
        missingArtifact.putObject("evidence").put("checked", true);
        missingArtifact.putObject("facts").put("verified", true); missingArtifact.putArray("artifactPaths").add("delivery.txt");
        assertEquals("BLOCKED", service.handle(missingArtifact).path("status").asText());
        Files.write(workspace.resolve("delivery.txt"), "done".getBytes(StandardCharsets.UTF_8));
        assertEquals("DONE", service.handle(missingArtifact).path("status").asText());
        ObjectNode history = current("state", verify); history.put("section", "history");
        JsonNode finished = new WorkflowService(home).handle(history);
        assertEquals("DONE", finished.path("status").asText());
        assertEquals(6, finished.path("state").path("history").size());
        assertTrue(Files.isRegularFile(workspace.resolve(".specunfold/workflow-state/demo/" + first.path("executionId").asText() + ".json")));
    }

    @Test
    void rejectsChangedSpecificationsAndWorkflowAndUnsafeArtifacts() throws Exception {
        fixture();
        WorkflowService service = new WorkflowService(home);
        JsonNode first = service.handle(args("bootstrap"));
        ObjectNode check = current("spec_check", first);
        assertEquals("VALID", service.handle(check).path("status").asText());
        write("spec/prepare.md", "# Changed preparation\n\nUpdated requirements.");
        assertEquals("BLOCKED", service.handle(check).path("status").asText());
        JsonNode refreshed = service.handle(current("spec", first));
        assertNotEquals(first.get("specTicket"), refreshed.get("specTicket"));
        assertEquals("VALID", service.handle(current("spec_check", refreshed)).path("status").asText());
        ObjectNode workflow = (ObjectNode) JSON.readTree(Files.readAllBytes(profile.resolve("workflows/main.json")));
        ((ObjectNode) workflow.get("nodes").get(0)).put("prompt", "Read updated workflow");
        write("workflows/main.json", workflow.toString());
        assertEquals("BLOCKED", service.handle(current("spec_check", refreshed)).path("status").asText());
        JsonNode latest = service.handle(current("spec", refreshed));
        ObjectNode unsafe = current("run", latest); unsafe.put("status", "DONE"); unsafe.putArray("artifactPaths").add("../outside.txt");
        assertThrows(IllegalArgumentException.class, () -> service.handle(unsafe));
        ObjectNode wrongNode = current("run", latest); wrongNode.put("nodeId", "verify").put("status", "DONE");
        assertEquals("BLOCKED", service.handle(wrongNode).path("status").asText());
        ObjectNode invalidWorkspace = args("bootstrap"); invalidWorkspace.put("workspaceRoot", directory.resolve("missing").toString());
        assertThrows(IllegalArgumentException.class, () -> service.handle(invalidWorkspace));
    }

    @Test
    void persistsBlockingReasonRequiresExplicitEvidenceAndRejectsReorderedNodes() throws Exception {
        fixture();
        WorkflowService service = new WorkflowService(home);
        JsonNode first = service.handle(args("bootstrap"));
        assertEquals("Read the specification.", first.path("specs").get(0).path("summary").asText());
        ObjectNode stopped = current("run", first); stopped.put("status", "BLOCKED").put("blockingReason", "Awaiting input");
        stopped.putObject("facts"); stopped.putObject("evidence").put("reason", "Input was not provided"); stopped.putArray("artifactPaths");
        JsonNode blocked = service.handle(stopped);
        assertEquals("Awaiting input", blocked.path("blockingReason").asText());
        JsonNode resumed = new WorkflowService(home).handle(current("next", blocked));
        assertEquals("Awaiting input", resumed.path("blockingReason").asText());
        ObjectNode done = current("run", resumed); done.put("status", "DONE");
        done.putObject("facts").put("prepared", true); done.putArray("artifactPaths");
        assertEquals("BLOCKED", service.handle(done).path("status").asText());
        done.putObject("evidence").put("note", "reviewed");
        JsonNode confirmation = service.handle(done);
        assertEquals("confirm", confirmation.path("nodeId").asText());
        assertFalse(confirmation.has("blockingReason"));
        ObjectNode definition = (ObjectNode) JSON.readTree(Files.readAllBytes(profile.resolve("workflows/main.json")));
        com.fasterxml.jackson.databind.node.ArrayNode nodes = (com.fasterxml.jackson.databind.node.ArrayNode) definition.get("nodes");
        JsonNode prepare = nodes.get(0); nodes.set(0, nodes.get(1)); nodes.set(1, prepare);
        write("workflows/main.json", definition.toString());
        assertThrows(IllegalStateException.class, () -> new WorkflowService(home).handle(current("next", confirmation)));
    }
    @Test
    void rejectsStateDirectoryAndFileSymlinksBeforeWritingOutsideWorkspace() throws Exception {
        fixture();
        workspace = workspace.toRealPath();
        Path outside = Files.createDirectories(directory.resolve("outside")).toRealPath();
        Path linkedDirectory = workspace.resolve(".specunfold");
        Files.createSymbolicLink(linkedDirectory, outside);
        try {
            assertThrows(IllegalArgumentException.class, () -> new WorkflowService(home).handle(args("bootstrap")));
            assertFalse(Files.exists(outside.resolve("workflow-state")));
        } finally { Files.delete(linkedDirectory); }
        Path stateDirectory = Files.createDirectories(workspace.resolve(".specunfold/workflow-state/demo"));
        Path outsideFile = outside.resolve("untouched.txt");
        Files.write(outsideFile, "untouched".getBytes(StandardCharsets.UTF_8));
        WorkflowStateStore store = new WorkflowStateStore();
        Path lock = stateDirectory.resolve("linked.lock");
        Files.createSymbolicLink(lock, outsideFile);
        try { assertThrows(IllegalArgumentException.class, () -> store.lock(workspace, "demo", "linked")); }
        finally { Files.delete(lock); }
        Path state = stateDirectory.resolve("linked.json");
        Files.createSymbolicLink(state, outsideFile);
        try {
            assertThrows(IllegalArgumentException.class, () -> store.save(workspace, "demo", "linked", JSON.createObjectNode()));
            assertThrows(IllegalArgumentException.class, () -> store.load(workspace, "demo", "linked"));
            assertEquals("untouched", new String(Files.readAllBytes(outsideFile), StandardCharsets.UTF_8));
        } finally { Files.delete(state); }
    }
    private void fixture() throws Exception {
        home = Files.createDirectories(directory.resolve("home")); workspace = Files.createDirectories(directory.resolve("workspace"));
        profile = Files.createDirectories(home.resolve("resources/profiles/demo"));
        write("spec-map.json", "{\"workflowResolution\":{\"defaultWorkflow\":\"main\"},\"intentRoutes\":[{\"whenAny\":[\"delivery\"],\"workflow\":\"main\"}],\"workflows\":{\"main\":{\"nodes\":{\"prepare\":{\"baseSpec\":[\"spec/prepare.md\"],\"conditionalSpec\":[{\"whenAny\":[\"delivery\"],\"spec\":[\"spec/delivery.md\"]}]},\"confirm\":{\"baseSpec\":[\"spec/confirm.md\"]},\"verify\":{\"baseSpec\":[\"spec/verify.md\"]}}}}}");
        write("workflows/main.json", "{\"nodes\":[{\"id\":\"prepare\",\"type\":\"RUN_NODE\",\"title\":\"Prepare\",\"objective\":\"Read requirements\",\"prompt\":\"Read specification\",\"requiredFacts\":[\"facts.prepared\"],\"factAssertions\":[{\"path\":\"facts.prepared\",\"operator\":\"equals\",\"value\":true}],\"completionGates\":[{\"type\":\"evidence\",\"path\":\"evidence.note\",\"operator\":\"notEmpty\"}]},{\"id\":\"confirm\",\"type\":\"ASK_USER\",\"title\":\"Confirm\",\"objective\":\"Obtain approval\",\"prompt\":\"Ask the user\"},{\"id\":\"verify\",\"type\":\"RUN_NODE\",\"title\":\"Verify\",\"objective\":\"Verify output\",\"prompt\":\"Check delivery\",\"requiredFacts\":[\"facts.verified\"],\"expectedArtifacts\":[\"delivery.txt\"],\"completionGates\":[{\"type\":\"artifact\",\"path\":\"delivery.txt\"}]}]}");
        write("spec/prepare.md", "# Preparation\n\nRead the specification.");
        write("spec/delivery.md", "# Delivery\n\nPrepare a delivery.");
        write("spec/confirm.md", "# Confirmation\n\nAsk for explicit approval.");
        write("spec/verify.md", "# Verification\n\nVerify the artifact exists.");
    }
    private ObjectNode args(String action) {
        ObjectNode request = JSON.createObjectNode().put("action", action).put("profile", "demo").put("workspaceRoot", workspace.toString());
        if ("bootstrap".equals(action)) request.put("workflowConfirmedByUser", true).put("workflowConfirmationSource", "user_explicit_request");
        return request;
    }
    private ObjectNode current(String action, JsonNode current) {
        ObjectNode args = args(action); args.put("executionId", current.path("executionId").asText());
        if (current.has("nodeId")) args.set("nodeId", current.get("nodeId"));
        if (current.has("specTicket")) args.set("specTicket", current.get("specTicket"));
        return args;
    }
    private void write(String path, String body) throws Exception {
        Path target = profile.resolve(path); Files.createDirectories(target.getParent()); Files.write(target, body.getBytes(StandardCharsets.UTF_8));
    }
}
