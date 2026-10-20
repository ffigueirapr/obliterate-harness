package com.obliterateharness.graph;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serialização JSON de propriedades complexas guardadas como string no grafo. */
@Component
public class JsonCodec {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private final JsonMapper mapper;

    public JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        return mapper.readValue(json, MAP);
    }

    public <T> List<T> readList(String json, Class<T> type) {
        if (json == null || json.isBlank()) return List.of();
        return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, type));
    }

    public <T> T convert(Object value, Class<T> type) {
        return mapper.convertValue(value, type);
    }

    public <T> T convert(Object value, TypeReference<T> type) {
        return mapper.convertValue(value, type);
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
