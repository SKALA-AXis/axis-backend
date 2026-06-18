package com.skala.axis.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonValuesTest {

    @Test
    void listValue_copiesListsAndEmptyForNonList() {
        assertThat(JsonValues.listValue(List.of(1, 2))).containsExactly(1, 2);
        assertThat(JsonValues.listValue("x")).isEmpty();
        assertThat(JsonValues.listValue(null)).isEmpty();
    }

    @Test
    void objectMap_normalizesKeysToString() {
        assertThat(JsonValues.objectMap(Map.of("a", 1))).containsEntry("a", 1);
        assertThat(JsonValues.objectMap("x")).isEmpty();
        assertThat(JsonValues.objectMap(null)).isEmpty();
    }

    @Test
    void objectList_keepsOnlyNonEmptyMaps() {
        List<Object> raw = List.of(Map.of("a", 1), "notmap", Map.of());
        List<Map<String, Object>> result = JsonValues.objectList(raw);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("a", 1);
    }

    @Test
    void stringValue_nullEmptyAndTrim() {
        assertThat(JsonValues.stringValue(null)).isEmpty();
        assertThat(JsonValues.stringValue("  hi  ")).isEqualTo("hi");
        assertThat(JsonValues.stringValue(42)).isEqualTo("42");
    }

    @Test
    void firstNonBlankObject_() {
        assertThat(JsonValues.firstNonBlankObject(null, "", "   ", "x")).isEqualTo("x");
        assertThat(JsonValues.firstNonBlankObject(null, "")).isNull();
    }
}
