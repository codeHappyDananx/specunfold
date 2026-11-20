package dev.specunfold.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.specunfold.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceRootsTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path temporary;

    @Test void negotiatesRefreshesAndSelectsRootsWithoutCreatingAmbiguousExecutions() throws Exception {
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path second = Files.createDirectory(temporary.resolve("second"));
        Path single = Files.createDirectory(temporary.resolve("single"));
        installMarker(first);
        SpecUnfoldServer server = new SpecUnfoldServer(new WorkflowService(temporary), new WorkspaceRoots(null, temporary));
        ObjectNode initialize = rpc("initialize");
        initialize.putObject("params").put("protocolVersion", "2025-11-25").putObject("capabilities").putObject("roots").put("listChanged", true);
        server.handle(initialize);
        assertNull(server.handle(notification("notifications/initialized")));
        ObjectNode request = server.pollOutgoing();
        assertEquals("roots/list", request.path("method").asText());
        assertNull(server.pollOutgoing());
        assertEquals("SELECT_WORKSPACE", tool(server, "bootstrap", null).path("status").asText());
        assertNull(server.handle(rootResponse(request, first, second)));
        assertEquals(first.toRealPath().toString(), tool(server, "workspace_probe", null).path("workspaceRoot").asText());
        assertNull(server.handle(notification("notifications/roots/list_changed")));
        ObjectNode refreshed = server.pollOutgoing();
        assertNotEquals(request.get("id"), refreshed.get("id"));
        assertEquals("SELECT_WORKSPACE", tool(server, "bootstrap", null).path("status").asText());
        installMarker(second);
        server.handle(rootResponse(refreshed, first, second));
        JsonNode selection = tool(server, "bootstrap", null);
        assertEquals("SELECT_WORKSPACE", selection.path("status").asText());
        assertEquals(2, selection.path("candidates").size());
        assertTrue(selection.has("nextAction"));
        assertFalse(Files.exists(first.resolve(".specunfold/workflow-state")));
        assertFalse(Files.exists(second.resolve(".specunfold/workflow-state")));
        assertEquals(second.toRealPath().toString(), tool(server, "workspace_probe", second).path("workspaceRoot").asText());
        server.handle(notification("notifications/roots/list_changed"));
        server.handle(rootResponse(server.pollOutgoing(), single));
        assertEquals(single.toRealPath().toString(), tool(server, "workspace_probe", null).path("workspaceRoot").asText());
    }

    @Test void honorsExplicitThenEnvironmentThenCwdAndNeverUsesProjectPathsAsChatRoot() throws Exception {
        ObjectNode initialize = json.createObjectNode();
        WorkspaceRoots unconfigured = new WorkspaceRoots(null, temporary);
        unconfigured.initialize(initialize);
        unconfigured.notification("notifications/initialized");
        assertNull(unconfigured.pollOutgoing());
        ObjectNode args = json.createObjectNode().put("action", "context");
        args.putObject("projectPaths").put("application", temporary.resolve("other").toString());
        assertEquals(temporary.toString(), unconfigured.resolve(args).path("workspaceRoot").asText());
        WorkspaceRoots configured = new WorkspaceRoots(temporary.resolve("environment").toString(), temporary);
        initialize.putObject("capabilities").putObject("roots");
        configured.initialize(initialize);
        assertEquals(temporary.resolve("environment").toString(), configured.resolve(args).path("workspaceRoot").asText());
        args.put("workspaceRoot", temporary.resolve("explicit").toString());
        assertEquals(temporary.resolve("explicit").toString(), configured.resolve(args).path("workspaceRoot").asText());
    }

    private ObjectNode rpc(String method) {
        return json.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
    }

    private ObjectNode notification(String method) { return json.createObjectNode().put("jsonrpc", "2.0").put("method", method); }

    private ObjectNode rootResponse(ObjectNode request, Path... paths) {
        ObjectNode response = json.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        com.fasterxml.jackson.databind.node.ArrayNode roots = response.putObject("result").putArray("roots");
        for (Path path : paths) roots.addObject().put("uri", path.toUri().toString());
        return response;
    }

    private JsonNode tool(SpecUnfoldServer server, String action, Path workspace) {
        ObjectNode call = rpc("tools/call");
        ObjectNode args = call.putObject("params").put("name", "workflow_ops").putObject("arguments").put("action", action).put("profile", "example");
        if (workspace != null) args.put("workspaceRoot", workspace.toString());
        JsonNode payload = server.handle(call).path("result").path("structuredContent");
        assertTrue(payload.path("success").asBoolean(), payload.toString());
        return payload.get("result");
    }

    private void installMarker(Path root) throws Exception {
        Path manifest = root.resolve(".agents/harness/manifest.json");
        Files.createDirectories(manifest.getParent());
        Files.write(manifest, "{\"framework\":\"specunfold\",\"contractVersion\":1,\"defaultProfile\":\"example\"}".getBytes(StandardCharsets.UTF_8));
    }
}
