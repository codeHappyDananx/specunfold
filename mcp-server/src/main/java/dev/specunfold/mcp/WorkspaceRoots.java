package dev.specunfold.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves the chat workspace from explicit arguments, service environment or MCP roots. */
final class WorkspaceRoots {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String configuredWorkspace;
    private final Path workingDirectory;
    private final ArrayDeque<ObjectNode> outgoing = new ArrayDeque<ObjectNode>();
    private final List<Path> roots = new ArrayList<Path>();
    private boolean supported;
    private boolean received;
    private boolean refreshAgain;
    private String pendingId;
    private long requestNumber;

    WorkspaceRoots(String configuredWorkspace, Path workingDirectory) {
        this.configuredWorkspace = configuredWorkspace;
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }

    void initialize(JsonNode params) {
        supported = params.path("capabilities").path("roots").isObject();
        received = false;
        refreshAgain = false;
        pendingId = null;
        roots.clear();
        outgoing.clear();
    }

    void notification(String method) {
        if (!supported) return;
        if ("notifications/initialized".equals(method)) requestRoots();
        else if ("notifications/roots/list_changed".equals(method)) {
            received = false;
            if (pendingId != null) refreshAgain = true;
            else requestRoots();
        }
    }

    private void requestRoots() {
        if (pendingId != null) return;
        pendingId = "specunfold-roots-" + (++requestNumber);
        outgoing.add(JSON.createObjectNode().put("jsonrpc", "2.0").put("id", pendingId)
                .put("method", "roots/list"));
    }

    ObjectNode pollOutgoing() { return outgoing.poll(); }

    boolean acceptResponse(JsonNode response) {
        if (response == null || !response.isObject() || response.has("method") || pendingId == null
                || !"2.0".equals(response.path("jsonrpc").asText())
                || !pendingId.equals(response.path("id").asText())) return false;
        Set<Path> selected = new LinkedHashSet<Path>();
        for (JsonNode root : response.path("result").path("roots")) {
            if (!root.path("uri").isTextual()) continue;
            try {
                URI uri = URI.create(root.get("uri").textValue());
                if ("file".equalsIgnoreCase(uri.getScheme())) selected.add(Paths.get(uri).toAbsolutePath().normalize());
            } catch (IllegalArgumentException e) {
                // Unsupported root URIs are not workspace candidates.
            }
        }
        roots.clear();
        roots.addAll(selected);
        pendingId = null;
        received = true;
        if (refreshAgain) {
            refreshAgain = false;
            received = false;
            requestRoots();
        }
        return true;
    }

    ObjectNode resolve(ObjectNode args) {
        if (args.has("workspaceRoot")) return args;
        if (configuredWorkspace != null && !configuredWorkspace.isEmpty()) {
            return args.deepCopy().put("workspaceRoot", configuredWorkspace);
        }
        if (supported && !received) return selection("Wait for the roots/list response or call workflow_ops with an explicit workspaceRoot");
        List<Path> installed = new ArrayList<Path>();
        for (Path root : roots) {
            if (Files.isRegularFile(root.resolve(".agents/harness/manifest.json"))) installed.add(root);
        }
        if (installed.size() == 1) return args.deepCopy().put("workspaceRoot", installed.get(0).toString());
        if (roots.size() == 1) return args.deepCopy().put("workspaceRoot", roots.get(0).toString());
        if (roots.size() > 1) return selection("Call workflow_ops with the selected chat workspaceRoot");
        return args.deepCopy().put("workspaceRoot", workingDirectory.toString());
    }

    private ObjectNode selection(String nextAction) {
        ObjectNode result = JSON.createObjectNode().put("status", "SELECT_WORKSPACE").put("nextAction", nextAction);
        for (Path root : roots) result.withArray("candidates").add(root.toString());
        if (!result.has("candidates")) result.putArray("candidates");
        return result;
    }
}
