package com.policypilot.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.policypilot.catalog.ControlChunk;
import com.policypilot.catalog.OscalCatalogParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Runs against a real PostgreSQL (database policypilot_test), because the behavior under test is
 * Postgres itself: the generated tsvector column, websearch_to_tsquery ranking and REAL[] arrays.
 * Locally: createdb policypilot_test. In CI: a postgres:16 service container.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.datasource.url=${PP_TEST_DB_URL:jdbc:postgresql://localhost:5432/policypilot_test}")
@Import(ControlRepository.class)
class ControlRepositoryIntegrationTest {

    @Autowired
    ControlRepository repo;
    @Autowired
    JdbcTemplate jdbc;

    List<ControlChunk> chunks;

    @BeforeEach
    void load() throws Exception {
        jdbc.update("DELETE FROM controls");
        try (InputStream in = getClass().getResourceAsStream("/fixtures/mini-catalog.json")) {
            chunks = new OscalCatalogParser(new ObjectMapper()).parse(in);
        }
        chunks.forEach(c -> repo.upsert(c, null, null));
    }

    @Test
    void keywordSearchFindsTheEnhancementThatMatchesTheQuestion() {
        assertThat(repo.searchText("disable expired or inactive accounts", 3)).first().isEqualTo("AC-2(3)");
    }

    @Test
    void keywordSearchRanksTitleMatchesAboveDiscussionMentions() {
        assertThat(repo.searchText("account management", 3)).first().isEqualTo("AC-2");
    }

    @Test
    void keywordSearchToleratesRawUserInput() {
        // Unbalanced quotes and operators would make to_tsquery throw; websearch_to_tsquery doesn't.
        assertThat(repo.searchText("\"policy & procedures -", 5)).isNotNull();
    }

    @Test
    void storesAndReadsBackEmbeddings() {
        ControlChunk ac2 = byId("AC-2");
        repo.upsert(ac2, new float[] {0.25f, -1.5f, 3f}, "test-model");
        assertThat(repo.findEmbedding("AC-2")).containsExactly(0.25f, -1.5f, 3f);
        assertThat(repo.embeddedFingerprints()).containsEntry("AC-2", ac2.contentHash() + "|test-model");
    }

    @Test
    void reIngestWithoutVectorKeepsEmbeddingOnlyWhileTextIsUnchanged() {
        ControlChunk ac2 = byId("AC-2");
        repo.upsert(ac2, new float[] {1f}, "m");

        repo.upsert(ac2, null, null);
        assertThat(repo.findEmbedding("AC-2")).containsExactly(1f);

        ControlChunk edited = new ControlChunk(ac2.controlId(), ac2.sortId(), ac2.family(), ac2.familyTitle(), ac2.parentId(),
                ac2.title(), ac2.statement() + " (revised)", ac2.guidance());
        repo.upsert(edited, null, null);
        assertThat(repo.findEmbedding("AC-2")).as("stale vector must be dropped when the text changes").isNull();
    }

    @Test
    void upsertIsIdempotentAndDeleteRemovesControlsThatLeftTheCatalog() {
        chunks.forEach(c -> repo.upsert(c, null, null));
        assertThat(repo.count()).isEqualTo(3);

        assertThat(repo.deleteAllExcept(Set.of("AC-2", "AC-2(3)"))).isEqualTo(1);
        assertThat(repo.count()).isEqualTo(2);
    }

    private ControlChunk byId(String id) {
        return chunks.stream().filter(c -> c.controlId().equals(id)).findFirst().orElseThrow();
    }
}
