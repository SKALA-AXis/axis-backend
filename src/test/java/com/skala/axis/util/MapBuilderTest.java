package com.skala.axis.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MapBuilderTest {

    @Test
    void buildsOrderedMapAllowingNullValues() {
        Map<String, Object> map = MapBuilder.mapOf("a", 1, "b", null, "c", "x");

        assertThat(map).containsEntry("a", 1).containsEntry("c", "x");
        assertThat(map.get("b")).isNull();
        assertThat(map.keySet()).containsExactly("a", "b", "c");
    }

    @Test
    void emptyForNoEntries() {
        assertThat(MapBuilder.mapOf()).isEmpty();
    }
}
