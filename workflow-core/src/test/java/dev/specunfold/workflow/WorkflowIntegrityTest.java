package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowIntegrityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @Test void changedGoalCannotCompleteWithTicketIssuedForDifferentConditionalSpecifications() throws Exception {
        fixture(false, false);
        WorkflowService service = new WorkflowService(workspace);
        JsonNode first = service.handle(bootstrap());
        write("out.txt", "prepared");
        ObjectNode run = run(first);
        run.putArray("artifactPaths").add("out.txt");
        run.putObject("currentState").put("goal", "review delivery");
        JsonNode result = service.handle(run);
        assertEquals("BLOCKED", result.path("status").asText());
        JsonNode resumed = service.handle(current("context", first));
        assertEquals("prepare", resumed.path("nodeId").asText());
        assertEquals(0, service.handle(state(first)).path("state").path("history").size());
    }

    @Test void negativeApprovalCannotBeOverriddenBySuccessfulAutomaticPolicy() throws Exception {
        fixture(true, false);
        write("review.json", "{\"status\":\"accepted\"}");
        WorkflowService service = new WorkflowService(workspace);
        JsonNode first = service.handle(bootstrap());
        ObjectNode run = current("run", first);
        run.put("status", "DONE").put("skipConfirm", true);
        run.putObject("decision").put("approved", false).put("autoApproved", true);
        JsonNode result = service.handle(run);
        assertEquals("BLOCKED", result.path("status").asText());
        assertEquals("prepare", result.path("nodeId").asText());
    }

    @Test void earlierArtifactSubmissionCannotSatisfyCurrentNodeSubmission() throws Exception {
        fixture(false, true);
        write("out.txt", "prepared");
        WorkflowService service = new WorkflowService(workspace);
        JsonNode first = service.handle(bootstrap());
        ObjectNode prepared = run(first); prepared.putArray("artifactPaths").add("out.txt");
        JsonNode verify = service.handle(prepared);
        assertEquals("verify", verify.path("nodeId").asText());
        JsonNode result = service.handle(run(verify));
        assertEquals("BLOCKED", result.path("status").asText());
        assertEquals("verify", result.path("nodeId").asText());
    }

    @Test void blockedRecordingNeedsEvidenceButDoesNotExecuteCompletionOnlyGates() throws Exception {
        fixture(false, false);
        WorkflowService service = new WorkflowService(workspace);
        JsonNode first = service.handle(bootstrap());
        ObjectNode report = run(first);
        report.put("status", "BLOCKED").put("blockingReason", "Waiting for input");
        report.putObject("facts");
        JsonNode result = service.handle(report);
        assertEquals("BLOCKED", result.path("status").asText());
        assertEquals(1, result.path("errors").size());
        assertEquals("Waiting for input", result.path("errors").get(0).asText());
        ObjectNode incomplete = current("run", result);
        incomplete.put("status", "BLOCKED").put("blockingReason", "Waiting for input");
        JsonNode missing = service.handle(incomplete);
        String errors = missing.path("errors").toString();
        assertTrue(errors.contains("facts object"));
        assertTrue(errors.contains("evidence object"));
        assertTrue(errors.contains("artifactPaths array"));
    }

    @Test void disclosureHashesAreScopedToExplicitContextAndFreshContextCanReadAgain() throws Exception {
        fixture(false, false);
        WorkflowService service = new WorkflowService(workspace);
        JsonNode first = service.handle(bootstrap());
        String digest = first.path("specs").get(0).path("sha256").asText();
        ObjectNode known = current("spec", first);
        known.put("includeContent", true).put("contextEpoch", "session-a");
        known.putObject("knownSpecHashes").put("spec/base.md", digest);
        JsonNode reused = service.handle(known).path("specs").get(0);
        assertTrue(reused.path("unchanged").asBoolean());
        assertFalse(reused.has("content"));
        ObjectNode fresh = current("spec", first);
        fresh.put("includeContent", true).put("contextEpoch", "session-b");
        assertEquals("# Base\n\nPrepare the result.", service.handle(fresh).path("specs").get(0).path("content").asText());
    }

    @Test void frozenParametersBindExpectedArtifactsCatalogAndExternalDocumentContractsAcrossRestart() throws Exception {
        fixture(false, false);
        Path definitionFile = workspace.resolve(".agents/profiles/demo/workflows/delivery.json");
        ObjectNode definition = (ObjectNode) JSON.readTree(definitionFile.toFile());
        ObjectNode prepare = (ObjectNode) definition.path("nodes").get(0);
        String template = "${parameters.name}/${executionId}.txt";
        prepare.putArray("expectedArtifacts").add(template);
        prepare.putArray("completionGates").addObject().put("type", "artifact").put("path", template);
        ((ArrayNode) prepare.get("completionGates")).addObject().put("type", "document_contract").put("contract", "gates/document.json");
        profile("workflows/delivery.json", definition.toString());
        profile("gates/document.json", "{\"artifact\":\"" + template + "\",\"requiredPatterns\":[\"prepared\"]}");
        ObjectNode start = bootstrap(); start.putObject("parameters").put("name", "output");
        JsonNode first = new WorkflowService(workspace).handle(start);
        String expected = "output/" + first.path("executionId").asText() + ".txt";
        assertEquals(expected, first.path("node").path("expectedArtifacts").get(0).asText());
        assertEquals(expected, first.path("node").path("completionGates").get(0).path("path").asText());
        WorkflowService restarted = new WorkflowService(workspace);
        assertEquals(expected, restarted.handle(current("context", first)).path("node").path("expectedArtifacts").get(0).asText());
        ObjectNode changed = current("context", first); changed.putObject("parameters").put("name", "other");
        assertThrows(IllegalArgumentException.class, () -> restarted.handle(changed));
        write(expected, "prepared");
        ObjectNode submission = run(first); submission.putArray("artifactPaths").add(expected);
        assertEquals("DONE", restarted.handle(submission).path("status").asText());
    }

    @Test void confirmedReopenRetainsInvalidatedAuditAndRebuildsOnlyEffectiveCompletedFacts() throws Exception {
        fixture(false, true); write("out.txt", "prepared");
        WorkflowService service = new WorkflowService(workspace);
        JsonNode first = service.handle(bootstrap());
        ObjectNode prepared = run(first); prepared.putArray("artifactPaths").add("out.txt");
        JsonNode verify = service.handle(prepared);
        Files.delete(workspace.resolve("out.txt"));
        ObjectNode reopen = current("context", verify).put("recoveryConfirmed", true);
        reopen.putArray("reopenNodeIds").add("prepare");
        JsonNode result = service.handle(reopen);
        assertEquals("prepare", result.path("nodeId").asText());
        JsonNode history = service.handle(state(result)).path("state").path("history");
        assertEquals(2, history.size());
        assertEquals("DONE", history.get(0).path("status").asText());
        assertTrue(history.get(0).path("invalidated").asBoolean());
        assertEquals("REOPENED", history.get(1).path("status").asText());
        assertTrue(history.get(1).path("recoveryConfirmed").asBoolean());
        assertTrue(service.handle(current("state", result).put("section", "facts")).path("state").path("facts").isEmpty());
    }

    private void fixture(boolean askFirst, boolean verification) throws Exception {
        ObjectNode map = JSON.createObjectNode();
        map.putObject("workflowResolution").put("defaultWorkflow", "delivery");
        map.putArray("intentRoutes");
        ObjectNode mappings = map.putObject("workflows").putObject("delivery").putObject("nodes");
        ObjectNode prepareMap = mappings.putObject("prepare");
        prepareMap.putArray("baseSpec").add("spec/base.md");
        ObjectNode conditional = prepareMap.putArray("conditionalSpec").addObject();
        conditional.putArray("whenAny").add("review"); conditional.putArray("spec").add("spec/review.md");
        ObjectNode definition = JSON.createObjectNode().put("id", "delivery");
        ArrayNode nodes = definition.putArray("nodes");
        ObjectNode prepare = node("prepare", askFirst ? "ASK_USER" : "RUN_NODE");
        if (askFirst) {
            ObjectNode condition = prepare.putObject("autoDecisionPolicy").putArray("conditions").addObject();
            condition.put("type", "path_in").put("artifact", "review.json").put("path", "status");
            condition.putArray("values").add("accepted");
        } else requireArtifact(prepare);
        nodes.add(prepare);
        if (verification) {
            ObjectNode verify = node("verify", "RUN_NODE"); requireArtifact(verify); nodes.add(verify);
            mappings.putObject("verify").putArray("baseSpec").add("spec/base.md");
        }
        profile("spec-map.json", map.toString());
        profile("workflows/delivery.json", definition.toString());
        profile("spec/base.md", "# Base\n\nPrepare the result.");
        profile("spec/review.md", "# Review\n\nCheck the additional review requirement.");
    }
    private ObjectNode node(String id, String type) {
        ObjectNode node = JSON.createObjectNode().put("id", id).put("type", type).put("title", id)
                .put("objective", "Prepare result").put("prompt", "Read current requirements and submit evidence");
        node.putArray("requiredFacts"); node.putArray("factAssertions"); node.putArray("expectedArtifacts"); node.putArray("completionGates");
        return node;
    }
    private void requireArtifact(ObjectNode node) {
        node.putArray("requiredFacts").add("facts.ready");
        node.putArray("factAssertions").addObject().put("path", "facts.ready").put("operator", "equals").put("value", true);
        node.putArray("expectedArtifacts").add("out.txt");
        node.putArray("completionGates").addObject().put("type", "artifact").put("path", "out.txt");
    }
    private ObjectNode args(String action) { return JSON.createObjectNode().put("action", action).put("workspaceRoot", workspace.toString()).put("profile", "demo"); }
    private ObjectNode bootstrap() { return args("bootstrap").put("intent", "delivery").put("workflowConfirmedByUser", true).put("workflowConfirmationSource", "user_explicit_request"); }
    private ObjectNode current(String action, JsonNode value) {
        return args(action).put("executionId", value.path("executionId").asText()).put("nodeId", value.path("nodeId").asText()).put("specTicket", value.path("specTicket").asText());
    }
    private ObjectNode run(JsonNode value) {
        ObjectNode run = current("run", value).put("status", "DONE");
        run.putObject("facts").put("ready", true); run.putObject("evidence").put("note", "Reviewed the result"); run.putArray("artifactPaths");
        return run;
    }
    private ObjectNode state(JsonNode value) { return current("state", value).put("section", "history"); }
    private void profile(String path, String content) throws Exception { write(".agents/profiles/demo/" + path, content); }
    private void write(String path, String content) throws Exception {
        Path file = workspace.resolve(path); Files.createDirectories(file.getParent()); Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
