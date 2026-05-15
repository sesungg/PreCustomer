package com.example.personareport.persona.importer;

import com.fasterxml.jackson.databind.JsonNode;

public record PersonaImportRow(
        String source,
        String sourceId,
        Integer age,
        String ageGroup,
        String gender,
        String region,
        String province,
        String district,
        String occupation,
        String personaSummary,
        String interests,
        String painPoints,
        String digitalFamiliarity,
        String buyingSensitivity,
        String rawData,
        boolean active
) {

    static PersonaImportRow from(JsonNode root, String fallbackSource) {
        JsonNode rawDataNode = root.get("rawData");
        String sourceId = firstText(root, "sourceId", null);
        if (isBlank(sourceId)) {
            sourceId = firstText(rawDataNode, "uuid", null);
        }
        if (isBlank(sourceId)) {
            sourceId = firstText(root, "uuid", null);
        }

        String province = firstText(root, "province", firstText(rawDataNode, "province", null));
        String district = firstText(root, "district", firstText(rawDataNode, "district", null));
        String region = firstText(root, "region", null);
        if (isBlank(region)) {
            region = joinRegion(province, district);
        }

        Integer age = integer(root, "age");
        if (age == null) {
            age = integer(rawDataNode, "age");
        }

        return new PersonaImportRow(
                firstText(root, "source", fallbackSource),
                blankToNull(sourceId),
                age,
                firstText(root, "ageGroup", ageGroupFromAge(age)),
                firstText(root, "gender", firstText(rawDataNode, "sex", null)),
                region,
                province,
                district,
                firstText(root, "occupation", firstText(rawDataNode, "occupation", null)),
                firstText(root, "personaSummary", firstText(rawDataNode, "persona", null)),
                firstText(root, "interests", firstText(rawDataNode, "hobbies_and_interests", null)),
                firstText(root, "painPoints", firstText(rawDataNode, "career_goals_and_ambitions", null)),
                firstText(root, "digitalFamiliarity", "보통"),
                firstText(root, "buyingSensitivity", "보통"),
                rawDataNode == null || rawDataNode.isNull() ? root.toString() : rawDataNode.toString(),
                !root.has("active") || root.get("active").asBoolean(true)
        );
    }

    private static String firstText(JsonNode node, String fieldName, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        if (isBlank(text)) {
            return fallback;
        }
        return text.trim();
    }

    private static Integer integer(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || isBlank(value.asText())) {
            return null;
        }
        if (value.canConvertToInt()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String ageGroupFromAge(Integer age) {
        if (age == null) {
            return null;
        }
        if (age >= 70) {
            return "70대 이상";
        }
        return (age / 10 * 10) + "대";
    }

    private static String joinRegion(String province, String district) {
        if (isBlank(province)) {
            return district;
        }
        if (isBlank(district)) {
            return province;
        }
        return province + " " + district;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
