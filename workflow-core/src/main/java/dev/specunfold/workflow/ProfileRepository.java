package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Selects one resource source for each workspace and Profile. */
public final class ProfileRepository {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path home;
    private final ClassLoader loader;
    private final Map<String, Source> sources = new ConcurrentHashMap<String, Source>();

    public ProfileRepository(Path home) {
        this.home = home == null ? null : home.toAbsolutePath().normalize();
        this.loader = Thread.currentThread().getContextClassLoader();
    }

    public JsonNode json(Path workspace, String profile, String relative) {
        try {
            JsonNode value = MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(text(workspace, profile, relative));
            if (value == null) throw new IllegalArgumentException("Empty Profile JSON: " + relative);
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid Profile JSON: " + relative, e);
        }
    }

    public String text(Path workspace, String profile, String relative) {
        return new String(bytes(workspace, profile, relative), StandardCharsets.UTF_8);
    }

    public boolean exists(Path workspace, String profile, String relative) {
        Source source = source(workspace, profile);
        Path path = relative(relative);
        if (source.root != null) return Files.isRegularFile(inside(source.root, path));
        try (InputStream ignored = resource(source, path).openStream()) {
            return true;
        } catch (java.io.FileNotFoundException e) { return false; }
        catch (IOException e) { throw new IllegalArgumentException("Cannot inspect Profile resource: " + relative, e); }
    }

    public Path profileRoot(Path workspace, String profile) {
        return source(workspace, profile).root;
    }

    public String defaultProfile(Path workspace) {
        Path manifest = inside(workspace, Paths.get(".agents", "harness", "manifest.json"));
        if (!Files.exists(manifest)) return "example";
        try {
            JsonNode value = MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(Files.readAllBytes(manifest));
            JsonNode profile = value == null ? null : value.get("defaultProfile");
            if (profile == null || !profile.isTextual() || !profile.textValue().matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
                throw new IllegalArgumentException("Harness manifest requires a valid defaultProfile");
            }
            return profile.textValue();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read Harness manifest: " + manifest, e);
        }
    }

    byte[] bytes(Path workspace, String profile, String relative) {
        Source source = source(workspace, profile);
        Path path = relative(relative);
        try {
            if (source.root != null) return Files.readAllBytes(inside(source.root, path));
            try (InputStream input = resource(source, path).openStream()) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return output.toByteArray();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read Profile resource: " + profile + "/" + relative, e);
        }
    }

    private Source source(Path workspace, String profile) {
        if (profile == null || !profile.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid Profile name: " + profile);
        }
        Path root = workspace.toAbsolutePath().normalize();
        return sources.computeIfAbsent(root + "\n" + profile, key -> select(root, profile));
    }

    private Source select(Path workspace, String profile) {
        List<Path> candidates = new ArrayList<Path>();
        String configured = System.getProperty("specunfold.profileRoot");
        if (configured != null) {
            if (configured.isEmpty()) throw new IllegalArgumentException("specunfold.profileRoot must be a directory");
            Path pinned = Paths.get(configured).toAbsolutePath().normalize();
            Path selected = inside(pinned, Paths.get(profile));
            if (!Files.isDirectory(selected) || !Files.isRegularFile(inside(selected, Paths.get("spec-map.json")))) {
                throw new IllegalArgumentException("Pinned Profile is missing: " + selected);
            }
            try { return new Source(selected.toRealPath(), null); }
            catch (IOException e) { throw new IllegalArgumentException("Cannot resolve pinned Profile: " + selected, e); }
        }
        candidates.add(workspace.resolve(Paths.get(".agents", "profiles", profile)));
        candidates.add(workspace.resolve(Paths.get("resources", "profiles", profile)));
        if (home != null) candidates.add(home.resolve(Paths.get("resources", "profiles", profile)));
        for (Path candidate : candidates) {
            if (Files.exists(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(candidate)) throw new IllegalArgumentException("Profile root is not a directory: " + candidate);
                try {
                    Path base = candidate.startsWith(workspace) ? workspace : home;
                    Path expected = base.toRealPath().resolve(base.relativize(candidate));
                    if (!candidate.toRealPath().equals(expected)) {
                        throw new IllegalArgumentException("Profile root symbolic link escapes its directory: " + candidate);
                    }
                    return new Source(candidate.toRealPath(), null);
                }
                catch (IOException e) { throw new IllegalArgumentException("Cannot resolve Profile root: " + candidate, e); }
            }
        }
        URL marker = loader.getResource("profiles/" + profile + "/spec-map.json");
        if (marker != null) {
            try { return new Source(null, new URL(marker, ".")); }
            catch (java.net.MalformedURLException e) { throw new IllegalArgumentException("Invalid Profile resource root", e); }
        }
        throw new IllegalArgumentException("Profile not found: " + profile);
    }

    static Path relative(String value) {
        if (value == null || value.isEmpty() || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Invalid Profile resource path: " + value);
        }
        Path path = Paths.get(value);
        if (path.isAbsolute() || !path.normalize().equals(path) || path.startsWith("..") || ".".equals(value)) {
            throw new IllegalArgumentException("Profile resource path escapes its root: " + value);
        }
        return path;
    }

    private static URL resource(Source source, Path path) {
        try {
            if ("file".equals(source.resourceRoot.getProtocol())) {
                inside(Paths.get(source.resourceRoot.toURI()), path);
            }
            return new URL(source.resourceRoot, new java.net.URI(null, null, path.toString().replace('\\', '/'), null).toASCIIString());
        } catch (java.net.URISyntaxException | java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Invalid Profile resource path: " + path, e);
        }
    }

    static Path inside(Path root, Path relative) {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) throw new IllegalArgumentException("Profile path escapes its root: " + relative);
        try {
            Path existing = target;
            while (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
            if (!existing.toRealPath().startsWith(base.toRealPath())) {
                throw new IllegalArgumentException("Profile symbolic link escapes its root: " + relative);
            }
            return target;
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve Profile path: " + target, e);
        }
    }

    private static final class Source {
        final Path root;
        final URL resourceRoot;
        Source(Path root, URL resourceRoot) { this.root = root; this.resourceRoot = resourceRoot; }
    }
}
