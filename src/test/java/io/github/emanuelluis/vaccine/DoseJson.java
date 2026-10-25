package io.github.emanuelluis.vaccine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds POST /api/v1/doses bodies for the ITs: a valid PENTA D1 dose, with overrides. */
public final class DoseJson {

    private DoseJson() {}

    public static Map<String, Object> valid(UUID personId) {
        var m = new LinkedHashMap<String, Object>();
        m.put("personId", personId.toString());
        m.put("birthDate", "2026-06-01");
        m.put("sex", "F");
        m.put("cnesCode", "0000264");
        m.put("vaccineCode", "PENTA");
        m.put("doseLabel", "D1");
        m.put("administeredAt", "2026-10-25T10:00:00-03:00");
        return m;
    }

    public static Map<String, Object> with(Map<String, Object> base, String field, Object value) {
        var m = new LinkedHashMap<>(base);
        if (value == null) {
            m.remove(field);
        } else {
            m.put(field, value);
        }
        return m;
    }

    public static String json(Map<String, Object> m) {
        return m.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + "\"" + e.getValue() + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }
}
