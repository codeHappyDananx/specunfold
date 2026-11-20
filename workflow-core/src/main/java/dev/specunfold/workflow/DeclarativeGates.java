package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.security.MessageDigest;
import com.networknt.schema.uri.URIFetcher;

/** Evaluates artifact contracts declared by a Profile, without project-specific file rules. */
public final class DeclarativeGates {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DeclarativeGates() { }

    public static boolean supports(String type) {
        return "document_contract".equals(type) || "json_schema".equals(type)
                || "json_item_directory_bundle".equals(type) || "json_document_traceability".equals(type)
                || "provenance".equals(type);
    }

    public static JsonNode resolve(ProfileRepository profiles, Path workspace, String profile, JsonNode reference) {
        if (reference.isObject()) return reference;
        String id = string(reference, "completion gate reference");
        JsonNode gate = profiles.json(workspace, profile, "gates/gates.json").path("gates").path(id);
        if (!gate.isObject()) throw new IllegalArgumentException("Undefined completion gate: " + id);
        return gate;
    }

    /** Profile resources included in the effective rule-set digest. */
    public static List<String> resources(JsonNode gate) {
        List<String> result = new ArrayList<String>();
        String type = field(gate, "type");
        if ("document_contract".equals(type)) result.add(field(gate, "contract"));
        if ("json_schema".equals(type)) result.add(field(gate, "schema"));
        return result;
    }

    public static List<String> resources(ProfileRepository profiles, Path workspace, String profile, JsonNode gate) {
        Set<String> references = new LinkedHashSet<String>(resources(gate));
        if ("json_schema".equals(field(gate, "type"))) {
            String schema = field(gate, "schema");
            Set<String> inspected = new LinkedHashSet<String>();
            inspectSchema(profiles, workspace, profile, schemaUri(schema), references, inspected);
        }
        return new ArrayList<String>(references);
    }

    public static void validate(ProfileRepository profiles, Path workspace, String profile,
                                JsonNode gate, List<String> failures) {
        validate(profiles, workspace, profile, gate, failures, JSON.createObjectNode());
    }

    public static void validate(ProfileRepository profiles, Path workspace, String profile,
                                JsonNode gate, List<String> failures, JsonNode state) {
        String type = field(gate, "type");
        if (!supports(type)) throw new IllegalArgumentException("Unsupported artifact gate: " + type);
        try {
            if ("document_contract".equals(type)) document(profiles, workspace, profile, gate, failures, state);
            else if ("json_schema".equals(type)) schema(profiles, workspace, profile, gate, failures);
            else if ("json_item_directory_bundle".equals(type)) bundle(workspace, gate, failures);
            else if ("json_document_traceability".equals(type)) trace(workspace, gate, failures);
            else provenance(workspace, gate, failures);
        } catch (IOException e) {
            failures.add(type + ": Cannot read artifact: " + e.getMessage());
        }
    }

    private static void document(ProfileRepository profiles, Path workspace, String profile,
                                 JsonNode gate, List<String> failures, JsonNode state) throws IOException {
        JsonNode contract = ExecutionParameters.bind(profiles.json(workspace, profile, field(gate, "contract")), state);
        Path path = file(workspace, field(contract, "artifact"), failures);
        if (path == null) return;
        String body = read(path);
        for (JsonNode item : optionalArray(contract, "requiredPatterns")) {
            String pattern = string(item, "required pattern");
            if (!Pattern.compile(pattern).matcher(body).find()) failures.add("Document missing pattern: " + pattern);
        }
        for (JsonNode group : optionalArray(contract, "requiredAnyPatternGroups")) {
            if (!group.isArray() || group.isEmpty()) throw new IllegalArgumentException("Pattern group must be a nonempty array");
            boolean found = false;
            for (JsonNode item : group) found |= Pattern.compile(string(item, "alternative pattern")).matcher(body).find();
            if (!found) failures.add("Document missing required pattern group: " + group);
        }
        for (JsonNode item : optionalArray(contract, "forbiddenPatterns")) {
            String pattern = string(item, "forbidden pattern");
            if (Pattern.compile(pattern).matcher(body).find()) failures.add("Document contains forbidden pattern: " + pattern);
        }
    }

