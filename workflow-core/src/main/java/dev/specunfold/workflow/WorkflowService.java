package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Profile-driven workflow execution with explicit disclosure and durable node transitions. */
public final class WorkflowService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProfileRepository profiles;
    private final WorkflowStateStore states = new WorkflowStateStore();

    public WorkflowService(Path home) { profiles = new ProfileRepository(home); }

    public JsonNode handle(JsonNode args) {
        if (args == null || !args.isObject()) throw new IllegalArgumentException("Arguments must be an object");
        String action = requiredText(args, "action");
        Path workspace = workspace(args);
        String profile = args.has("profile") ? requiredText(args, "profile") : profiles.defaultProfile(workspace);
        try {
            if ("workspace_probe".equals(action)) return WorkspaceContext.probe(profiles, workspace, profile, args);
            if ("ignore_workspace".equals(action)) return WorkspaceContext.ignore(workspace, profile, args);
            if ("context".equals(action) && !args.has("executionId")) return startupContext(workspace, profile, args);
            if ("spec".equals(action) && !args.has("executionId")) return statelessSpec(workspace, profile, args);
            if ("spec_map".equals(action)) return profiles.json(workspace, profile, "spec-map.json");
            if ("run_profile_script".equals(action)) return ProfileScriptRunner.run(profiles, workspace, profile, args);
            if ("validate".equals(action)) {
                JsonNode checked = validateProfile(workspace, profile, args);
                if (!args.has("executionId")) return checked;
                ObjectNode request = args.deepCopy(); request.put("action", "context");
                ObjectNode result = (ObjectNode) handle(request); result.set("profileValidation", checked); return result;
            }
            if ("bootstrap".equals(action)) return bootstrap(workspace, profile, args);
            if (!("context".equals(action) || "next".equals(action) || "state".equals(action) || "spec".equals(action)
                    || "spec_check".equals(action) || "run".equals(action))) {
                throw new IllegalArgumentException("Unsupported action: " + action);
            }
            String executionId = requiredText(args, "executionId");
            try (WorkflowStateStore.Lock ignored = states.lock(workspace, profile, executionId)) {
                ObjectNode state = states.load(workspace, profile, executionId);
                if (!executionId.equals(state.path("executionId").asText()) || !profile.equals(state.path("profile").asText())) {
                    throw new IllegalStateException("Execution state identity mismatch");
                }
                if (args.has("parameters") && !args.get("parameters").equals(state.path("parameters"))) throw new IllegalArgumentException("Execution parameters are frozen at bootstrap");
                if (args.has("reopenNodeIds")) reopen(workspace, profile, state, args);
                if (!"run".equals(action)) applyCurrentState(state, args);
                Context context = context(workspace, profile, state);
                if ("run".equals(action)) return run(context, args);
                if ("spec_check".equals(action)) {
                    ObjectNode result = JSON.createObjectNode();
                    String error = ticketError(context, args);
                    result.put("status", error == null ? "VALID" : "BLOCKED");
                    if (error != null) result.put("error", error);
                    result.put("executionId", executionId).put("effectiveRuleSetHash", context.hash);
                    return result;
                }
                ObjectNode result = view(context, "spec".equals(action) && args.path("includeContent").asBoolean(false));
                if ("spec".equals(action) && args.has("specPath")) {
                    String selected = requiredText(args, "specPath"); ArrayNode specs = JSON.createArrayNode();
                    for (JsonNode spec : result.path("specs")) if (selected.equals(spec.path("path").asText())) specs.add(spec);
                    if (specs.isEmpty()) throw new IllegalArgumentException("Specification is not required by the current node: " + selected);
                    result.set("specs", specs);
                }
                if ("state".equals(action)) stateDetails(result, state, args);
                if ("context".equals(action)) result.set("projectContext", WorkspaceContext.projectContext(profiles, workspace, profile, args));
                if (args.has("knownSpecHashes")) markKnownSpecs(result, args);
                if ("spec".equals(action) && args.path("includeContent").asBoolean(false)) contentPages(result, args);
                states.save(workspace, profile, executionId, state);
                return result;
            }
        } catch (IOException e) { throw new IllegalStateException("Workflow storage failed: " + e.getMessage(), e); }
    }

    private JsonNode startupContext(Path workspace, String profile, JsonNode args) throws IOException {
        ObjectNode probe = WorkspaceContext.probe(profiles, workspace, profile, args);
        if (!"READY".equals(probe.path("status").asText())) return probe;
        ArrayNode candidates = states.activeExecutions(workspace, profile);
        if (candidates.size() == 1) {
            ObjectNode resume = args.deepCopy();
            resume.put("executionId", candidates.get(0).path("executionId").asText());
            return handle(resume);
        }
        probe.set("executions", candidates);
        if (candidates.size() > 1) {
            probe.put("status", "SELECT_EXECUTION").put("nextAction", "Call context with an explicit executionId");
        } else {
            probe.put("status", "IDLE");
            JsonNode map = profiles.json(workspace, profile, "spec-map.json");
            String intent = args.has("intent") ? requiredText(args, "intent") : "";
            String workflow = args.has("workflow") ? requiredText(args, "workflow") : resolveWorkflow(map, intent, args);
            probe.put("workflow", workflow);
            ObjectNode request = args.deepCopy(); request.put("workflow", workflow);
            probe.set("specs", statelessSpec(workspace, profile, request).path("specs"));
            probe.put("nextAction", "Read matched specifications; bootstrap only for an explicitly requested persistent workflow");
        }
        return probe;
    }

    private ObjectNode statelessSpec(Path workspace, String profile, JsonNode args) {
        JsonNode map = profiles.json(workspace, profile, "spec-map.json");
        String intent = args.has("intent") ? requiredText(args, "intent") : "";
        String workflow = args.has("workflow") ? requiredText(args, "workflow") : resolveWorkflow(map, intent, args);
        Set<String> references = new LinkedHashSet<String>();
        if (args.has("specPath")) {
            String requested = requiredText(args, "specPath");
            for (JsonNode mapping : map.path("workflows").path(workflow).path("nodes")) {
                for (JsonNode ref : mapping.path("baseSpec")) references.add(text(ref, "baseSpec reference"));
                for (JsonNode condition : mapping.path("conditionalSpec")) for (JsonNode ref : condition.path("spec")) references.add(text(ref, "conditionalSpec reference"));
            }
            for (JsonNode route : map.path("intentRoutes")) for (JsonNode ref : route.path("spec")) references.add(text(ref, "route specification"));
            if (!references.contains(requested)) throw new IllegalArgumentException("Specification is not declared in the selected Profile workflow: " + requested);
            references.clear(); references.add(requested);
        } else {
            for (JsonNode route : map.path("intentRoutes")) if (matches(route.path("whenAny"), intent)) {
                for (JsonNode ref : route.path("spec")) references.add(text(ref, "route specification"));
            }
            if (references.isEmpty() || args.has("nodeId")) {
                JsonNode definition = profiles.json(workspace, profile, "workflows/" + workflow + ".json");
                String node = args.has("nodeId") ? requiredText(args, "nodeId") : requiredText(definition.path("nodes").get(0), "id");
                JsonNode mapping = map.path("workflows").path(workflow).path("nodes").path(node);
                if (!mapping.isObject()) throw new IllegalArgumentException("Unknown node: " + node);
                for (JsonNode ref : mapping.path("baseSpec")) references.add(text(ref, "baseSpec reference"));
                for (JsonNode condition : mapping.path("conditionalSpec")) if (matches(condition.path("whenAny"), intent)) {
                    for (JsonNode ref : condition.path("spec")) references.add(text(ref, "conditionalSpec reference"));
                }
            }
        }
        ObjectNode result = JSON.createObjectNode().put("status", "STATELESS").put("profile", profile).put("workflow", workflow);
        ArrayNode specs = result.putArray("specs");
        for (String ref : references) {
            String body = profiles.text(workspace, profile, ref);
            ObjectNode spec = specs.addObject().put("path", ref).put("summary", summary(body)).put("sha256", hash(body));
            if (args.path("includeContent").asBoolean(false)) spec.put("content", body);
        }
        if (args.has("knownSpecHashes")) markKnownSpecs(result, args);
        if (args.path("includeContent").asBoolean(false)) contentPages(result, args);
        return result;
    }

    private boolean applyCurrentState(ObjectNode state, JsonNode args) {
        if (!args.has("currentState")) return false;
        if (!args.get("currentState").isObject()) throw new IllegalArgumentException("currentState must be an object");
        ObjectNode current = state.has("currentState") ? ((ObjectNode) state.get("currentState")).deepCopy() : JSON.createObjectNode();
        ObjectNode next = current.deepCopy();
        Iterator<String> fields = args.get("currentState").fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!java.util.Arrays.asList("goal", "design", "constraints", "context", "acceptance").contains(field)) throw new IllegalArgumentException("Unknown currentState field: " + field);
            next.set(field, args.get("currentState").get(field).deepCopy());
        }
        if (next.equals(current)) return false;
        if (next.has("goal")) state.put("intent", requiredText(next, "goal"));
        next.put("revision", current.path("revision").asInt() + 1).put("updatedAt", Instant.now().toString());
        state.set("currentState", next); return true;
    }

    private boolean contains(JsonNode values, JsonNode expected) {
        requireArray(values, "operationModes");
        for (JsonNode value : values) if (value.equals(expected)) return true;
        return false;
    }

    private void stateDetails(ObjectNode result, ObjectNode state, JsonNode args) {
        ObjectNode summary = result.putObject("state");
        summary.put("historyCount", state.path("history").size()).put("factCount", state.path("facts").size())
                .put("evidenceCount", state.path("evidence").size()).put("artifactCount", state.path("artifactPaths").size());
        if (args.has("section")) {
            String section = requiredText(args, "section");
            if (!java.util.Arrays.asList("history", "facts", "evidence", "artifactPaths", "currentState").contains(section)) throw new IllegalArgumentException("Unknown state section: " + section);
            JsonNode source = state.path(section); int offset = position(args, "offset", 0, Integer.MAX_VALUE);
            int limit = position(args, "limit", 20, 100); if (limit == 0) throw new IllegalArgumentException("limit must be positive");
            if (offset > source.size()) throw new IllegalArgumentException("offset exceeds state section size");
            int end = Math.min(source.size(), offset + limit);
            if (source.isArray()) {
                ArrayNode page = summary.putArray(section); for (int i = offset; i < end; i++) page.add(source.get(i));
            } else {
                ObjectNode page = summary.putObject(section); Iterator<String> names = source.fieldNames(); int index = 0;
                while (names.hasNext()) { String name = names.next(); if (index >= offset && index < end) page.set(name, source.get(name)); index++; }
            }
            summary.put("offset", offset).put("totalItems", source.size()).put("complete", end == source.size());
            if (end < source.size()) {
                ObjectNode next = args.deepCopy(); next.put("offset", end).put("limit", limit); summary.set("nextRead", next);
            }
        }
    }

    private void contentPages(ObjectNode result, JsonNode args) {
        int offset = position(args, "offset", 0, Integer.MAX_VALUE);
        int limit = position(args, "limit", 4096, 8192); if (limit == 0) throw new IllegalArgumentException("limit must be positive");
        for (JsonNode item : result.path("specs")) {
            if (!item.has("content")) continue;
            ObjectNode spec = (ObjectNode) item; String body = spec.get("content").textValue();
            if (offset > body.length() || (offset > 0 && offset < body.length() && Character.isLowSurrogate(body.charAt(offset)) && Character.isHighSurrogate(body.charAt(offset - 1)))) throw new IllegalArgumentException("offset must be a valid specification character boundary");
            int end = (int) Math.min(body.length(), (long) offset + limit);
            if (end < body.length() && end > offset && Character.isHighSurrogate(body.charAt(end - 1)) && Character.isLowSurrogate(body.charAt(end))) end--;
            if (end == offset && end < body.length()) throw new IllegalArgumentException("limit must fit a complete Unicode character");
            spec.put("content", body.substring(offset, end)).put("offset", offset).put("totalLength", body.length()).put("complete", end == body.length());
            if (end < body.length()) {
                ObjectNode next = args.deepCopy(); next.put("action", "spec").put("specPath", spec.path("path").asText()).put("offset", end).put("limit", limit);
                spec.set("nextRead", next);
            }
        }
    }

    private int position(JsonNode args, String field, int defaultValue, int maximum) {
        if (!args.has(field)) return defaultValue;
        JsonNode number = args.get(field);
        if (!number.isIntegralNumber() || !number.canConvertToInt() || number.intValue() < 0 || number.intValue() > maximum) throw new IllegalArgumentException(field + " is outside its integer range");
        return number.intValue();
    }

    private void markKnownSpecs(ObjectNode result, JsonNode args) {
        requiredText(args, "contextEpoch");
        if (!args.get("knownSpecHashes").isObject()) throw new IllegalArgumentException("knownSpecHashes must be an object");
        result.put("contextEpoch", requiredText(args, "contextEpoch"));
        for (JsonNode spec : result.path("specs")) {
            JsonNode known = args.get("knownSpecHashes").get(spec.path("path").asText());
            if (known != null && known.isTextual() && known.textValue().equals(spec.path("sha256").asText())) {
                ((ObjectNode) spec).remove("content"); ((ObjectNode) spec).put("unchanged", true);
            }
        }
    }

    private ArrayNode missingArtifacts(Context context) {
        ArrayNode missing = JSON.createArrayNode();
        Set<String> checked = new LinkedHashSet<String>();
        for (JsonNode record : context.state.path("history")) {
            if (record.path("invalidated").asBoolean(false) || !"DONE".equals(record.path("status").asText())) continue;
            String producer = record.path("nodeId").asText();
            Set<String> paths = new LinkedHashSet<String>();
            for (JsonNode node : context.definition.path("nodes")) if (producer.equals(node.path("id").asText())) {
                for (JsonNode path : node.path("expectedArtifacts")) paths.add(text(path, "expectedArtifacts entry"));
            }
            for (JsonNode path : record.path("artifactPaths")) paths.add(text(path, "artifactPaths entry"));
            for (String path : paths) if (checked.add(producer + "\n" + path) && !Files.isRegularFile(artifact(context.workspace, path))) {
                missing.addObject().put("path", path).put("producerNodeId", producer);
            }
        }
        return missing;
    }

    private void reopen(Path workspace, String profile, ObjectNode state, JsonNode args) {
        requireArray(args.get("reopenNodeIds"), "reopenNodeIds");
        if (!args.path("recoveryConfirmed").isBoolean() || !args.get("recoveryConfirmed").booleanValue()) throw new IllegalArgumentException("Reopening requires explicit user recoveryConfirmed=true");
        Context context = context(workspace, profile, state);
        int index = context.definition.path("nodes").size();
        for (JsonNode id : args.get("reopenNodeIds")) {
            String nodeId = text(id, "reopenNodeIds entry"); int found = -1;
            for (int i = 0; i < context.definition.path("nodes").size(); i++) if (nodeId.equals(context.definition.path("nodes").get(i).path("id").asText())) found = i;
            if (found < 0 || found >= state.path("nodeIndex").asInt()) throw new IllegalArgumentException("Only completed nodes may be reopened: " + nodeId);
            index = Math.min(index, found);
        }
        if (index == context.definition.path("nodes").size()) throw new IllegalArgumentException("reopenNodeIds must not be empty");
        Set<String> retained = new LinkedHashSet<String>();
        for (int i = 0; i < index; i++) retained.add(context.definition.path("nodes").get(i).path("id").asText());
        ArrayNode history = (ArrayNode) state.get("history");
        for (JsonNode record : history) if (!retained.contains(record.path("nodeId").asText())) ((ObjectNode) record).put("invalidated", true);
        ObjectNode recovery = history.addObject().put("status", "REOPENED").put("recordedAt", Instant.now().toString()).put("recoveryConfirmed", true);
        recovery.set("reopenNodeIds", args.get("reopenNodeIds").deepCopy());
        state.put("nodeIndex", index).put("status", "READY");
        state.putObject("facts"); state.putObject("evidence"); state.putArray("artifactPaths");
        for (JsonNode record : history) if (!record.path("invalidated").asBoolean(false) && "DONE".equals(record.path("status").asText())) {
            merge(state, record, "facts"); merge(state, record, "evidence");
            for (JsonNode path : record.path("artifactPaths")) ((ArrayNode) state.get("artifactPaths")).add(path);
        }
        state.remove(java.util.Arrays.asList("issuedTicket", "issuedNodeId", "issuedRuleSetHash", "decision", "currentNodeId", "blockingReason"));
    }

    private ObjectNode bootstrap(Path workspace, String profile, JsonNode args) throws IOException {
        JsonNode map = profiles.json(workspace, profile, "spec-map.json");
        String intent = args.has("intent") ? requiredText(args, "intent") : "";
        if (args.has("executionId")) {
            ObjectNode resume = args.deepCopy(); resume.put("action", "context"); return (ObjectNode) handle(resume);
        }
        String source = args.path("workflowConfirmationSource").asText();
        if (!args.path("workflowConfirmedByUser").isBoolean() || !args.get("workflowConfirmedByUser").booleanValue()
                || !("user_explicit_request".equals(source) || "user_confirmed_proposal".equals(source))) {
            throw new IllegalArgumentException("Starting a persistent workflow requires explicit user confirmation and its source");
        }
        String workflow = args.has("workflow") ? requiredText(args, "workflow") : resolveWorkflow(map, intent, args);
        ObjectNode state = JSON.createObjectNode();
        String executionId = UUID.randomUUID().toString();
        state.put("executionId", executionId).put("profile", profile).put("workflow", workflow)
                .put("intent", intent).put("nodeIndex", 0).put("status", "READY");
        state.putObject("facts"); state.putObject("evidence"); state.putArray("artifactPaths"); state.putArray("history");
        state.put("createdAt", Instant.now().toString());
        state.putObject("workflowConfirmation").put("approved", true).put("source", source);
        if (args.has("parameters")) {
            if (!args.get("parameters").isObject()) throw new IllegalArgumentException("parameters must be an object");
            state.set("parameters", args.get("parameters").deepCopy());
        }
        if (args.has("operationMode")) state.put("operationMode", requiredText(args, "operationMode"));
        state.putObject("currentState").put("goal", intent).put("revision", 0);
        applyCurrentState(state, args);
        try (WorkflowStateStore.Lock ignored = states.lock(workspace, profile, executionId)) {
            Context context = context(workspace, profile, state);
            ArrayNode nodeOrder = state.putArray("nodeOrder");
            for (JsonNode node : context.definition.get("nodes")) nodeOrder.add(requiredText(node, "id"));
            ObjectNode result = view(context, false);
            states.save(workspace, profile, executionId, state);
            return result;
        }
    }

    private String resolveWorkflow(JsonNode map, String intent, JsonNode args) {
        Set<String> candidates = new LinkedHashSet<String>();
        for (JsonNode route : map.path("intentRoutes")) {
            if (matches(route.path("whenAny"), intent)) candidates.add(requiredText(route, "workflow"));
        }
        for (JsonNode route : map.path("workflowResolution").path("argumentRoutes")) {
            for (JsonNode param : route.path("whenAnyParam")) {
                JsonNode supplied = args.path("parameters").path(text(param, "route parameter"));
                if (!supplied.isMissingNode() && !supplied.isNull()) candidates.add(requiredText(route, "workflow"));
            }
        }
        if (candidates.size() > 1) throw new IllegalArgumentException("Ambiguous workflow; select workflow explicitly: " + candidates);
        if (candidates.size() == 1) return candidates.iterator().next();
        return requiredText(map.path("workflowResolution"), "defaultWorkflow");
    }

    private Context context(Path workspace, String profile, ObjectNode state) {
        String workflow = requiredText(state, "workflow");
        if (!workflow.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid workflow identifier");
        JsonNode map = profiles.json(workspace, profile, "spec-map.json");
        JsonNode definition = ExecutionParameters.bind(profiles.json(workspace, profile, "workflows/" + workflow + ".json"), state);
        if (state.has("operationMode")) {
            ObjectNode filtered = definition.deepCopy(); ArrayNode nodes = filtered.putArray("nodes");
            ArrayNode skipped = state.putArray("skippedNodes");
            for (JsonNode node : definition.path("nodes")) {
                if (!node.has("operationModes") || node.get("operationModes").isEmpty() || contains(node.get("operationModes"), state.get("operationMode"))) nodes.add(node);
                else skipped.addObject().put("nodeId", requiredText(node, "id")).put("status", "SKIPPED");
            }
            definition = filtered;
        }
        validateNodes(map, workflow, definition);
        Context context = new Context(workspace, profile, state, map, definition);
        int index = state.path("nodeIndex").asInt(-1);
        if (index < 0 || index > definition.get("nodes").size()) throw new IllegalStateException("Saved node position is invalid");
        context.node = index == definition.get("nodes").size() ? null : definition.get("nodes").get(index);
        if (state.has("nodeOrder")) {
            if (index == state.get("nodeOrder").size() && index != definition.get("nodes").size()) {
                throw new IllegalStateException("Completed workflow node order changed; bootstrap a new execution");
            }
            for (int i = 0; i <= index && i < state.get("nodeOrder").size(); i++) {
                if (i >= definition.get("nodes").size() || !state.get("nodeOrder").get(i).asText().equals(definition.get("nodes").get(i).path("id").asText())) {
                    throw new IllegalStateException("Workflow node order changed; bootstrap a new execution");
                }
            }
            if (context.node != null && state.has("currentNodeId") && !requiredText(context.node, "id").equals(state.path("currentNodeId").asText())) {
                throw new IllegalStateException("Saved current node no longer matches workflow");
            }
        }
        context.specs = JSON.createArrayNode();
        if (context.node != null) {
            JsonNode mapping = map.path("workflows").path(workflow).path("nodes").path(requiredText(context.node, "id"));
            Set<String> references = new LinkedHashSet<String>();
            for (JsonNode ref : mapping.path("baseSpec")) references.add(text(ref, "baseSpec reference"));
            for (JsonNode condition : mapping.path("conditionalSpec")) {
                if (matches(condition.path("whenAny"), state.path("intent").asText())) {
                    for (JsonNode ref : condition.path("spec")) references.add(text(ref, "conditionalSpec reference"));
                }
            }
            if (references.isEmpty()) throw new IllegalArgumentException("Current node must declare at least one specification");
            for (JsonNode ref : context.node.path("requiredSpec")) if (!references.contains(text(ref, "requiredSpec reference"))) {
                throw new IllegalArgumentException("Node requiredSpec is missing from its specification map: " + ref.textValue());
            }
            for (String reference : references) {
                String body = profiles.text(workspace, profile, reference);
                ObjectNode spec = context.specs.addObject();
                spec.put("path", reference).put("summary", summary(body)).put("sha256", hash(body)).put("content", body);
            }
        }
        ObjectNode rules = JSON.createObjectNode();
        rules.set("specMap", map); rules.set("workflow", definition);
        rules.set("specs", context.specs);
        ObjectNode gateResources = rules.putObject("gateResources");
        if (context.node != null) for (JsonNode reference : context.node.path("completionGates")) {
            JsonNode gate = ExecutionParameters.bind(DeclarativeGates.resolve(profiles, workspace, profile, reference), state);
            String type = requiredText(gate, "type");
            if (!("fact".equals(type) || "evidence".equals(type) || "artifact".equals(type) || DeclarativeGates.supports(type))) throw new IllegalArgumentException("Unsupported completion gate: " + type);
            if ("fact".equals(type) || "evidence".equals(type)) checkAssertion(JSON.createObjectNode(), gate, "fact".equals(type) ? "facts." : "evidence.", new ArrayList<String>());
            for (String path : DeclarativeGates.resources(profiles, workspace, profile, gate)) {
                gateResources.put(path, hash(profiles.text(workspace, profile, path)));
            }
            if (reference.isTextual()) gateResources.set(reference.textValue(), gate);
        }
        if (profiles.exists(workspace, profile, "rules.json")) rules.set("rules", profiles.json(workspace, profile, "rules.json"));
        context.hash = hash(rules.toString());
        return context;
    }

    private ObjectNode view(Context context, boolean includeContent) {
        ObjectNode state = context.state;
        ObjectNode result = JSON.createObjectNode();
        result.put("executionId", state.path("executionId").asText()).put("profile", context.profile)
                .put("workflow", state.path("workflow").asText()).put("effectiveRuleSetHash", context.hash);
        ObjectNode continuation = result.putObject("continuation");
        continuation.put("executionId", state.path("executionId").asText()).put("profile", context.profile)
                .put("workspaceRoot", context.workspace.toString());
        result.put("workspaceRoot", context.workspace.toString()).put("packageMode", "compact");
        if (state.has("currentState")) result.set("currentState", state.get("currentState").deepCopy());
        ArrayNode missing = missingArtifacts(context);
        if (!missing.isEmpty()) {
            result.put("status", "BLOCKED").put("blockingReason", "Completed node artifacts are missing; request user confirmation before reopening producer nodes");
            result.set("missingArtifacts", missing); continuation.put("action", "context"); return result;
        }
        if (context.node == null) {
            state.put("status", "DONE"); state.remove("currentNodeId");
            result.put("status", "DONE"); continuation.put("action", "state"); return result;
        }
        String nodeId = requiredText(context.node, "id");
        state.put("currentNodeId", nodeId);
        if (!context.hash.equals(state.path("issuedRuleSetHash").asText())
                || !nodeId.equals(state.path("issuedNodeId").asText())) {
            state.put("issuedTicket", UUID.randomUUID().toString() + UUID.randomUUID().toString());
            state.put("issuedRuleSetHash", context.hash).put("issuedNodeId", nodeId);
        }
        result.put("status", state.path("status").asText()).put("nodeId", nodeId)
                .put("specTicket", state.path("issuedTicket").asText());
        if (state.has("blockingReason")) result.set("blockingReason", state.get("blockingReason"));
        ObjectNode node = context.node.deepCopy();
        ArrayNode gates = node.putArray("completionGates");
        for (JsonNode reference : context.node.path("completionGates")) gates.add(ExecutionParameters.bind(DeclarativeGates.resolve(profiles, context.workspace, context.profile, reference), state));
        result.set("node", node);
        Set<String> instructions = new LinkedHashSet<String>();
        for (JsonNode instruction : context.definition.path("executionInstruction")) instructions.add(text(instruction, "workflow execution instruction"));
        for (JsonNode instruction : context.node.path("executionInstruction")) instructions.add(text(instruction, "node execution instruction"));
        result.set("executionInstruction", JSON.valueToTree(instructions));
        ObjectNode templates = result.putObject("actionParameterTemplates");
        for (JsonNode tool : context.node.path("allowedTools")) {
            String name = text(tool, "allowed tool"); JsonNode template = context.map.path("actionParameterTemplates").get(name);
            if (template != null) templates.set(name, ExecutionParameters.bind(template, state));
        }
        ArrayNode specs = context.specs.deepCopy();
        if (!includeContent) for (JsonNode spec : specs) ((ObjectNode) spec).remove("content");
        result.set("specs", specs);
        ObjectNode done = result.putObject("runTemplate");
        done.put("action", "run").put("workspaceRoot", context.workspace.toString()).put("profile", context.profile)
                .put("executionId", state.path("executionId").asText()).put("nodeId", nodeId)
                .put("specTicket", state.path("issuedTicket").asText()).put("status", "DONE");
        if ("ASK_USER".equals(context.node.path("type").asText())) done.putObject("decision").putNull("approved");
        else { done.putObject("facts"); done.putObject("evidence"); done.putArray("artifactPaths"); }
        continuation.put("action", "run").put("nodeId", nodeId).put("specTicket", state.path("issuedTicket").asText());
        result.put("nextAction", "Execute the current node and submit runTemplate; read specification content only when needed");
        return result;
    }

    private ObjectNode run(Context context, JsonNode args) throws IOException {
        ObjectNode candidate = context.state.deepCopy();
        boolean changed = applyCurrentState(candidate, args);
        if (changed) context = context(context.workspace, context.profile, candidate);
        String error = ticketError(context, args);
        if (error != null) {
            if (changed) states.save(context.workspace, context.profile, candidate.path("executionId").asText(), candidate);
            return blocked(context, error);
        }
        if (!missingArtifacts(context).isEmpty()) return view(context, false);
        String status = requiredText(args, "status");
        if (!("DONE".equals(status) || "BLOCKED".equals(status))) throw new IllegalArgumentException("Run status must be DONE or BLOCKED");
        merge(candidate, args, "facts"); merge(candidate, args, "evidence");
        if (args.has("artifactPaths")) {
            requireArray(args.get("artifactPaths"), "artifactPaths");
            Set<String> paths = new LinkedHashSet<String>();
            for (JsonNode item : candidate.get("artifactPaths")) paths.add(text(item, "artifactPaths entry"));
            for (JsonNode item : args.get("artifactPaths")) { String path = text(item, "artifactPaths entry"); artifact(context.workspace, path); paths.add(path); }
            ArrayNode artifacts = candidate.putArray("artifactPaths"); for (String path : paths) artifacts.add(path);
        }
        List<String> failures = new ArrayList<String>();
        if ("BLOCKED".equals(status)) {
            String reason = requiredText(args, "blockingReason");
            candidate.put("blockingReason", reason);
            failures.add(reason);
        } else candidate.remove("blockingReason");
        if ("RUN_NODE".equals(context.node.path("type").asText())) {
            if (!args.path("facts").isObject()) failures.add("RUN_NODE requires facts object");
            if (!args.path("evidence").isObject() || args.get("evidence").isEmpty()) failures.add("RUN_NODE requires nonempty evidence object");
            if (!args.path("artifactPaths").isArray()) failures.add("RUN_NODE requires artifactPaths array");
        }
        if ("ASK_USER".equals(context.node.path("type").asText()) && "DONE".equals(status)) {
            JsonNode approved = args.path("decision").path("approved");
            if (args.path("decision").path("autoApproved").asBoolean(false)) {
                if (!approved.isBoolean() || !approved.booleanValue()) failures.add("Automatic approval requires decision.approved=true");
                if (!args.path("skipConfirm").isBoolean() || !args.get("skipConfirm").booleanValue()) failures.add("Automatic approval requires explicit skipConfirm=true");
                else AutoDecisionPolicy.evaluate(context.workspace, context.node.path("autoDecisionPolicy"), failures);
            } else {
                if (!approved.isBoolean()) failures.add("ASK_USER requires decision.approved boolean");
                else if (!approved.booleanValue()) failures.add("User approval was declined");
            }
            if (args.path("decision").isObject()) candidate.set("decision", args.get("decision").deepCopy());
            if (args.path("decision").path("autoApproved").asBoolean(false) && failures.isEmpty()) ((ObjectNode) candidate.get("decision")).put("source", "profile.autoDecisionPolicy");
        }
        if ("DONE".equals(status)) {
        for (JsonNode required : context.node.path("requiredFacts")) {
            String path = text(required, "requiredFacts entry");
            if (!path.startsWith("facts.")) throw new IllegalArgumentException("Required fact path must start with facts.");
            JsonNode value = value(args, path);
            if (value.isMissingNode() || value.isNull()) failures.add("Required fact is missing: " + path);
        }
        for (JsonNode assertion : context.node.path("factAssertions")) checkAssertion((ObjectNode) args, assertion, "facts.", failures);
        for (JsonNode path : context.node.path("expectedArtifacts")) checkArtifact(context.workspace, (ObjectNode) args, text(path, "expectedArtifacts entry"), failures);
        for (JsonNode reference : context.node.path("completionGates")) {
            JsonNode gate = ExecutionParameters.bind(DeclarativeGates.resolve(profiles, context.workspace, context.profile, reference), candidate);
            String type = requiredText(gate, "type");
            if ("fact".equals(type)) checkAssertion((ObjectNode) args, gate, "facts.", failures);
            else if ("evidence".equals(type)) checkAssertion((ObjectNode) args, gate, "evidence.", failures);
            else if ("artifact".equals(type)) checkArtifact(context.workspace, (ObjectNode) args, requiredText(gate, "path"), failures);
            else if (DeclarativeGates.supports(type)) DeclarativeGates.validate(profiles, context.workspace, context.profile, gate, failures, candidate);
            else throw new IllegalArgumentException("Unsupported completion gate: " + type);
        }
        }
        ObjectNode record = ((ArrayNode) candidate.get("history")).addObject();
        record.put("nodeId", requiredText(context.node, "id")).put("status", failures.isEmpty() ? "DONE" : "BLOCKED")
                .put("recordedAt", Instant.now().toString()).put("effectiveRuleSetHash", context.hash);
        if (candidate.has("decision")) record.set("decision", candidate.get("decision").deepCopy());
        for (String field : new String[]{"facts", "evidence", "artifactPaths"}) if (args.has(field)) record.set(field, args.get(field).deepCopy());
        if (failures.isEmpty()) {
            candidate.put("nodeIndex", candidate.path("nodeIndex").intValue() + 1).put("status", "READY");
            candidate.remove(java.util.Arrays.asList("issuedTicket", "issuedNodeId", "issuedRuleSetHash", "decision", "currentNodeId", "blockingReason"));
        } else candidate.put("status", "BLOCKED").put("blockingReason", String.join("; ", failures));
        context.state = candidate;
        Context next = context(context.workspace, context.profile, candidate);
        ObjectNode result = view(next, false);
        ObjectNode delta = result.putObject("stateDelta");
        delta.put("nodeId", requiredText(context.node, "id")).put("status", failures.isEmpty() ? "DONE" : "BLOCKED");
        for (String field : new String[]{"facts", "evidence", "artifactPaths", "currentState"}) if (args.has(field)) delta.set(field, args.get(field).deepCopy());
        if (!failures.isEmpty()) result.set("errors", JSON.valueToTree(failures));
        states.save(context.workspace, context.profile, candidate.path("executionId").asText(), candidate);
        return result;
    }

    private ObjectNode blocked(Context context, String error) {
        ObjectNode result = JSON.createObjectNode();
        result.put("status", "BLOCKED").put("executionId", context.state.path("executionId").asText())
                .put("error", error).put("effectiveRuleSetHash", context.hash);
        if (context.node != null) result.put("nodeId", context.node.path("id").asText());
        result.putObject("continuation").put("action", "spec").put("executionId", context.state.path("executionId").asText())
                .put("profile", context.profile).put("workspaceRoot", context.workspace.toString());
        return result;
    }

    private String ticketError(Context context, JsonNode args) {
        if (context.node == null) return "Execution is already DONE";
        if (!requiredText(context.node, "id").equals(args.path("nodeId").asText())) return "nodeId is not the current node";
        if (!args.path("specTicket").isTextual() || !context.state.path("issuedTicket").isTextual()
                || !MessageDigest.isEqual(args.get("specTicket").textValue().getBytes(StandardCharsets.UTF_8),
                context.state.get("issuedTicket").textValue().getBytes(StandardCharsets.UTF_8))) return "Invalid specification ticket";
        if (!context.hash.equals(context.state.path("issuedRuleSetHash").asText())) return "Specification or workflow changed; request spec again";
        return null;
    }

    private void checkAssertion(ObjectNode state, JsonNode assertion, String prefix, List<String> failures) {
        String path = requiredText(assertion, "path");
        if (!path.startsWith(prefix)) throw new IllegalArgumentException("Gate path must start with " + prefix);
        String operator = requiredText(assertion, "operator");
        JsonNode actual = value(state, path);
        boolean passed;
        if (assertion.has("when")) {
            List<String> unmet = new ArrayList<String>();
            checkAssertion(state, assertion.get("when"), prefix, unmet);
            if (!unmet.isEmpty()) {
                ObjectNode definition = ((ObjectNode) assertion).deepCopy(); definition.remove("when");
                checkAssertion(JSON.createObjectNode(), definition, prefix, new ArrayList<String>());
                return;
            }
        }
        if ("exists".equals(operator)) passed = !actual.isMissingNode() && !actual.isNull();
        else if ("notEmpty".equals(operator)) passed = !actual.isMissingNode() && !actual.isNull()
                && (!actual.isTextual() || !actual.textValue().isEmpty()) && (!actual.isContainerNode() || actual.size() > 0);
        else if ("equals".equals(operator)) {
            if (!assertion.has("value")) throw new IllegalArgumentException("equals assertion requires value");
            passed = actual.equals(assertion.get("value"));
        } else if ("in".equals(operator)) {
            requireArray(assertion.path("value"), "in assertion value"); passed = false;
            for (JsonNode allowed : assertion.get("value")) if (actual.equals(allowed)) passed = true;
        } else if ("empty".equals(operator)) passed = !actual.isMissingNode() && !actual.isNull()
                && ((actual.isTextual() && actual.textValue().isEmpty()) || (actual.isContainerNode() && actual.isEmpty()));
        else throw new IllegalArgumentException("Unsupported assertion operator: " + operator);
        if (!passed) failures.add("Gate failed: " + path + " " + operator);
    }

    private void checkArtifact(Path workspace, ObjectNode state, String path, List<String> failures) {
        Path artifact = artifact(workspace, path);
        boolean submitted = false;
        for (JsonNode declared : state.path("artifactPaths")) if (path.equals(declared.asText())) submitted = true;
        if (!submitted || !Files.isRegularFile(artifact)) failures.add("Required artifact must be submitted and exist: " + path);
    }

    private Path artifact(Path workspace, String relative) {
        Path path = Paths.get(relative);
        if (relative.isEmpty() || path.isAbsolute()) throw new IllegalArgumentException("Artifact path must be workspace relative");
        Path resolved = workspace.resolve(path).normalize();
        if (!resolved.startsWith(workspace)) throw new IllegalArgumentException("Artifact path escapes workspace");
        try {
            if (Files.exists(resolved) && !resolved.toRealPath().startsWith(workspace)) throw new IllegalArgumentException("Artifact symlink escapes workspace");
        } catch (IOException e) { throw new IllegalArgumentException("Cannot inspect artifact: " + relative, e); }
        return resolved;
    }

    private void merge(ObjectNode state, JsonNode args, String field) {
        if (!args.has(field)) return;
        if (!args.get(field).isObject()) throw new IllegalArgumentException(field + " must be an object");
        ((ObjectNode) state.get(field)).setAll((ObjectNode) args.get(field));
    }

    private JsonNode value(JsonNode root, String path) {
        for (String part : path.split("\\.")) root = root.path(part);
        return root;
    }

    private JsonNode validateProfile(Path workspace, String profile, JsonNode args) {
        JsonNode map = profiles.json(workspace, profile, "spec-map.json");
        if (!map.path("workflows").isObject() || map.get("workflows").isEmpty()) throw new IllegalArgumentException("spec-map workflows must be a nonempty object");
        String defaultWorkflow = requiredText(map.path("workflowResolution"), "defaultWorkflow");
        if (!map.get("workflows").has(defaultWorkflow)) throw new IllegalArgumentException("Default workflow is not declared");
        for (JsonNode route : map.path("intentRoutes")) {
            requireArray(route.path("whenAny"), "intentRoutes.whenAny");
            if (route.get("whenAny").isEmpty()) throw new IllegalArgumentException("Intent route requires keywords");
            if (!map.get("workflows").has(requiredText(route, "workflow"))) throw new IllegalArgumentException("Intent route workflow is not declared");
        }
        Iterator<String> names = map.get("workflows").fieldNames();
        int count = 0;
        while (names.hasNext()) {
            String workflow = names.next();
            JsonNode definition = profiles.json(workspace, profile, "workflows/" + workflow + ".json");
            validateNodes(map, workflow, definition);
            for (JsonNode node : definition.get("nodes")) {
                ObjectNode state = JSON.createObjectNode();
                if (args.has("parameters")) state.set("parameters", args.get("parameters"));
                state.put("executionId", args.has("executionId") ? requiredText(args, "executionId") : "validation");
                state.put("workflow", workflow).put("intent", "").put("nodeIndex", countIndex(definition.get("nodes"), node));
                context(workspace, profile, state);
                for (JsonNode path : node.path("expectedArtifacts")) artifact(workspace, text(path, "expectedArtifacts entry"));
                for (JsonNode reference : node.path("completionGates")) {
                    JsonNode gate = DeclarativeGates.resolve(profiles, workspace, profile, reference);
                    if ("artifact".equals(gate.path("type").asText())) artifact(workspace, requiredText(gate, "path"));
                    for (String resource : DeclarativeGates.resources(profiles, workspace, profile, gate)) profiles.text(workspace, profile, resource);
                }
                JsonNode mapping = map.path("workflows").path(workflow).path("nodes").path(node.path("id").asText());
                for (JsonNode conditional : mapping.path("conditionalSpec")) {
                    requireArray(conditional.path("whenAny"), "conditionalSpec.whenAny");
                    requireArray(conditional.path("spec"), "conditionalSpec.spec");
                    for (JsonNode ref : conditional.get("spec")) profiles.text(workspace, profile, text(ref, "conditionalSpec reference"));
                }
            }
            count++;
        }
        return JSON.createObjectNode().put("status", "VALID").put("workflows", count);
    }

    private int countIndex(JsonNode nodes, JsonNode target) {
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i) == target) return i;
        throw new IllegalArgumentException("Node missing from workflow");
    }

    private void validateNodes(JsonNode map, String workflow, JsonNode definition) {
        if (definition.has("executionInstruction")) requireArray(definition.get("executionInstruction"), "workflow executionInstruction");
        requireArray(definition.path("nodes"), "workflow.nodes");
        if (definition.get("nodes").isEmpty()) throw new IllegalArgumentException("Workflow must have nodes");
        Set<String> ids = new LinkedHashSet<String>();
        for (JsonNode node : definition.get("nodes")) {
            String id = requiredText(node, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate node: " + id);
            String type = requiredText(node, "type");
            if (!("RUN_NODE".equals(type) || "ASK_USER".equals(type))) throw new IllegalArgumentException("Unsupported node type: " + type);
            requiredText(node, "title"); requiredText(node, "objective"); requiredText(node, "prompt");
            JsonNode mapping = map.path("workflows").path(workflow).path("nodes").path(id);
            if (!mapping.isObject()) throw new IllegalArgumentException("Missing specification map for node: " + id);
            requireArray(mapping.path("baseSpec"), "baseSpec");
            for (JsonNode required : node.path("requiredFacts")) {
                if (!text(required, "requiredFacts entry").startsWith("facts.")) throw new IllegalArgumentException("Required fact path must start with facts.");
            }
            List<String> ignored = new ArrayList<String>();
            for (JsonNode assertion : node.path("factAssertions")) checkAssertion(JSON.createObjectNode(), assertion, "facts.", ignored);
            for (JsonNode expected : node.path("expectedArtifacts")) text(expected, "expectedArtifacts entry");
            for (JsonNode gate : node.path("completionGates")) {
                if (gate.isTextual()) continue;
                String gateType = requiredText(gate, "type");
                if ("fact".equals(gateType)) checkAssertion(JSON.createObjectNode(), gate, "facts.", ignored);
                else if ("evidence".equals(gateType)) checkAssertion(JSON.createObjectNode(), gate, "evidence.", ignored);
                else if ("artifact".equals(gateType)) requiredText(gate, "path");
                else if (!DeclarativeGates.supports(gateType)) throw new IllegalArgumentException("Unsupported completion gate: " + gateType);
            }
            for (String field : new String[] {"executionInstruction", "requiredSpec", "operationModes", "requiredFacts", "factAssertions", "expectedArtifacts", "completionGates"}) {
                if (node.has(field)) requireArray(node.get(field), field);
            }
            for (JsonNode mode : node.path("operationModes")) text(mode, "operation mode");
        }
    }

    private boolean matches(JsonNode terms, String intent) {
        requireArray(terms, "whenAny");
        for (JsonNode term : terms) if (intent.toLowerCase(Locale.ROOT).contains(text(term, "intent keyword").toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private Path workspace(JsonNode args) {
        String value;
        if (args.has("workspaceRoot")) value = requiredText(args, "workspaceRoot");
        else { value = System.getenv("SPECUNFOLD_WORKSPACE_ROOT"); if (value == null || value.isEmpty()) value = System.getProperty("user.dir"); }
        try {
            Path path = Paths.get(value).toRealPath();
            if (!Files.isDirectory(path)) throw new IllegalArgumentException("workspaceRoot must be an existing directory");
            return path;
        } catch (IOException e) { throw new IllegalArgumentException("workspaceRoot must be an existing directory: " + value, e); }
    }

    private String summary(String body) {
        StringBuilder paragraph = new StringBuilder();
        for (String line : body.split("\r?\n")) {
            if (line.trim().startsWith("#")) continue;
            if (line.trim().isEmpty()) { if (paragraph.length() > 0) break; else continue; }
            if (paragraph.length() > 0) paragraph.append(' ');
            paragraph.append(line.trim());
        }
        String text = paragraph.toString();
        return text.length() > 240 ? text.substring(0, 240) : text;
    }
    private String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(); for (byte value : digest) result.append(String.format("%02x", value & 255)); return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String requiredText(JsonNode object, String field) { return text(object.path(field), field); }
    private static String text(JsonNode value, String label) {
        if (!value.isTextual() || value.textValue().trim().isEmpty()) throw new IllegalArgumentException(label + " must be a nonempty string");
        return value.textValue();
    }
    private static void requireArray(JsonNode value, String label) {
        if (!value.isArray()) throw new IllegalArgumentException(label + " must be an array");
    }
    private static final class Context {
        final Path workspace; final String profile; ObjectNode state;
        final JsonNode map; final JsonNode definition;
        JsonNode node; ArrayNode specs; String hash;
        Context(Path workspace, String profile, ObjectNode state, JsonNode map, JsonNode definition) {
            this.workspace = workspace; this.profile = profile; this.state = state; this.map = map; this.definition = definition;
        }
    }
}
