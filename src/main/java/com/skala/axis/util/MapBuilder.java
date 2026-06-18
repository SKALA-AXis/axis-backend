package com.skala.axis.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * key/value 가변인자로 순서 보존 Map 을 만드는 헬퍼 (refactoring B-R2).
 *
 * <p>{@code Map.of} 와 달리 null 값을 허용하고 삽입 순서를 보존한다
 * ({@code PeerOverviewTableService} 의 응답 조립에 쓰이던 private {@code mapOf} 를 그대로 옮긴 것).
 */
public final class MapBuilder {

    private MapBuilder() {
    }

    /** entries 를 [k0, v0, k1, v1, ...] 로 해석해 LinkedHashMap 생성(null 값 허용). */
    public static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}