    private static void schema(ProfileRepository profiles, Path workspace, String profile,
                               JsonNode gate, List<String> failures) throws IOException {
        Path path = file(workspace, field(gate, "artifact"), failures);
        if (path == null) return;
        String reference = field(gate, "schema");
        JsonNode schema = profiles.json(workspace, profile, reference);
        resources(profiles, workspace, profile, gate);
        URIFetcher fetcher = uri -> new ByteArrayInputStream(profiles.bytes(workspace, profile, schemaPath(uri)));
        JsonSchemaFactory factory = JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7))
                .uriFetcher(fetcher, "http", "https", "file", "classpath", "jar", "ftp").build();
        JsonNode content = JSON.readTree(path.toFile());
        if (content == null) { failures.add("JSON artifact is empty: " + path.getFileName()); return; }
        for (ValidationMessage message : factory.getSchema(schemaUri(reference), schema).validate(content)) {
            failures.add("JSON schema: " + message.getMessage());
        }
    }

    private static URI schemaUri(String reference) {
        ProfileRepository.relative(reference);
        try { return new URI("https", "specunfold.invalid", "/profile/" + reference, null); }
        catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("Invalid schema reference: " + reference, e); }
    }

    private static String schemaPath(URI uri) {
        if (!"https".equals(uri.getScheme()) || !"specunfold.invalid".equals(uri.getHost())
                || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getPath() == null || !uri.getPath().startsWith("/profile/")) {
            throw new IllegalArgumentException("Schema reference must stay inside its Profile: " + uri);
        }
        String path = uri.getPath().substring("/profile/".length());
        ProfileRepository.relative(path);
        return path;
    }

    private static void inspectSchema(ProfileRepository profiles, Path workspace, String profile,
                                      URI uri, Set<String> references, Set<String> inspected) {
        String path = schemaPath(uri);
        references.add(path);
        if (inspected.add(path)) inspectReferences(profiles, workspace, profile,
                profiles.json(workspace, profile, path), schemaUri(path), references, inspected);
    }

    private static void inspectReferences(ProfileRepository profiles, Path workspace, String profile,
                                          JsonNode node, URI base, Set<String> references, Set<String> inspected) {
        if (node.isObject() && node.has("$id")) {
            base = base.resolve(string(node.get("$id"), "$id"));
            schemaPath(base);
        }
        if (node.isObject() && node.has("$ref")) {
            URI target = base.resolve(string(node.get("$ref"), "$ref"));
            inspectSchema(profiles, workspace, profile, target, references, inspected);
        }
        for (JsonNode child : node) inspectReferences(profiles, workspace, profile, child, base, references, inspected);
    }

    private static void provenance(Path workspace, JsonNode gate, List<String> failures) throws IOException {
        String artifact = field(gate, "artifact");
        Path path = file(workspace, artifact, failures);
        Path recordPath = file(workspace, field(gate, "recordArtifact"), failures);
        if (path == null || recordPath == null) return;
        JsonNode records = dotted(JSON.readTree(recordPath.toFile()), field(gate, "recordsPath"));
        if (!records.isArray()) throw new IllegalArgumentException("recordsPath must select an array");
        JsonNode latest = null;
        for (JsonNode record : records) if (artifact.equals(record.path("path").asText())) latest = record;
        if (latest == null) { failures.add("Artifact has no provenance record: " + artifact); return; }
        if (!contains(array(gate, "allowedProducers"), field(latest, "producer"))) failures.add("Artifact producer is not allowed: " + artifact);
        if (!contains(array(gate, "allowedActions"), field(latest, "action"))) failures.add("Artifact action is not allowed: " + artifact);
        if (!latest.path("fileLastModifiedMillis").isIntegralNumber()) throw new IllegalArgumentException("Provenance fileLastModifiedMillis must be an integer");
        if (latest.get("fileLastModifiedMillis").longValue() != Files.getLastModifiedTime(path).toMillis()) failures.add("Artifact modification time differs from provenance: " + artifact);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format("%02x", value & 255));
            if (!hex.toString().equals(field(latest, "sha256"))) failures.add("Artifact content differs from provenance: " + artifact);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode entry : array) if (value.equals(string(entry, "allowed value"))) return true;
        return false;
    }

    private static void bundle(Path workspace, JsonNode gate, List<String> failures) throws IOException {
        Path source = file(workspace, field(gate, "sourceArtifact"), failures);
        if (source == null) return;
        JsonNode groups = dotted(JSON.readTree(source.toFile()), field(gate, "groupsPath"));
        if (!groups.isArray()) throw new IllegalArgumentException("groupsPath must select an array");
        String itemsField = field(gate, "itemsField"), idField = field(gate, "idField");
        Set<String> ids = new LinkedHashSet<String>();
        for (JsonNode group : groups) {
            JsonNode items = group.path(itemsField);
            if (!items.isArray()) throw new IllegalArgumentException("itemsField must select an array");
            for (JsonNode item : items) {
                String id = field(item, idField);
                if (!ids.add(id)) failures.add("Duplicate bundle identifier: " + id);
            }
        }
        if (ids.isEmpty()) { failures.add("Bundle source has no items"); return; }
        List<String> required = new ArrayList<String>();
        for (JsonNode item : array(gate, "requiredFiles")) {
            String name = string(item, "requiredFiles entry");
            Path relative = ProfileRepository.relative(name);
            if (relative.getNameCount() != 1) throw new IllegalArgumentException("requiredFiles must contain direct file names");
            required.add(name);
        }
        if (required.isEmpty()) throw new IllegalArgumentException("requiredFiles must not be empty");
        Path root = inside(workspace, field(gate, "root"));
        if (!Files.isDirectory(root)) { failures.add("Bundle root is missing: " + gate.get("root").textValue()); return; }
        String separator = field(gate, "directorySeparator");
        List<Path> directories = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(root)) { paths.filter(Files::isDirectory).forEach(directories::add); }
        for (String id : ids) {
            List<Path> matches = new ArrayList<Path>();
            for (Path directory : directories) if (directory.getFileName().toString().startsWith(id + separator)) matches.add(directory);
            if (matches.size() != 1) { failures.add("Bundle directory count for " + id + ": " + matches.size()); continue; }
            for (String name : required) {
                Path requiredFile = inside(workspace, workspace.relativize(matches.get(0).resolve(name)).toString().replace('\\', '/'));
                if (!Files.isRegularFile(requiredFile)) failures.add("Bundle " + id + " is missing " + name);
            }
        }
    }

    private static void trace(Path workspace, JsonNode gate, List<String> failures) throws IOException {
        Path source = file(workspace, field(gate, "sourceArtifact"), failures);
        Path target = file(workspace, field(gate, "targetArtifact"), failures);
        if (source == null || target == null) return;
        JsonNode items = dotted(JSON.readTree(source.toFile()), field(gate, "sourceArrayPath"));
        if (!items.isArray()) throw new IllegalArgumentException("sourceArrayPath must select an array");
        Set<String> sourceIds = new LinkedHashSet<String>();
        String idField = field(gate, "sourceIdField");
        for (JsonNode item : items) {
            String id = field(item, idField);
            if (!sourceIds.add(id)) failures.add("Duplicate source identifier: " + id);
        }
        String body = read(target);
        Matcher start = Pattern.compile(field(gate, "targetSectionStartPattern")).matcher(body);
        if (!start.find()) { failures.add("Traceability section is missing"); return; }
        int endIndex = body.length();
        if (gate.has("targetSectionEndPattern")) {
            Matcher end = Pattern.compile(field(gate, "targetSectionEndPattern")).matcher(body);
            if (end.find(start.end())) endIndex = end.start();
        }
        Matcher ids = Pattern.compile(field(gate, "targetIdPattern")).matcher(body.substring(start.end(), endIndex));
        Set<String> targetIds = new LinkedHashSet<String>();
        while (ids.find()) targetIds.add(ids.group());
        Set<String> missing = new LinkedHashSet<String>(sourceIds); missing.removeAll(targetIds);
        Set<String> extra = new LinkedHashSet<String>(targetIds); extra.removeAll(sourceIds);
        if (!missing.isEmpty()) failures.add("Document missing source identifiers: " + missing);
        if (!extra.isEmpty()) failures.add("Document has unknown identifiers: " + extra);
    }

    private static Path inside(Path workspace, String value) {
        return ProfileRepository.inside(workspace, ProfileRepository.relative(value));
    }

    private static Path file(Path workspace, String value, List<String> failures) {
        Path path = inside(workspace, value);
        if (Files.isRegularFile(path)) return path;
        failures.add("Artifact is missing: " + value);
        return null;
    }

    private static String read(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }

    private static JsonNode dotted(JsonNode root, String path) {
        if (root == null) throw new IllegalArgumentException("JSON artifact must not be empty");
        for (String part : path.split("\\.")) root = root.path(part);
        return root;
    }

    private static JsonNode array(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array");
        return value;
    }

    private static JsonNode optionalArray(JsonNode object, String field) {
        return object.has(field) ? array(object, field) : JSON.createArrayNode();
    }

    private static String field(JsonNode object, String field) { return string(object.path(field), field); }
    private static String string(JsonNode value, String field) {
        if (!value.isTextual() || value.textValue().trim().isEmpty()) throw new IllegalArgumentException(field + " must be a nonempty string");
        return value.textValue();
    }
}
