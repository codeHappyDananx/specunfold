package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Binds Profile templates to parameters frozen when an execution starts. */
public final class ExecutionParameters {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^{}]+)\\}");

    private ExecutionParameters() { }

    public static JsonNode bind(JsonNode value, JsonNode frozenState) {
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), bind(field.getValue(), frozenState));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : value) result.add(bind(item, frozenState));
            return result;
        }
        if (!value.isTextual()) return value.deepCopy();
        Matcher matcher = VARIABLE.matcher(value.textValue());
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            JsonNode selected;
            if ("executionId".equals(name)) selected = frozenState.path("executionId");
            else if (name.startsWith("parameters.") && name.length() > "parameters.".length()) {
                selected = frozenState.path("parameters");
                for (String part : name.substring("parameters.".length()).split("\\.", -1)) {
                    if (part.isEmpty()) throw new IllegalArgumentException("Invalid execution parameter: " + name);
                    selected = selected.path(part);
                }
            } else throw new IllegalArgumentException("Unknown execution template variable: " + name);
            if (!selected.isTextual() || selected.textValue().trim().isEmpty()) {
                throw new IllegalArgumentException("Execution template variable requires a nonempty string: " + name);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(selected.textValue()));
        }
        matcher.appendTail(result);
        return TextNode.valueOf(result.toString());
    }
}
