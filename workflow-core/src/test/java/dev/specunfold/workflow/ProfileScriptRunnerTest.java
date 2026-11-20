package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProfileScriptRunnerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path workspace;

    @Test void declaredScriptReceivesJsonAndWorkspaceWithStderrSeparate() throws Exception {
        profile("process.stderr.write('diagnostic'); let text=''; process.stdin.on('data',x=>text+=x);"
                + "process.stdin.on('end',()=>console.log(JSON.stringify({input:JSON.parse(text),cwd:process.cwd(),arg:process.argv[2]})));", 10);
        JsonNode result = ProfileScriptRunner.run(new ProfileRepository(null), workspace, "demo", args());
        assertEquals("value", result.path("input").path("message").textValue());
        assertEquals(workspace.toRealPath(), java.nio.file.Paths.get(result.path("cwd").textValue()).toRealPath());
        assertEquals("fixed", result.path("arg").textValue());
    }

    @Test void timeoutDestroysProcessAndDeletesItsTemporaryDirectory() throws Exception {
        profile("setInterval(()=>{},1000);", 1);
        long before = temporaryCount();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ProfileScriptRunner.run(new ProfileRepository(null), workspace, "demo", args()));
        assertTrue(failure.getMessage().contains("timed out"));
        assertEquals(before, temporaryCount());
    }

    @Test void undeclaredEntryAndMissingTimeoutAreRejected() throws Exception {
        profile("console.log('{}');", 10);
        Path catalog = workspace.resolve(".agents/profiles/demo/runtime/scripts.json");
        ProfileRepositoryTest.write(catalog, "{\"scripts\":{\"check\":{\"runtime\":\"node\",\"entry\":\"run.js\",\"files\":[],\"args\":[],\"timeoutSeconds\":10}}}");
        assertThrows(IllegalArgumentException.class, () -> ProfileScriptRunner.run(new ProfileRepository(null), workspace, "demo", args()));
        ProfileRepositoryTest.write(catalog, "{\"scripts\":{\"check\":{\"runtime\":\"node\",\"entry\":\"run.js\",\"files\":[\"run.js\"],\"args\":[]}}}");
        assertThrows(IllegalArgumentException.class, () -> ProfileScriptRunner.run(new ProfileRepository(null), workspace, "demo", args()));
    }

    @Test void nonJsonStdoutAndProcessFailureAreRejected() throws Exception {
        profile("console.log('{} {}');", 10);
        assertThrows(IllegalArgumentException.class, () -> ProfileScriptRunner.run(new ProfileRepository(null), workspace, "demo", args()));
        profile("process.stderr.write('specific failure');process.exit(3);", 10);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ProfileScriptRunner.run(new ProfileRepository(null), workspace, "demo", args()));
        assertTrue(failure.getMessage().contains("specific failure"));
    }

    @Test void classpathScriptIsMaterializedThenRemoved() throws Exception {
        Path container = workspace.resolve("container");
        Path root = container.resolve("profiles/packaged");
        ProfileRepositoryTest.write(root.resolve("spec-map.json"), "{}");
        ProfileRepositoryTest.write(root.resolve("runtime/scripts.json"), "{\"scripts\":{\"check\":{\"runtime\":\"node\",\"entry\":\"run.js\",\"files\":[\"run.js\"],\"args\":[],\"timeoutSeconds\":10}}}");
        ProfileRepositoryTest.write(root.resolve("run.js"), "console.log(JSON.stringify({entry:__filename}));");
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{container.toUri().toURL()}, original)) {
            Thread.currentThread().setContextClassLoader(loader);
            JsonNode result = ProfileScriptRunner.run(new ProfileRepository(null), workspace, "packaged", args());
            Path entry = java.nio.file.Paths.get(result.get("entry").textValue());
            assertFalse(entry.startsWith(container));
            assertFalse(Files.exists(entry));
        } finally { Thread.currentThread().setContextClassLoader(original); }
    }

    private void profile(String script, int timeout) throws Exception {
        Path root = workspace.resolve(".agents/profiles/demo");
        ProfileRepositoryTest.write(root.resolve("spec-map.json"), "{}");
        ProfileRepositoryTest.write(root.resolve("run.js"), script);
        ProfileRepositoryTest.write(root.resolve("runtime/scripts.json"), "{\"scripts\":{\"check\":{\"runtime\":\"node\",\"entry\":\"run.js\",\"files\":[\"run.js\"],\"args\":[\"fixed\"],\"timeoutSeconds\":" + timeout + "}}}");
    }

    private JsonNode args() throws Exception { return MAPPER.readTree("{\"scriptId\":\"check\",\"input\":{\"message\":\"value\"}}"); }

    private long temporaryCount() throws Exception {
        try (Stream<Path> files = Files.list(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("specunfold-script-")).count();
        }
    }
}
