package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Workspace-local routing. Project names and discovery markers belong to the selected Profile. */
final class WorkspaceContext {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path IGNORED = Paths.get(".specunfold", "ignored-profiles.json");

    private WorkspaceContext() { }

    static ObjectNode probe(ProfileRepository repo, Path workspace, String profile, JsonNode args) {
        ObjectNode result = JSON.createObjectNode();
        result.put("workspaceRoot", workspace.toString()).put("profile", profile);
        if (localJson(workspace, IGNORED).has(profile)) {
            result.put("status", "IGNORED").put("profileAvailable", false);
            return result;
        }
        boolean available = repo.exists(workspace, profile, "spec-map.json");
        result.put("profileAvailable", available).put("status", available ? "READY" : "UNAVAILABLE");
        if (available) result.set("projectContext", projectContext(repo, workspace, profile, args));
        return result;
    }

    static ObjectNode ignore(Path workspace, String profile, JsonNode args) {
        if (!profile.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid Profile name");
        String reason = text(args.path("reason"), "reason");
        ObjectNode ignored = localJson(workspace, IGNORED);
        ignored.put(profile, reason);
        Path target = ProfileRepository.inside(workspace, IGNORED);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "ignored-", ".tmp");
            try {
                Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(ignored));
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) { throw new IllegalArgumentException("Cannot save workspace ignore selection", e); }
        return JSON.createObjectNode().put("status", "IGNORED").put("workspaceRoot", workspace.toString()).put("profile", profile);
    }

    static ObjectNode projectContext(ProfileRepository repo, Path workspace, String profile, JsonNode args) {
        ObjectNode result = JSON.createObjectNode().put("workspaceRoot", workspace.toString());
        if (!repo.exists(workspace, profile, "runtime/project-context.json")) return result.put("status", "UNCONFIGURED");
        JsonNode config = repo.json(workspace, profile, "runtime/project-context.json");
        if (!config.path("projects").isArray()) throw new IllegalArgumentException("project-context.projects must be an array");
        if (args.has("projectPaths") && !args.get("projectPaths").isObject()) throw new IllegalArgumentException("projectPaths must be an object");
        JsonNode installed = localJson(workspace, Paths.get(".agents", "context.json"));
        if (installed.has("projectPaths") && !installed.get("projectPaths").isObject()) throw new IllegalArgumentException("Installed projectPaths must be an object");
        String intent = args.has("intent") ? text(args.get("intent"), "intent").toLowerCase(Locale.ROOT) : "";
        Set<String> ignored = new HashSet<String>();
        for (String directory : strings(config.path("ignoredDirectories"), "ignoredDirectories")) ignored.add(directory.toLowerCase(Locale.ROOT));
        ArrayNode requestedKeys = result.putArray("requestedKeys");
        ArrayNode confirmations = result.putArray("needsConfirmation");
        Set<String> keys = new HashSet<String>();
        boolean missing = false, ambiguous = false;
        for (JsonNode rule : config.get("projects")) {
            String key = text(rule.path("key"), "project key");
            if (!keys.add(key)) throw new IllegalArgumentException("Duplicate project key or alias: " + key);
            List<String> names = new ArrayList<String>(); names.add(key);
            for (String alias : strings(rule.path("aliases"), "aliases")) {
                if (!keys.add(alias)) throw new IllegalArgumentException("Duplicate project key or alias: " + alias);
                names.add(alias);
            }
            boolean requested = false;
            for (String keyword : strings(rule.path("intentKeywords"), "intentKeywords")) {
                if (intent.contains(keyword.toLowerCase(Locale.ROOT))) requested = true;
            }
            String configured = null, source = null;
            for (String name : names) {
                if (args.path("projectPaths").has(name)) {
                    configured = text(args.path("projectPaths").get(name), "projectPaths." + name);
                    source = "argument"; requested = true; break;
                }
            }
            if (configured == null) for (String name : names) {
                if (installed.path("projectPaths").has(name)) {
                    configured = text(installed.path("projectPaths").get(name), "Installed projectPaths." + name);
                    source = "workspace_context"; break;
                }
            }
            if (requested) requestedKeys.add(key);
            ObjectNode entry = JSON.createObjectNode();
            List<Path> candidates = new ArrayList<Path>();
            if (configured != null) {
                Path path = Paths.get(configured);
                if (!path.isAbsolute()) path = workspace.resolve(path);
                path = path.toAbsolutePath().normalize();
                if (!Files.isDirectory(path)) throw new IllegalArgumentException("Configured project directory does not exist: " + path);
                try { candidates.add(path.toRealPath()); }
                catch (IOException e) { throw new IllegalArgumentException("Cannot resolve project directory: " + path, e); }
            } else if (requested || rule.path("discoverAlways").asBoolean(false)) {
                candidates = discover(workspace, rule.path("discovery"), ignored);
                source = "discovered";
            }
            if (candidates.size() == 1) {
                entry.put("status", "RESOLVED").put("path", candidates.get(0).toString()).put("exists", true).put("source", source);
            } else {
                String status = candidates.isEmpty() ? "MISSING" : "AMBIGUOUS";
                entry.put("status", status).put("exists", false);
                if (!candidates.isEmpty()) entry.set("candidates", JSON.valueToTree(candidates.stream().map(Path::toString).toArray(String[]::new)));
                if (requested) {
                    missing |= candidates.isEmpty(); ambiguous |= !candidates.isEmpty();
                    ObjectNode confirmation = confirmations.addObject().put("key", key).put("status", status);
                    confirmation.set("candidates", JSON.valueToTree(candidates.stream().map(Path::toString).toArray(String[]::new)));
                }
            }
            for (String name : names) result.set(name, entry.deepCopy());
        }
        if (config.has("termMapping")) {
            if (!config.get("termMapping").isObject()) throw new IllegalArgumentException("termMapping must be an object");
            result.set("termMapping", config.get("termMapping").deepCopy());
        }
        return result.put("status", ambiguous ? "AMBIGUOUS" : missing ? "INCOMPLETE" : "READY");
    }

    private static List<Path> discover(Path workspace, JsonNode discovery, Set<String> ignored) {
        if (discovery.isMissingNode()) return Collections.emptyList();
        if (!discovery.isObject()) throw new IllegalArgumentException("discovery must be an object");
        int ancestors = depth(discovery, "ancestorLevels"), candidateDepth = depth(discovery, "candidateDepth"), markerDepth = depth(discovery, "markerDepth");
        JsonNode groups = discovery.path("markerGroups");
        if (!groups.isArray() || groups.isEmpty()) throw new IllegalArgumentException("discovery.markerGroups must be a nonempty array");
        Path root = workspace;
        for (int index = 0; index < ancestors && root.getParent() != null; index++) root = root.getParent();
        List<Path> candidates = directories(root, candidateDepth, ignored);
        List<Path> matches = new ArrayList<Path>();
        for (Path candidate : candidates) {
            boolean matched = true;
            for (JsonNode group : groups) {
                boolean found = false;
                for (String marker : strings(group.path("paths"), "marker paths")) {
                    if (Files.exists(ProfileRepository.inside(candidate, ProfileRepository.relative(marker)))) found = true;
                }
                List<String> globs = strings(group.path("directoryGlobs"), "directoryGlobs");
                if (!found && !globs.isEmpty()) {
                    for (Path directory : directories(candidate, markerDepth, ignored)) {
                        String relative = candidate.relativize(directory).toString().replace('\\', '/');
                        for (String glob : globs) if (glob(glob).matcher(relative).matches()) found = true;
                    }
                }
                if (!found) { matched = false; break; }
            }
            if (matched) matches.add(candidate);
        }
        Collections.sort(matches);
        return matches;
    }

    private static List<Path> directories(Path root, int depth, Set<String> ignored) {
        List<Path> result = new ArrayList<Path>(); result.add(root);
        int start = 0, end = 1;
        for (int level = 0; level < depth; level++) {
            for (int index = start; index < end; index++) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(result.get(index))) {
                    for (Path child : children) {
                        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && !ignored.contains(child.getFileName().toString().toLowerCase(Locale.ROOT))) result.add(child);
                    }
                } catch (java.nio.file.AccessDeniedException e) {
                    // A declaration may cover an ancestor with unrelated inaccessible directories.
                } catch (IOException e) { throw new IllegalArgumentException("Cannot inspect project directory: " + result.get(index), e); }
            }
            start = end; end = result.size();
        }
        return result;
    }

    private static Pattern glob(String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '*') {
                if (index + 1 < value.length() && value.charAt(index + 1) == '*') { regex.append(".*"); index++; }
                else regex.append("[^/]*");
            } else regex.append(Pattern.quote(String.valueOf(character)));
        }
        return Pattern.compile(regex.append('$').toString(), Pattern.CASE_INSENSITIVE);
    }

    private static int depth(JsonNode node, String name) {
        if (!node.has(name)) return 0;
        JsonNode value = node.get(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw new IllegalArgumentException(name + " must be a nonnegative integer");
        return value.intValue();
    }

    private static List<String> strings(JsonNode value, String label) {
        List<String> result = new ArrayList<String>();
        if (value.isMissingNode()) return result;
        if (!value.isArray()) throw new IllegalArgumentException(label + " must be an array");
        for (JsonNode item : value) result.add(text(item, label + " entry"));
        return result;
    }

    private static String text(JsonNode value, String label) {
        if (!value.isTextual() || value.textValue().trim().isEmpty()) throw new IllegalArgumentException(label + " must be a nonempty string");
        return value.textValue();
    }

    private static ObjectNode localJson(Path workspace, Path relative) {
        Path path = ProfileRepository.inside(workspace, relative);
        if (!Files.exists(path)) return JSON.createObjectNode();
        try {
            JsonNode value = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(Files.readAllBytes(path));
            if (value == null || !value.isObject()) throw new IllegalArgumentException("Workspace configuration must be a JSON object: " + path);
            return (ObjectNode) value;
        } catch (IOException e) { throw new IllegalArgumentException("Cannot read workspace configuration: " + path, e); }
    }
}
