package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DeclarativeGatesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @Test void documentContractChecksRequiredAlternativesAndForbiddenPatterns() throws Exception {
        profile("contracts/note.json", "{\"artifact\":\"note.md\",\"requiredPatterns\":[\"# Result\"],\"requiredAnyPatternGroups\":[[\"Approved\",\"Reviewed\"]],\"forbiddenPatterns\":[\"TODO\"]}");
        JsonNode gate = json("{\"type\":\"document_contract\",\"contract\":\"contracts/note.json\"}");
        write("note.md", "# Result\nReviewed");
        assertTrue(validate(gate).isEmpty());
        write("note.md", "# Draft\nTODO");
        assertEquals(3, validate(gate).size());
    }

    @Test void draft7UsesLocalReferencesAndRejectsRemoteReferencesBeforeValidation() throws Exception {
        profile("contracts/root.json", "{\"type\":\"object\",\"properties\":{\"value\":{\"$ref\":\"value.json#/definitions/flag\"}},\"required\":[\"value\"]}");
        profile("contracts/value.json", "{\"definitions\":{\"flag\":{\"type\":\"boolean\"}}}");
        JsonNode gate = json("{\"type\":\"json_schema\",\"artifact\":\"result.json\",\"schema\":\"contracts/root.json\"}");
        write("result.json", "{\"value\":true}");
        assertTrue(validate(gate).isEmpty());
        assertEquals(Arrays.asList("contracts/root.json", "contracts/value.json"), DeclarativeGates.resources(repository(), workspace, "demo", gate));
        write("result.json", "{\"value\":\"true\"}");
        assertFalse(validate(gate).isEmpty());
        profile("contracts/value.json", "{\"definitions\":{\"flag\":{\"type\":\"string\"}}}");
        assertTrue(validate(gate).isEmpty());
        profile("contracts/root.json", "{\"$ref\":\"https://example.invalid/untrusted.json\"}");
        assertThrows(IllegalArgumentException.class, () -> validate(gate));
        profile("contracts/root.json", "{\"$ref\":\"file:///C:/private.json\"}");
        assertThrows(IllegalArgumentException.class, () -> validate(gate));
    }

    @Test void bundleRequiresUniqueDirectoriesAndEveryDeclaredFile() throws Exception {
        write("items.json", "{\"groups\":[{\"items\":[{\"id\":\"A1\"}]}]}");
        write("output/A1-first/note.md", "ready");
        JsonNode gate = json("{\"type\":\"json_item_directory_bundle\",\"sourceArtifact\":\"items.json\",\"groupsPath\":\"groups\",\"itemsField\":\"items\",\"idField\":\"id\",\"root\":\"output\",\"directorySeparator\":\"-\",\"requiredFiles\":[\"note.md\",\"result.json\"]}");
        assertFalse(validate(gate).isEmpty());
        write("output/A1-first/result.json", "{}");
        assertTrue(validate(gate).isEmpty());
        write("output/A1-second/note.md", "duplicate");
        assertFalse(validate(gate).isEmpty());
    }

    @Test void traceabilityComparesOnlyDeclaredSectionInBothDirections() throws Exception {
        write("items.json", "{\"items\":[{\"id\":\"ID1\"},{\"id\":\"ID2\"}]}");
        JsonNode gate = json("{\"type\":\"json_document_traceability\",\"sourceArtifact\":\"items.json\",\"sourceArrayPath\":\"items\",\"sourceIdField\":\"id\",\"targetArtifact\":\"note.md\",\"targetSectionStartPattern\":\"# Items\",\"targetSectionEndPattern\":\"# Appendix\",\"targetIdPattern\":\"ID[0-9]+\"}");
        write("note.md", "# Items\nID1 ID2\n# Appendix\nID9");
        assertTrue(validate(gate).isEmpty());
        write("note.md", "# Items\nID1 ID3\n# Appendix\nID2");
        assertEquals(2, validate(gate).size());
    }

    @Test void provenanceRejectsChangedContentAndDisallowedProducer() throws Exception {
        write("out.txt", "ready");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(workspace.resolve("out.txt")));
        StringBuilder hex = new StringBuilder();
        for (byte item : digest) hex.append(String.format("%02x", item & 255));
        ObjectNode record = JSON.createObjectNode();
        ObjectNode item = record.putArray("items").addObject();
        item.put("path", "out.txt").put("producer", "builder").put("action", "generate").put("sha256", hex.toString())
                .put("fileLastModifiedMillis", Files.getLastModifiedTime(workspace.resolve("out.txt")).toMillis());
        write("records.json", record.toString());
        JsonNode gate = json("{\"type\":\"provenance\",\"artifact\":\"out.txt\",\"recordArtifact\":\"records.json\",\"recordsPath\":\"items\",\"allowedProducers\":[\"builder\"],\"allowedActions\":[\"generate\"]}");
        assertTrue(validate(gate).isEmpty());
        write("out.txt", "changed");
        assertTrue(validate(gate).stream().anyMatch(failure -> failure.contains("content differs")));
        item.put("producer", "manual"); write("records.json", record.toString());
        assertTrue(validate(gate).stream().anyMatch(failure -> failure.contains("producer is not allowed")));
    }

    @Test void gateCatalogReferencesResolveAndPathsStayInsideWorkspace() throws Exception {
        profile("gates/gates.json", "{\"gates\":{\"note\":{\"type\":\"document_contract\",\"contract\":\"contracts/note.json\"}}}");
        assertEquals("document_contract", DeclarativeGates.resolve(repository(), workspace, "demo", json("\"note\"")).path("type").asText());
        assertThrows(IllegalArgumentException.class, () -> DeclarativeGates.resolve(repository(), workspace, "demo", json("\"missing\"")));
        JsonNode gate = json("{\"type\":\"json_schema\",\"artifact\":\"../outside.json\",\"schema\":\"contracts/root.json\"}");
        assertThrows(IllegalArgumentException.class, () -> validate(gate));
    }

    private List<String> validate(JsonNode gate) {
        List<String> failures = new ArrayList<String>();
        DeclarativeGates.validate(repository(), workspace, "demo", gate, failures);
        return failures;
    }
    private ProfileRepository repository() { return new ProfileRepository(workspace); }
    private JsonNode json(String value) throws Exception { return JSON.readTree(value); }
    private void profile(String relative, String content) throws Exception { write(".agents/profiles/demo/" + relative, content); }
    private void write(String relative, String content) throws Exception {
        Path target = workspace.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }
}
