package dev.specunfold.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Bounded response snapshots; reading a page never repeats a workflow action. */
final class ResponsePages {
    static final int MAX_RESPONSE_BYTES = 16384;
    private static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<String, Snapshot>();
    private final boolean structured;

    ResponsePages() { this(true); }
    ResponsePages(boolean structured) { this.structured = structured; }

    static int budget(JsonNode args) {
        JsonNode value = args.get("maxResponseBytes");
        if (value == null) return MAX_RESPONSE_BYTES;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1024
                || value.intValue() > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("maxResponseBytes must be an integer from 1024 to 16384");
        }
        return value.intValue();
    }

    ObjectNode render(JsonNode id, ObjectNode payload, boolean failed, int budget) {
        ObjectNode direct = response(id, payload, failed, "Result is in structuredContent.");
        if (bytes(direct) <= budget) return direct;
        String content = payload.toString();
        byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("Response exceeds the 1048576-byte snapshot limit; request a narrower specification section or state page");
        }
        String responseId = UUID.randomUUID().toString();
        Snapshot snapshot = new Snapshot(content, digest(encoded), failed);
        snapshots.put(responseId, snapshot);
        if (snapshots.size() > 32) snapshots.remove(snapshots.keySet().iterator().next());
        return fragment(id, responseId, snapshot, 0, budget);
    }

    ObjectNode page(JsonNode id, JsonNode args, int budget) {
        if (!args.path("responseId").isTextual() || args.get("responseId").textValue().isEmpty()) {
            throw new IllegalArgumentException("responseId is required for page");
        }
        String responseId = args.get("responseId").textValue();
        Snapshot snapshot = snapshots.get(responseId);
        if (snapshot == null) {
            throw new IllegalArgumentException("Response cursor expired or belongs to another server process; call context to resume without creating a new workflow");
        }
        JsonNode position = args.get("offset");
        if (position == null || !position.isIntegralNumber() || !position.canConvertToInt()
                || position.intValue() < 0 || position.intValue() >= snapshot.content.length()) {
            throw new IllegalArgumentException("offset must be an integer within the response snapshot");
        }
        int offset = position.intValue();
        if (offset > 0 && Character.isLowSurrogate(snapshot.content.charAt(offset))
                && Character.isHighSurrogate(snapshot.content.charAt(offset - 1))) {
            throw new IllegalArgumentException("offset must not split a Unicode surrogate pair; use the supplied next request");
        }
        return fragment(id, responseId, snapshot, offset, budget);
    }

    private ObjectNode fragment(JsonNode id, String responseId, Snapshot snapshot, int offset, int budget) {
        int length = snapshot.content.length();
        if (length - offset <= budget) {
            ObjectNode entire = pageResponse(id, responseId, snapshot, offset, length, budget);
            if (bytes(entire) <= budget) return entire;
        }
        int lower = 1;
        int upper = Math.min(length - offset - 1, budget);
        int best = offset;
        while (lower <= upper) {
            int count = lower + (upper - lower) / 2;
            int end = offset + count;
            if (Character.isHighSurrogate(snapshot.content.charAt(end - 1))
                    && Character.isLowSurrogate(snapshot.content.charAt(end))) end--;
            ObjectNode candidate = pageResponse(id, responseId, snapshot, offset, end, budget);
            if (bytes(candidate) <= budget) { if (end > offset) best = end; lower = count + 1; }
            else upper = count - 1;
        }
        if (best == offset) {
            throw new IllegalArgumentException("Response envelope exceeds maxResponseBytes; use a shorter request id or a larger response budget");
        }
        return pageResponse(id, responseId, snapshot, offset, best, budget);
    }

    private ObjectNode pageResponse(JsonNode id, String responseId, Snapshot snapshot, int offset, int end, int budget) {
        ObjectNode payload = JSON.createObjectNode().put("success", true);
        ObjectNode page = payload.putObject("result");
        page.put("kind", "json_fragment").put("executable", false).put("responseId", responseId)
                .put("offset", offset).put("offsetUnit", "UTF-16 code units")
                .put("totalLength", snapshot.content.length()).put("sha256", snapshot.hash)
                .put("fragment", snapshot.content.substring(offset, end))
                .put("complete", end == snapshot.content.length());
        if (end < snapshot.content.length()) {
            page.putObject("next").put("action", "page").put("responseId", responseId)
                    .put("offset", end).put("maxResponseBytes", budget);
        }
        return response(id, payload, snapshot.failed,
                "JSON fragment only. Read and assemble all pages before interpreting the result or its gates.");
    }

    static ObjectNode enforce(ObjectNode response, int budget) {
        if (response == null || bytes(response) <= budget) return response;
        ObjectNode failure = JSON.createObjectNode().put("jsonrpc", "2.0");
        failure.putNull("id");
        failure.putObject("error").put("code", -32603)
                .put("message", "Response envelope exceeds the UTF-8 byte budget; use a shorter request id");
        return failure;
    }

    ObjectNode response(JsonNode id, ObjectNode payload, boolean failed, String text) {
        ObjectNode rpc = JSON.createObjectNode().put("jsonrpc", "2.0");
        rpc.set("id", id);
        ObjectNode result = rpc.putObject("result");
        result.put("isError", failed);
        result.putArray("content").addObject().put("type", "text").put("text", structured ? text : payload.toString());
        if (structured) result.set("structuredContent", payload);
        return rpc;
    }

    private static int bytes(ObjectNode value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }

    private static String digest(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder value = new StringBuilder();
            for (byte item : digest) value.append(String.format("%02x", item & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static final class Snapshot {
        final String content;
        final String hash;
        final boolean failed;
        Snapshot(String content, String hash, boolean failed) {
            this.content = content; this.hash = hash; this.failed = failed;
        }
    }
}
