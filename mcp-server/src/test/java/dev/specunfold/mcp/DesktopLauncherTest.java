package dev.specunfold.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

class DesktopLauncherTest {
    @Test void connectionConfigPreservesSpacesAndUnicodeAndNeedsNoSystemRuntime() throws Exception {
        java.nio.file.Path executable = Paths.get("F:\\软件目录\\Spec Unfold\\SpecUnfold-server.exe");
        JsonNode config = new ObjectMapper().readTree(DesktopLauncher.connectionConfig(executable));
        JsonNode service = config.path("mcpServers").path("specunfold");
        assertEquals(executable.toAbsolutePath().toString().replace('\\', '/'), service.path("command").textValue());
        assertTrue(service.path("args").isArray());
        assertEquals(0, service.path("args").size());
    }
}
