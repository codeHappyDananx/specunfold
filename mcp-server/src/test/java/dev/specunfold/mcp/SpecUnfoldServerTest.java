package dev.specunfold.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.specunfold.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SpecUnfoldServerTest {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path home;

    @Test void negotiatesVersionExposesOnlyWorkflowAndContainsToolErrors() throws Exception {
        SpecUnfoldServer server = new SpecUnfoldServer(new WorkflowService(home));
        assertEquals(-32600, server.handle(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).path("error").path("code").asInt());
        JsonNode initialized = server.handle(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}"));
        assertEquals("2024-11-05", initialized.path("result").path("protocolVersion").asText());
        assertNull(server.handle(json.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));
        JsonNode tools = server.handle(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}")).path("result").path("tools");
        assertEquals(1, tools.size()); assertEquals("workflow_ops", tools.get(0).path("name").asText());
        JsonNode failed = server.handle(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"workflow_ops\",\"arguments\":{\"action\":\"unknown\"}}}"));
        assertTrue(failed.path("result").path("isError").asBoolean());
        assertFalse(failed.path("result").has("structuredContent"));
        assertFalse(json.readTree(failed.path("result").path("content").get(0).path("text").asText()).path("success").asBoolean());
        JsonNode schema = tools.get(0).path("inputSchema").path("properties");
        assertTrue(schema.path("action").path("enum").toString().contains("context"));
        assertTrue(schema.path("action").path("enum").toString().contains("page"));
        assertTrue(schema.has("currentState"));
        assertEquals(16384, schema.path("maxResponseBytes").path("maximum").asInt());
        assertEquals("boolean", schema.path("workflowConfirmedByUser").path("type").asText());
        assertEquals(2, schema.path("workflowConfirmationSource").path("enum").size());
        assertEquals("boolean", schema.path("skipConfirm").path("type").asText());
        assertEquals("boolean", schema.path("decision").path("properties").path("autoApproved").path("type").asText());
        JsonNode invalidBudget = server.handle(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"workflow_ops\",\"arguments\":{\"action\":\"context\",\"maxResponseBytes\":0}}}"));
        assertEquals(-32602, invalidBudget.path("error").path("code").asInt());
        assertEquals(-32601, server.handle(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"unknown\"}")).path("error").path("code").asInt());
    }

    @Test void negotiatesSinglePayloadRepresentationAndBoundsErrorsForBothProtocolGenerations() throws Exception {
        for (String version : new String[] {"2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"}) {
            SpecUnfoldServer server = new SpecUnfoldServer(new WorkflowService(home));
            server.handle(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + version + "\"}}"));
            JsonNode response = server.handle(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"workflow_ops\",\"arguments\":{\"action\":\"unknown\",\"maxResponseBytes\":1024}}}"));
            assertTrue(response.toString().getBytes(StandardCharsets.UTF_8).length <= 1024);
            JsonNode result = response.path("result");
            boolean structured = version.equals("2025-06-18") || version.equals("2025-11-25");
            assertEquals(structured, result.has("structuredContent"));
            JsonNode payload = structured ? result.get("structuredContent")
                    : json.readTree(result.path("content").get(0).path("text").asText());
            assertFalse(payload.path("success").asBoolean());
            assertEquals(1, result.path("content").size());
            if (structured) assertFalse(result.path("content").get(0).path("text").asText().startsWith("{"));
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 2000; i++) id.append('i');
            com.fasterxml.jackson.databind.node.ObjectNode call = json.createObjectNode().put("jsonrpc", "2.0").put("id", id.toString()).put("method", "tools/call");
            call.putObject("params").put("name", "unknown").putObject("arguments").put("action", "context").put("maxResponseBytes", 1024);
            assertTrue(server.handle(call).toString().getBytes(StandardCharsets.UTF_8).length <= 1024);
        }
    }
}
