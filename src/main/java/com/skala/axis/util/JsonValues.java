package com.skala.axis.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 파싱된 JSON(Object 트리) 탐색용 순수 헬퍼 (refactoring B-R2).
 *
 * <p>{@code PeerOverviewTableService} 등에서 {@code ObjectMapper.readValue} 로 파싱한
 * {@code Object}/{@code List}/{@code Map} 트리를 안전하게 좁히던 인스턴스 헬퍼들을 그대로 모았다
 * (파싱 자체는 ObjectMapper 의존이라 호출부에 남고, 여기엔 순수 탐색만).
 */
public final class JsonValues {

    private JsonValues() {
    }

    /** value 가 List 면 복사본, 아니면 빈 리스트. */
    public static List<Object> listValue(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    /** value(List) 의 각 원소를 Map 으로 좁혀 비어있지 않은 것만 수집. */
    public static List<Map<String, Object>> objectList(Object value) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object rawItem : listValue(value)) {
            Map<String, Object> item = objectMap(rawItem);
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    /** value 가 Map 이면 키를 String 으로 정규화한 LinkedHashMap, 아니면 빈 맵. */
    public static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    /** null → "", 그 외 String.valueOf 후 trim. */
    public static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** stringValue 가 공백이 아닌 첫 값. 없으면 null. */
    public static Object firstNonBlankObject(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
