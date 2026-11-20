package dev.specunfold.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProfileRepositoryTest {
    @TempDir Path directory;

    @Test void selectsOneRootAndDoesNotFillMissingFilesFromAnotherVersion() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path home = Files.createDirectories(directory.resolve("home"));
        write(workspace.resolve(".agents/profiles/demo/spec-map.json"), "{\"version\":1}");
        write(workspace.resolve("resources/profiles/demo/spec-map.json"), "{\"version\":2}");
        write(home.resolve("resources/profiles/demo/extra.txt"), "different version");
        ProfileRepository repo = new ProfileRepository(home);
        assertEquals(1, repo.json(workspace, "demo", "spec-map.json").get("version").intValue());
        assertEquals(workspace.resolve(".agents/profiles/demo").toRealPath(), repo.profileRoot(workspace, "demo"));
        assertFalse(repo.exists(workspace, "demo", "extra.txt"));
        assertThrows(IllegalArgumentException.class, () -> repo.text(workspace, "demo", "extra.txt"));
        assertThrows(IllegalArgumentException.class, () -> repo.text(workspace, "demo", "../extra.txt"));
        assertThrows(IllegalArgumentException.class, () -> repo.text(workspace, "../demo", "spec-map.json"));
    }

    @Test void workspaceResourcesPrecedeHomeAndHomeIsAvailable() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path home = Files.createDirectories(directory.resolve("home"));
        write(workspace.resolve("resources/profiles/demo/spec-map.json"), "{\"version\":2}");
        write(home.resolve("resources/profiles/demo/spec-map.json"), "{\"version\":3}");
        write(home.resolve("resources/profiles/other/spec-map.json"), "{\"version\":4}");
        ProfileRepository repo = new ProfileRepository(home);
        assertEquals(2, repo.json(workspace, "demo", "spec-map.json").get("version").intValue());
        assertEquals(4, repo.json(workspace, "other", "spec-map.json").get("version").intValue());
    }

    @Test void classpathResourcesStayInTheirSelectedContainer() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path first = directory.resolve("first"), second = directory.resolve("second");
        write(first.resolve("profiles/demo/spec-map.json"), "{}");
        write(first.resolve("profiles/demo/spec.txt"), "selected");
        write(second.resolve("profiles/demo/extra.txt"), "another container");
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{first.toUri().toURL(), second.toUri().toURL()}, original)) {
            Thread.currentThread().setContextClassLoader(loader);
            ProfileRepository repo = new ProfileRepository(null);
            assertNull(repo.profileRoot(workspace, "demo"));
            assertEquals("selected", repo.text(workspace, "demo", "spec.txt"));
            assertFalse(repo.exists(workspace, "demo", "extra.txt"));
            assertThrows(IllegalArgumentException.class, () -> repo.text(workspace, "demo", "extra.txt"));
        } finally { Thread.currentThread().setContextClassLoader(original); }
    }

    @Test void symbolicLinkCannotReadOutsideSelectedProfile() throws Exception {
        Path root = directory.resolve("workspace/.agents/profiles/demo");
        write(root.resolve("spec-map.json"), "{}");
        Path outside = directory.resolve("secret.txt");
        write(outside, "secret");
        try { Files.createSymbolicLink(root.resolve("outside.txt"), outside); }
        catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links unavailable: " + e.getMessage());
        }
        ProfileRepository repo = new ProfileRepository(null);
        assertThrows(IllegalArgumentException.class, () -> repo.text(directory.resolve("workspace"), "demo", "outside.txt"));
    }

    @Test void profileRootCannotBeRedirectedToAnotherDirectory() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path profiles = Files.createDirectories(workspace.resolve(".agents/profiles"));
        Path other = Files.createDirectories(workspace.resolve("other"));
        write(other.resolve("spec-map.json"), "{}");
        try { Files.createSymbolicLink(profiles.resolve("demo"), other); }
        catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links unavailable: " + e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> new ProfileRepository(null).profileRoot(workspace, "demo"));
    }

    @Test void installedHarnessDeclaresDefaultProfile() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        ProfileRepository repo = new ProfileRepository(null);
        assertEquals("example", repo.defaultProfile(workspace));
        write(workspace.resolve(".agents/harness/manifest.json"), "{\"defaultProfile\":\"external\"}");
        assertEquals("external", repo.defaultProfile(workspace));
        write(workspace.resolve(".agents/harness/manifest.json"), "{\"defaultProfile\":[\"external\"]}");
        assertThrows(IllegalArgumentException.class, () -> repo.defaultProfile(workspace));
    }

    @Test void pinnedReleasePrecedesWorkspaceAndNeverFallsBack() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path release = Files.createDirectories(directory.resolve("release"));
        write(workspace.resolve(".agents/profiles/demo/spec-map.json"), "{\"source\":\"workspace\"}");
        write(release.resolve("demo/spec-map.json"), "{\"source\":\"release\"}");
        String previous = System.getProperty("specunfold.profileRoot");
        try {
            System.setProperty("specunfold.profileRoot", release.toString());
            ProfileRepository repo = new ProfileRepository(null);
            assertEquals("release", repo.json(workspace, "demo", "spec-map.json").path("source").asText());
            assertFalse(repo.exists(workspace, "demo", "extra.txt"));
            assertThrows(IllegalArgumentException.class, () -> repo.json(workspace, "example", "spec-map.json"));
        } finally {
            if (previous == null) System.clearProperty("specunfold.profileRoot");
            else System.setProperty("specunfold.profileRoot", previous);
        }
    }

    static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
