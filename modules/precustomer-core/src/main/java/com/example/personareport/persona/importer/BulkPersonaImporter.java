package com.example.personareport.persona.importer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.persona", name = "import-enabled", havingValue = "true")
public class BulkPersonaImporter implements ApplicationRunner {

    private static final String INSERT_SQL = """
            insert into persona_profile (
                source, source_id, age, age_group, gender, region, province, district, occupation,
                persona_summary, interests, pain_points, digital_familiarity, buying_sensitivity,
                raw_data, active, created_at, updated_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (source_id) where source_id is not null do nothing
            """;

    private final PersonaImportProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!isPostgreSql()) {
            log.warn("Persona bulk import is enabled, but the active database is not PostgreSQL. Skipping bulk import.");
            return;
        }

        Path importPath = Path.of(properties.importPath());
        if (!Files.exists(importPath)) {
            log.warn("Persona bulk import file does not exist: {}. Skipping import.", importPath.toAbsolutePath());
            return;
        }

        ensureIndexes();
        importJsonl(importPath);
    }

    private boolean isPostgreSql() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return metaData.getDatabaseProductName().toLowerCase().contains("postgresql");
        }
    }

    private void ensureIndexes() {
        jdbcTemplate.execute("create unique index if not exists ux_persona_profile_source_id on persona_profile (source_id) where source_id is not null");
        jdbcTemplate.execute("create index if not exists ix_persona_profile_source on persona_profile (source)");
        jdbcTemplate.execute("create index if not exists ix_persona_profile_active on persona_profile (active)");
        jdbcTemplate.execute("create index if not exists ix_persona_profile_age_group on persona_profile (age_group)");
        jdbcTemplate.execute("create index if not exists ix_persona_profile_province on persona_profile (province)");
        jdbcTemplate.execute("create index if not exists ix_persona_profile_district on persona_profile (district)");
        jdbcTemplate.execute("create index if not exists ix_persona_profile_occupation on persona_profile (occupation)");
        jdbcTemplate.execute("create index if not exists ix_persona_profile_source_active_province on persona_profile (source, active, province)");
        jdbcTemplate.execute("create index if not exists ix_persona_profile_source_active_age_group on persona_profile (source, active, age_group)");
    }

    private void importJsonl(Path importPath) throws IOException {
        Instant startedAt = Instant.now();
        ImportStats stats = new ImportStats();
        List<PersonaImportRow> batch = new ArrayList<>(properties.safeBatchSize());

        try (BufferedReader reader = Files.newBufferedReader(importPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                stats.read++;
                if (line.isBlank()) {
                    stats.skipped++;
                    continue;
                }

                try {
                    batch.add(PersonaImportRow.from(objectMapper.readTree(line), properties.safeSource()));
                } catch (JsonProcessingException exception) {
                    stats.failed++;
                    log.warn("Skipping invalid persona JSONL at line {}. reason={}", stats.read, exception.getOriginalMessage());
                    continue;
                }

                if (batch.size() >= properties.safeBatchSize()) {
                    flush(batch, stats);
                }

                if (stats.read % 10_000 == 0) {
                    log.info("Persona bulk import progress: read={}, attempted={}, inserted={}, skipped={}, failed={}",
                            stats.read, stats.attempted, stats.inserted, stats.skipped, stats.failed);
                }
            }
        }

        flush(batch, stats);
        Duration elapsed = Duration.between(startedAt, Instant.now());
        log.info("Persona bulk import complete: read={}, attempted={}, inserted={}, skipped={}, failed={}, elapsed={}s",
                stats.read, stats.attempted, stats.inserted, stats.skipped, stats.failed, elapsed.toSeconds());
    }

    private void flush(List<PersonaImportRow> batch, ImportStats stats) {
        if (batch.isEmpty()) {
            return;
        }

        int[] results = jdbcTemplate.batchUpdate(INSERT_SQL, new PersonaBatchPreparedStatementSetter(batch));
        stats.attempted += batch.size();
        for (int result : results) {
            if (result > 0) {
                stats.inserted += result;
            } else {
                stats.skipped++;
            }
        }
        batch.clear();
    }

    private static class PersonaBatchPreparedStatementSetter implements BatchPreparedStatementSetter {

        private final List<PersonaImportRow> rows;
        private final Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        PersonaBatchPreparedStatementSetter(List<PersonaImportRow> rows) {
            this.rows = rows;
        }

        @Override
        public void setValues(PreparedStatement ps, int i) throws SQLException {
            PersonaImportRow row = rows.get(i);
            ps.setString(1, row.source());
            ps.setString(2, row.sourceId());
            if (row.age() == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, row.age());
            }
            ps.setString(4, row.ageGroup());
            ps.setString(5, row.gender());
            ps.setString(6, row.region());
            ps.setString(7, row.province());
            ps.setString(8, row.district());
            ps.setString(9, row.occupation());
            ps.setString(10, row.personaSummary());
            ps.setString(11, row.interests());
            ps.setString(12, row.painPoints());
            ps.setString(13, row.digitalFamiliarity());
            ps.setString(14, row.buyingSensitivity());
            ps.setString(15, row.rawData());
            ps.setBoolean(16, row.active());
            ps.setTimestamp(17, now);
            ps.setTimestamp(18, now);
        }

        @Override
        public int getBatchSize() {
            return rows.size();
        }
    }

    private static class ImportStats {
        private long read;
        private long attempted;
        private long inserted;
        private long skipped;
        private long failed;
    }
}
