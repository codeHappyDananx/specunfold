package dev.specunfold.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ResponsePagesTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void boundsCompleteRpcResponsesAndReconstructsUnicodeAndGatesWithoutDuplicateText() throws Exception {
        ObjectNode payload = json.createObjectNode().put("success", true);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 2500; i++) text.append("规范😀\\\"\n");
        payload.putObject("result").put("content", text.toString()).putObject("gate").put("required", "user-confirmation");
        for (boolean structured : new boolean[] {true, false}) {
            ResponsePages pages = new ResponsePages(structured);
            JsonNode response = pages.render(json.getNodeFactory().numberNode(42), payload, false, 1024);
            StringBuilder assembled = new StringBuilder();
            String hash = null;
            int count = 0;
            while (true) {
                assertTrue(response.toString().getBytes(StandardCharsets.UTF_8).length <= 1024);
                assertEquals(structured, response.path("result").has("structuredContent"));
                if (structured) assertFalse(response.path("result").path("content").get(0).path("text").asText().contains("规范"));
                JsonNode page = unpack(response).path("result");
                assertEquals("json_fragment", page.path("kind").asText());
                assertFalse(page.path("executable").asBoolean());
                assertEquals(assembled.length(), page.path("offset").asInt());
                if (hash == null) hash = page.path("sha256").asText();
                else assertEquals(hash, page.path("sha256").asText());
                assembled.append(page.path("fragment").asText());
                count++;
                if (page.path("complete").asBoolean()) {
                    assertFalse(page.has("next"));
                    assertEquals(assembled.length(), page.path("totalLength").asInt());
                    break;
                }
                response = pages.page(json.getNodeFactory().numberNode(42), page.get("next"), 1024);
            }
            assertTrue(count > 1);
            assertEquals(payload, json.readTree(assembled.toString()));
            assertEquals("user-confirmation", json.readTree(assembled.toString()).path("result").path("gate").path("required").asText());
        }
    }

    private JsonNode unpack(JsonNode response) throws Exception {
        JsonNode result = response.path("result");
        return result.has("structuredContent") ? result.get("structuredContent")
                : json.readTree(result.path("content").get(0).path("text").asText());
    }

    @Test void boundsSnapshotCountAndRejectsExpiredOrOversizedSnapshots() {
        ResponsePages pages = new ResponsePages();
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 3000; i++) content.append('x');
        ObjectNode payload = json.createObjectNode().put("body", content.toString());
        JsonNode first = pages.render(json.getNodeFactory().numberNode(1), payload, false, 1024)
                .path("result").path("structuredContent").path("result").get("next");
        for (int i = 0; i < 32; i++) pages.render(json.getNodeFactory().numberNode(1), payload, false, 1024);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> pages.page(json.getNodeFactory().numberNode(1), first, 1024)).getMessage().contains("call context"));
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i < 1048576; i++) oversized.append('a');
        assertThrows(IllegalArgumentException.class, () -> pages.render(json.getNodeFactory().numberNode(1),
                json.createObjectNode().put("content", oversized.toString()), false, 1024));
    }

    @Test void validatesBudgetsAndBoundsTheEnvelopeEvenForHugeRequestIds() {
        assertEquals(16384, ResponsePages.budget(json.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> ResponsePages.budget(json.createObjectNode().put("maxResponseBytes", 1023)));
        assertThrows(IllegalArgumentException.class, () -> ResponsePages.budget(json.createObjectNode().put("maxResponseBytes", 16385)));
        assertThrows(IllegalArgumentException.class, () -> ResponsePages.budget(json.createObjectNode().put("maxResponseBytes", "1024")));
        assertThrows(IllegalArgumentException.class, () -> ResponsePages.budget(json.createObjectNode().put("maxResponseBytes", 1024.5)));
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 20000; i++) id.append('i');
        ObjectNode response = new ResponsePages().response(json.getNodeFactory().textNode(id.toString()),
                json.createObjectNode().put("success", true), false, "Result is in structuredContent.");
        JsonNode bounded = ResponsePages.enforce(response, 1024);
        assertTrue(bounded.toString().getBytes(StandardCharsets.UTF_8).length <= 1024);
        assertTrue(bounded.get("id").isNull());
        assertEquals(-32603, bounded.path("error").path("code").asInt());
    }

    @Test void rejectsInvalidPageOffsetsAndUnicodeSplits() {
        ResponsePages pages = new ResponsePages();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 500; i++) body.append("😀x");
        ObjectNode payload = json.createObjectNode().put("content", body.toString());
        JsonNode page = pages.render(json.getNodeFactory().numberNode(1), payload, false, 1024)
                .path("result").path("structuredContent").path("result");
        String responseId = page.path("responseId").asText();
        ObjectNode args = json.createObjectNode().put("responseId", responseId).put("offset", -1);
        assertThrows(IllegalArgumentException.class, () -> pages.page(json.getNodeFactory().numberNode(1), args, 1024));
        args.put("offset", page.path("totalLength").asInt());
        assertThrows(IllegalArgumentException.class, () -> pages.page(json.getNodeFactory().numberNode(1), args, 1024));
        args.put("offset", payload.toString().indexOf("😀") + 1);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> pages.page(json.getNodeFactory().numberNode(1), args, 1024)).getMessage().contains("surrogate pair"));
        args.put("offset", 1.5);
        assertThrows(IllegalArgumentException.class, () -> pages.page(json.getNodeFactory().numberNode(1), args, 1024));
    }
}
