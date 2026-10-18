package com.documenter.util;

import com.documenter.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TemplateVariablesTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "null"})
    void absentDefinitionsRemainAbsent(String input) {
        assertNull(TemplateVariables.validateAndNormalize(input));
    }

    @Test
    void normalizesNamesAndPreservesValidOptionalFieldsAndExtensions() {
        var result = json.readTree(TemplateVariables.validateAndNormalize("""
                [{"name":" title ","label":"标题","required":true,"description":"说明","custom":"kept"},
                 {"name":"Title","required":false},{"name":"_id","label":null}]
                """));
        assertEquals("title", result.get(0).get("name").asText());
        assertEquals("标题", result.get(0).get("label").asText());
        assertEquals("说明", result.get(0).get("description").asText());
        assertTrue(result.get(0).get("required").asBoolean());
        assertEquals("kept", result.get(0).get("custom").asText());
        assertEquals("Title", result.get(1).get("name").asText());
        assertFalse(result.get(1).get("required").asBoolean());
        assertTrue(result.get(2).get("label").isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "[null]", "[1]", "[[]]", "[{}]", "[{\"name\":\"  \"}]",
            "[{\"name\":true}]", "[{\"name\":123}]", "[{\"name\":{}}]",
            "[{\"name\":\"1title\"}]", "[{\"name\":\"bad-name\"}]",
            "[{\"name\":\"title\"},{\"name\":\" title \"}]",
            "[{\"name\":\"x\",\"label\":12}]", "[{\"name\":\"x\",\"label\":[]}]",
            "[{\"name\":\"x\",\"description\":{}}]",
            "[{\"name\":\"x\",\"required\":\"true\"}]",
            "[{\"name\":\"x\",\"required\":1}]", "[{\"name\":\"x\",\"required\":null}]"
    })
    void rejectsInvalidStructureNamesDuplicatesAndFieldTypes(String input) {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> TemplateVariables.validateAndNormalize(input)).getCode());
    }

    @Test
    void enforcesVariableCountBoundary() {
        String hundred = IntStream.range(0, 100).mapToObj(i -> "{\"name\":\"v" + i + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
        assertEquals(100, json.readTree(TemplateVariables.validateAndNormalize(hundred)).size());
        String extra = hundred.substring(0, hundred.length() - 1) + ",{\"name\":\"extra\"}]";
        assertEquals(400, assertThrows(BusinessException.class,
                () -> TemplateVariables.validateAndNormalize(extra)).getCode());
    }

    @Test
    void enforcesTextLengthBoundaries() {
        String valid = "[{\"name\":\"" + "a".repeat(64) + "\",\"label\":\"" + "中".repeat(128)
                + "\",\"description\":\"" + "文".repeat(255) + "\"}]";
        assertNotNull(TemplateVariables.validateAndNormalize(valid));
        for (String invalid : new String[] {
                "[{\"name\":\"" + "a".repeat(65) + "\"}]",
                "[{\"name\":\"x\",\"label\":\"" + "中".repeat(129) + "\"}]",
                "[{\"name\":\"x\",\"description\":\"" + "文".repeat(256) + "\"}]"}) {
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> TemplateVariables.validateAndNormalize(invalid)).getCode());
        }
    }
}
