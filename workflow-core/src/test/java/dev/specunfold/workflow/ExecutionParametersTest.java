package dev.specunfold.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionParametersTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void expandsNestedStringsWithoutChangingTheFrozenStateOrValueTypes() throws Exception {
        JsonNode input = JSON.readTree("{\"artifact\":\"${parameters.output.dir}/${executionId}.json\",\"items\":[\"${parameters.name}\",12,true]}");
        JsonNode state = JSON.readTree("{\"executionId\":\"run-1\",\"parameters\":{\"output\":{\"dir\":\"out\"},\"name\":\"$x\\\\value\"}}");
        JsonNode bound = ExecutionParameters.bind(input, state);
        assertEquals("out/run-1.json", bound.path("artifact").textValue());
        assertEquals(state.path("parameters").path("name"), bound.path("items").get(0));
        assertTrue(bound.path("items").get(1).isInt());
        assertTrue(bound.path("items").get(2).isBoolean());
        assertEquals("${parameters.output.dir}/${executionId}.json", input.path("artifact").textValue());
    }

    @Test void rejectsMissingEmptyNonStringAndUnknownVariables() throws Exception {
        JsonNode state = JSON.readTree("{\"parameters\":{\"empty\":\"\",\"count\":3}}");
        for (String variable : new String[] {"executionId", "parameters.missing", "parameters.empty", "parameters.count", "parameters..name", "other"}) {
            JsonNode value = JSON.getNodeFactory().textNode("${" + variable + "}");
            assertThrows(IllegalArgumentException.class, () -> ExecutionParameters.bind(value, state), variable);
        }
    }
}
