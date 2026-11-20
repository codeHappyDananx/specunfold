package dev.specunfold.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.specunfold.workflow.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Line-delimited JSON-RPC over stdio. Diagnostics are written only to stderr. */
public final class SpecUnfoldServer {
    private static final Logger logger = LoggerFactory.getLogger(SpecUnfoldServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VERSIONS = new HashSet<String>(Arrays.asList(
            "2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"));
    private final WorkflowService workflow;
    private final WorkspaceRoots roots;
    private ResponsePages pages = new ResponsePages();
    private boolean initialized;

    SpecUnfoldServer(WorkflowService workflow) {
        this(workflow, new WorkspaceRoots(System.getenv("SPECUNFOLD_WORKSPACE_ROOT"), Paths.get(System.getProperty("user.dir"))));
    }

    SpecUnfoldServer(WorkflowService workflow, WorkspaceRoots roots) { this.workflow = workflow; this.roots = roots; }

    public static void main(String[] args) throws Exception {
        SpecUnfoldServer server = new SpecUnfoldServer(new WorkflowService(Paths.get(
                System.getProperty("specunfold.home", System.getProperty("user.dir")))));
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        String line;
        while ((line = input.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            ObjectNode response;
            try { response = server.handle(JSON.readTree(line)); }
            catch (Exception e) { response = error(null, -32700, "Parse error"); }
            if (response != null) output.println(JSON.writeValueAsString(response));
            ObjectNode outgoing;
            while ((outgoing = server.pollOutgoing()) != null) output.println(JSON.writeValueAsString(outgoing));
        }
    }

    ObjectNode handle(JsonNode request) {
        return ResponsePages.enforce(handleRequest(request), ResponsePages.MAX_RESPONSE_BYTES);
    }

    ObjectNode pollOutgoing() { return ResponsePages.enforce(roots.pollOutgoing(), ResponsePages.MAX_RESPONSE_BYTES); }

    private ObjectNode handleRequest(JsonNode request) {
        if (roots.acceptResponse(request)) return null;
        if (request == null || !request.isObject() || !"2.0".equals(request.path("jsonrpc").asText())
                || !request.path("method").isTextual()) return error(null, -32600, "Invalid Request");
        String method = request.get("method").textValue();
        if (!request.has("id")) {
            if (initialized) roots.notification(method);
            return null;
        }
        JsonNode id = request.get("id");
        if (!(id.isTextual() || id.isIntegralNumber())) return error(null, -32600, "Invalid request id");
        JsonNode params = request.path("params");
        if (!params.isMissingNode() && !params.isObject()) return error(id, -32602, "params must be an object");
        if ("initialize".equals(method)) {
            if (!params.path("protocolVersion").isTextual()) return error(id, -32602, "protocolVersion is required");
            String version = params.get("protocolVersion").textValue();
            String selectedVersion = VERSIONS.contains(version) ? version : "2025-11-25";
            pages = new ResponsePages(!"2024-11-05".equals(selectedVersion) && !"2025-03-26".equals(selectedVersion));
            roots.initialize(params);
            ObjectNode result = JSON.createObjectNode();
            result.put("protocolVersion", selectedVersion);
            result.putObject("capabilities").putObject("tools").put("listChanged", false);
            result.putObject("serverInfo").put("name", "specunfold").put("version", "1.0.0");
            result.put("instructions", "At task/session start, resumption or context compaction, call workflow_ops context with the chat workspaceRoot, intent and known executionId. Ordinary tasks do not create workflows. Use current refs and runTemplate directly; read needed specification text only. Follow json_fragment next requests and assemble every page before using the result. User confirmation must come from the user.");
            initialized = true;
            return result(id, result);
        }
        if ("ping".equals(method)) return result(id, JSON.createObjectNode());
        if (!initialized) return error(id, -32600, "Initialize the MCP session first");
        if ("tools/list".equals(method)) {
            ObjectNode result = JSON.createObjectNode();
            ObjectNode tool = result.putArray("tools").addObject();
            tool.put("name", "workflow_ops");
            tool.put("description", "Start/resume with context. Read current refs, request needed specification text, submit facts/artifacts/evidence via runTemplate and continue. ASK_USER needs a user decision. UTF-8 response bytes are bounded; assemble all json_fragment pages before using a result.");
            tool.set("inputSchema", inputSchema());
            return result(id, result);
        }
        if ("tools/call".equals(method)) {
            if (!params.path("arguments").isObject()) return error(id, -32602, "arguments must be an object");
            JsonNode args = params.get("arguments");
            int budget;
            try { budget = ResponsePages.budget(args); }
            catch (IllegalArgumentException e) { return error(id, -32602, e.getMessage()); }
            if (!"workflow_ops".equals(params.path("name").asText())) {
                return ResponsePages.enforce(error(id, -32602, "Unknown tool"), budget);
            }
            if ("page".equals(args.path("action").asText())) {
                try { return pages.page(id, args, budget); }
                catch (IllegalArgumentException e) {
                    ObjectNode failure = JSON.createObjectNode().put("success", false).put("error", e.getMessage());
                    return ResponsePages.enforce(pages.response(id, failure, true, "Page request failed; see structuredContent."), budget);
                }
            }
            ObjectNode payload = JSON.createObjectNode();
            boolean failed = false;
            try {
                ObjectNode selected = roots.resolve((ObjectNode) args);
                payload.set("result", selected.has("workspaceRoot") ? workflow.handle(selected) : selected);
                payload.put("success", true);
            }
            catch (Exception e) {
                failed = true;
                payload.put("success", false).put("error", e.getMessage());
                logger.warn("Workflow request failed: {}", e.getMessage());
            }
            try { return pages.render(id, payload, failed, budget); }
            catch (IllegalArgumentException e) {
                ObjectNode failure = JSON.createObjectNode().put("success", false).put("error", e.getMessage());
                return ResponsePages.enforce(pages.response(id, failure, true, "Response budget failed; see structuredContent."), budget);
            }
        }
        return error(id, -32601, "Method not found: " + method);
    }

    private static ObjectNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode actions = properties.putObject("action").put("type", "string").putArray("enum");
        for (String action : Arrays.asList("context", "ignore_workspace", "workspace_probe", "spec_map", "spec", "spec_check", "bootstrap", "next", "run", "state", "validate", "run_profile_script", "page")) actions.add(action);
        for (String name : Arrays.asList("workspaceRoot", "profile", "workflow", "intent", "executionId", "nodeId", "specTicket", "status", "blockingReason", "scriptId", "reason", "operationMode", "section", "specPath", "contextEpoch", "responseId")) properties.putObject(name).put("type", "string");
        for (String name : Arrays.asList("includeContent", "recoveryConfirmed", "workflowConfirmedByUser", "skipConfirm")) properties.putObject(name).put("type", "boolean");
        properties.putObject("workflowConfirmationSource").put("type", "string").putArray("enum")
                .add("user_explicit_request").add("user_confirmed_proposal");
        for (String name : Arrays.asList("facts", "evidence", "input", "currentState", "knownSpecHashes", "parameters", "projectPaths")) properties.putObject(name).put("type", "object");
        for (String name : Arrays.asList("artifactPaths", "reopenNodeIds")) properties.putObject(name).put("type", "array").putObject("items").put("type", "string");
        for (String name : Arrays.asList("offset", "limit", "maxResponseBytes")) properties.putObject(name).put("type", "integer");
        properties.with("maxResponseBytes").put("minimum", 1024).put("maximum", ResponsePages.MAX_RESPONSE_BYTES);
        ObjectNode decision = properties.putObject("decision").put("type", "object");
        ObjectNode decisionProperties = decision.putObject("properties");
        decisionProperties.putObject("approved").put("type", "boolean");
        decisionProperties.putObject("autoApproved").put("type", "boolean");
        decision.putArray("required").add("approved");
        decision.put("additionalProperties", false);
        schema.putArray("required").add("action");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode result(JsonNode id, JsonNode value) {
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id); response.set("result", value); return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id); response.putObject("error").put("code", code).put("message", message);
        return response;
    }
}
