package com.example.personareport.persona.importer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.persona")
public record PersonaImportProperties(
        boolean importEnabled,
        String importPath,
        int importBatchSize,
        String importSource
) {

    public int safeBatchSize() {
        return importBatchSize <= 0 ? 3000 : importBatchSize;
    }

    public String safeSource() {
        if (importSource == null || importSource.isBlank()) {
            return "NEMOTRON";
        }
        return importSource.trim();
    }
}
