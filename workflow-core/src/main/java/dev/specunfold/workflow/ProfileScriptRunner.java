package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Runs commands declared by the selected Profile without invoking a shell. */
public final class ProfileScriptRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileScriptRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProfileScriptRunner() { }

    public static JsonNode run(ProfileRepository repo, Path workspace, String profile, JsonNode args) {
        Path temporary = null;
        Process process = null;
        try {
            /*
             * ========================================================================
             * Step 1: Resolve the declared entry and its resources
             * ========================================================================
             * Source: runtime/scripts.json in the selected Profile.
             * Only declared files and arguments are supplied to the runtime.
             */
            LOGGER.info("Resolving Profile script, profile={}", profile);
            String scriptId = text(args, "scriptId");
            if (!args.path("input").isObject()) throw new IllegalArgumentException("input must be a JSON object");
            JsonNode declaration = repo.json(workspace, profile, "runtime/scripts.json").path("scripts").path(scriptId);
            if (!declaration.isObject()) throw new IllegalArgumentException("Profile script not declared: " + scriptId);
            String runtime = text(declaration, "runtime");
            if (!runtime.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid script runtime: " + runtime);
            String entry = text(declaration, "entry");
            ProfileRepository.relative(entry);
            JsonNode files = declaration.path("files");
            JsonNode arguments = declaration.path("args");
            JsonNode timeout = declaration.path("timeoutSeconds");
            if (!files.isArray() || !arguments.isArray() || !timeout.isIntegralNumber()
                    || !timeout.canConvertToInt() || timeout.intValue() <= 0) {
                throw new IllegalArgumentException("Script requires files[], args[] and positive integer timeoutSeconds");
            }
            List<String> resources = new ArrayList<String>();
            for (JsonNode file : files) {
                if (!file.isTextual()) throw new IllegalArgumentException("Script files must be strings");
                ProfileRepository.relative(file.textValue());
                if (resources.contains(file.textValue())) throw new IllegalArgumentException("Duplicate script file: " + file.textValue());
                resources.add(file.textValue());
            }
            if (!resources.contains(entry)) throw new IllegalArgumentException("Script entry must be declared in files: " + entry);
            temporary = Files.createTempDirectory("specunfold-script-");
            Path root = repo.profileRoot(workspace, profile);
            if (root == null) {
                root = Files.createDirectories(temporary.resolve("profile"));
                for (String resource : resources) {
                    Path target = root.resolve(ProfileRepository.relative(resource));
                    Files.createDirectories(target.getParent());
                    Files.write(target, repo.bytes(workspace, profile, resource));
                }
            } else {
                for (String resource : resources) {
                    if (!repo.exists(workspace, profile, resource)) throw new IllegalArgumentException("Script file missing: " + resource);
                }
            }
            List<String> command = new ArrayList<String>();
            command.add(System.getProperty("specunfold.script." + runtime, runtime));
            command.add(ProfileRepository.inside(root, ProfileRepository.relative(entry)).toString());
            for (JsonNode argument : arguments) {
                if (!argument.isTextual()) throw new IllegalArgumentException("Script args must be strings");
                command.add(argument.textValue());
            }
            LOGGER.info("Resolved Profile script, scriptId={}", scriptId);

            /*
             * ========================================================================
             * Step 2: Execute with JSON stdin and independent stdout/stderr
             * ========================================================================
             * File redirection avoids pipe deadlocks before timeout enforcement.
             */
            LOGGER.info("Executing Profile script, scriptId={}", scriptId);
            Path stdin = temporary.resolve("stdin.json");
            Path stdout = temporary.resolve("stdout.json");
            Path stderr = temporary.resolve("stderr.txt");
            Files.write(stdin, MAPPER.writeValueAsBytes(args.get("input")));
            ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile())
                    .redirectInput(stdin.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            if ("python".equals(runtime)) {
                builder.environment().put("PYTHONIOENCODING", "utf-8");
                builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            }
            process = builder.start();
            if (!process.waitFor(timeout.intValue(), TimeUnit.SECONDS)) {
                throw new IllegalArgumentException("Profile script timed out: " + scriptId);
            }
            if (process.exitValue() != 0) {
                throw new IllegalArgumentException("Profile script failed: " + scriptId + ", exit=" + process.exitValue()
                        + ", stderr=" + new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8));
            }
            JsonNode result = MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(Files.readAllBytes(stdout));
            if (result == null || !result.isObject()) throw new IllegalArgumentException("Script stdout must contain one JSON object");
            LOGGER.info("Executed Profile script, scriptId={}", scriptId);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Profile script interrupted", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot execute Profile script", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (temporary != null) {
                try {
                    List<Path> cleanup;
                    try (Stream<Path> paths = Files.walk(temporary)) {
                        cleanup = paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
                    }
                    for (Path path : cleanup) {
                        // Windows can release redirected output handles shortly after process termination.
                        for (int attempt = 0; attempt < 20; attempt++) {
                            try { Files.delete(path); }
                            catch (java.nio.file.FileSystemException e) {
                                if (attempt == 19) { LOGGER.warn("Cannot clean script temporary file: {}", path, e); break; }
                                try { Thread.sleep(25); }
                                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                                continue;
                            }
                            catch (IOException e) { LOGGER.warn("Cannot clean script temporary file: {}", path, e); }
                            break;
                        }
                    }
                } catch (IOException e) { LOGGER.warn("Cannot clean script temporary directory: {}", temporary, e); }
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isEmpty()) throw new IllegalArgumentException(field + " must be a nonempty string");
        return value.textValue();
    }
}
