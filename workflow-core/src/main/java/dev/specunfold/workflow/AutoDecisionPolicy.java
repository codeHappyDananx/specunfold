package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Checks explicit Profile conditions; the caller must separately require the user's skip-confirm authorization. */
public final class AutoDecisionPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AutoDecisionPolicy() { }

    public static boolean evaluate(Path workspace, JsonNode policy, List<String> failures) {
        JsonNode conditions = array(policy, "conditions");
        if (conditions.isEmpty()) throw new IllegalArgumentException("Automatic decision policy requires conditions");
        int initialFailures = failures.size();
        for (JsonNode condition : conditions) {
            String type = field(condition, "type");
            if (!("no_blocking_items".equals(type) || "path_in".equals(type) || "path_not_in".equals(type))) {
                throw new IllegalArgumentException("Unsupported automatic decision condition: " + type);
            }
            String artifact = field(condition, "artifact");
            Path path = ProfileRepository.inside(workspace, ProfileRepository.relative(artifact));
            if (!Files.isRegularFile(path)) { failures.add("Automatic decision source is missing: " + artifact); continue; }
            JsonNode source;
            try { source = JSON.readTree(path.toFile()); }
            catch (IOException e) { failures.add("Automatic decision source is invalid JSON: " + artifact); continue; }
            if (source == null) { failures.add("Automatic decision source is empty: " + artifact); continue; }
            if ("no_blocking_items".equals(type)) noBlockingItems(source, condition, failures);
            else {
                String selectedPath = field(condition, "path");
                JsonNode actual = dotted(source, selectedPath);
                if (actual.isMissingNode() || actual.isNull()) { failures.add("Automatic decision value is missing: " + selectedPath); continue; }
                JsonNode values = array(condition, "values");
                if (values.isEmpty()) throw new IllegalArgumentException("Automatic decision values must not be empty");
                boolean included = false;
                for (JsonNode allowed : values) {
                    if (!allowed.isValueNode()) throw new IllegalArgumentException("Automatic decision values must be JSON scalars");
                    included |= actual.equals(allowed);
                }
                if ("path_in".equals(type) != included) failures.add("Automatic decision condition failed: " + selectedPath + " " + type);
            }
        }
        return failures.size() == initialFailures;
    }

    private static void noBlockingItems(JsonNode source, JsonNode condition, List<String> failures) {
        JsonNode paths = array(condition, "paths"), fields = array(condition, "blockingFields");
        JsonNode severities = array(condition, "blockingSeverities");
        if (paths.isEmpty()) throw new IllegalArgumentException("Blocking item paths must not be empty");
        if (fields.isEmpty() && severities.isEmpty()) throw new IllegalArgumentException("Blocking item rules must not be empty");
        String severityField = severities.isEmpty() ? null : field(condition, "severityField");
        for (JsonNode selected : paths) {
            String selectedPath = text(selected, "blocking item path");
            JsonNode items = dotted(source, selectedPath);
            if (!items.isArray()) { failures.add("Blocking item path must select an array: " + selectedPath); continue; }
            for (JsonNode item : items) {
                boolean blocking = false;
                if (item.isTextual()) {
                    for (JsonNode severity : severities) {
                        blocking |= item.textValue().toLowerCase(Locale.ROOT).contains(text(severity, "blocking severity").toLowerCase(Locale.ROOT));
                    }
                } else if (item.isObject()) {
                    for (JsonNode name : fields) {
                        JsonNode value = dotted(item, text(name, "blocking field"));
                        if (!value.isMissingNode() && !value.isBoolean()) throw new IllegalArgumentException("Blocking field must be boolean: " + name.textValue());
                        blocking |= value.isBoolean() && value.booleanValue();
                    }
                    if (severityField != null) {
                        JsonNode value = dotted(item, severityField);
                        if (!value.isMissingNode() && !value.isTextual()) throw new IllegalArgumentException("Severity field must be a string: " + severityField);
                        for (JsonNode severity : severities) {
                            blocking |= value.isTextual() && value.textValue().equalsIgnoreCase(text(severity, "blocking severity"));
                        }
                    }
                } else throw new IllegalArgumentException("Blocking items must be objects or strings");
                if (blocking) failures.add("Automatic decision has a blocking item: " + selectedPath);
            }
        }
    }

    private static JsonNode dotted(JsonNode root, String path) {
        for (String part : path.split("\\.")) root = root.path(part);
        return root;
    }
    private static JsonNode array(JsonNode object, String name) {
        JsonNode value = object.path(name);
        if (!value.isArray()) throw new IllegalArgumentException(name + " must be an array");
        return value;
    }
    private static String field(JsonNode object, String name) { return text(object.path(name), name); }
    private static String text(JsonNode value, String name) {
        if (!value.isTextual() || value.textValue().trim().isEmpty()) throw new IllegalArgumentException(name + " must be a nonempty string");
        return value.textValue();
    }
}
