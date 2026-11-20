package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceContextTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path directory;

    @Test void declaredDiscoveryReturnsAmbiguityAndExplicitRoleResolvesIt() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace")).toRealPath();
        Path profile = workspace.resolve(".agents/profiles/demo");
        ProfileRepositoryTest.write(profile.resolve("spec-map.json"), "{}");
        ProfileRepositoryTest.write(profile.resolve("runtime/project-context.json"), "{\"projects\":[{\"key\":\"service\",\"aliases\":[\"backend\"],\"intentKeywords\":[\"service\"],\"discovery\":{\"ancestorLevels\":1,\"candidateDepth\":1,\"markerGroups\":[{\"paths\":[\"pom.xml\"]}]}}],\"termMapping\":{\"service\":\"service\"}}");
        ProfileRepositoryTest.write(directory.resolve("one/pom.xml"), "one");
        ProfileRepositoryTest.write(directory.resolve("two/pom.xml"), "two");
        ProfileRepository repo = new ProfileRepository(null);
        JsonNode args = JSON.readTree("{\"intent\":\"inspect service\"}");
        JsonNode ambiguous = WorkspaceContext.projectContext(repo, workspace, "demo", args);
        assertEquals("AMBIGUOUS", ambiguous.path("status").asText());
        assertEquals(2, ambiguous.path("service").path("candidates").size());
        assertEquals("service", ambiguous.path("termMapping").path("service").asText());

        com.fasterxml.jackson.databind.node.ObjectNode explicit = JSON.createObjectNode();
        explicit.putObject("projectPaths").put("backend", directory.resolve("one").toString());
        JsonNode resolved = WorkspaceContext.projectContext(repo, workspace, "demo", explicit);
        assertEquals("READY", resolved.path("status").asText());
        assertEquals(directory.resolve("one").toRealPath().toString(), resolved.path("service").path("path").asText());
        assertEquals(resolved.path("service"), resolved.path("backend"));
        explicit.putObject("projectPaths").put("service", directory.resolve("missing").toString());
        assertThrows(IllegalArgumentException.class, () -> WorkspaceContext.projectContext(repo, workspace, "demo", explicit));
    }

    @Test void workspaceIgnoreSurvivesAnotherRepositoryAndCreatesNoExecution() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace")).toRealPath();
        ProfileRepositoryTest.write(workspace.resolve(".agents/profiles/demo/spec-map.json"), "{}");
        JsonNode ignored = WorkspaceContext.ignore(workspace, "demo", JSON.readTree("{\"reason\":\"ordinary workspace\"}"));
        assertEquals("IGNORED", ignored.path("status").asText());
        assertEquals("IGNORED", WorkspaceContext.probe(new ProfileRepository(null), workspace, "demo", JSON.createObjectNode()).path("status").asText());
        assertFalse(Files.exists(workspace.resolve(".specunfold/workflow-state")));
        assertFalse(Files.exists(directory.resolve("ignored-profiles.json")));
    }

    @Test void installedProjectPathsAreWorkspaceLocal() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace")).toRealPath();
        Path service = Files.createDirectories(directory.resolve("service"));
        Path profile = workspace.resolve(".agents/profiles/demo");
        ProfileRepositoryTest.write(profile.resolve("spec-map.json"), "{}");
        ProfileRepositoryTest.write(profile.resolve("runtime/project-context.json"), "{\"projects\":[{\"key\":\"service\"}]}");
        com.fasterxml.jackson.databind.node.ObjectNode installed = JSON.createObjectNode();
        installed.putObject("projectPaths").put("service", service.toString());
        ProfileRepositoryTest.write(workspace.resolve(".agents/context.json"), installed.toString());
        JsonNode result = WorkspaceContext.projectContext(new ProfileRepository(null), workspace, "demo", JSON.createObjectNode());
        assertEquals("workspace_context", result.path("service").path("source").asText());
        assertEquals(service.toRealPath().toString(), result.path("service").path("path").asText());
    }
}
