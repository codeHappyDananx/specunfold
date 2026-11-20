package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AutoDecisionPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @Test void allDeclaredConditionsMustPassAndMissingFactsDoNotApprove() throws Exception {
        JsonNode policy = JSON.readTree("{\"conditions\":[{\"type\":\"path_in\",\"artifact\":\"review.json\",\"path\":\"review.status\",\"values\":[\"accepted\"]},{\"type\":\"path_not_in\",\"artifact\":\"review.json\",\"path\":\"review.source\",\"values\":[\"unconfirmed\"]}]}");
        write("{\"review\":{\"status\":\"accepted\",\"source\":\"user\"}}");
        assertTrue(evaluate(policy));
        write("{\"review\":{\"status\":\"accepted\",\"source\":\"unconfirmed\"}}");
        assertFalse(evaluate(policy));
        write("{\"review\":{\"status\":\"accepted\"}}");
        assertFalse(evaluate(policy));
        Files.delete(workspace.resolve("review.json"));
        assertFalse(evaluate(policy));
    }

    @Test void blockingFlagsAndTextSeveritiesPreventAutomaticApproval() throws Exception {
        JsonNode policy = JSON.readTree("{\"conditions\":[{\"type\":\"no_blocking_items\",\"artifact\":\"review.json\",\"paths\":[\"issues\"],\"blockingFields\":[\"blocking\"],\"blockingSeverities\":[\"HIGH\"],\"severityField\":\"severity\"}]}");
        write("{\"issues\":[{\"blocking\":false,\"severity\":\"LOW\"}]}");
        assertTrue(evaluate(policy));
        write("{\"issues\":[{\"blocking\":true,\"severity\":\"LOW\"}]}");
        assertFalse(evaluate(policy));
        write("{\"issues\":[\"high remaining risk\"]}");
        assertFalse(evaluate(policy));
        write("{}");
        assertFalse(evaluate(policy));
    }

    @Test void invalidPoliciesAndOutsideWorkspacePathsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> evaluate(JSON.readTree("{\"conditions\":[]}")));
        JsonNode outside = JSON.readTree("{\"conditions\":[{\"type\":\"path_in\",\"artifact\":\"../outside.json\",\"path\":\"status\",\"values\":[true]}]}");
        assertThrows(IllegalArgumentException.class, () -> evaluate(outside));
        JsonNode invalid = JSON.readTree("{\"conditions\":[{\"type\":\"arbitrary\",\"artifact\":\"review.json\"}]}");
        assertThrows(IllegalArgumentException.class, () -> evaluate(invalid));
    }

    private boolean evaluate(JsonNode policy) {
        List<String> failures = new ArrayList<String>();
        boolean passed = AutoDecisionPolicy.evaluate(workspace, policy, failures);
        assertEquals(passed, failures.isEmpty());
        return passed;
    }
    private void write(String content) throws Exception { Files.write(workspace.resolve("review.json"), content.getBytes(StandardCharsets.UTF_8)); }
}
