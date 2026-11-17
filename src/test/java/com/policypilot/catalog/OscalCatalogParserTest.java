package com.policypilot.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Fixture = real catalog excerpt: AC-1, AC-2, AC-2(3) and the withdrawn AC-2(10). */
class OscalCatalogParserTest {

    private static final OscalCatalogParser parser = new OscalCatalogParser(new ObjectMapper());
    private static Map<String, ControlChunk> byId;

    @BeforeAll
    static void parseFixture() throws Exception {
        try (InputStream in = OscalCatalogParserTest.class.getResourceAsStream("/fixtures/mini-catalog.json")) {
            byId = parser.parse(in).stream().collect(Collectors.toMap(ControlChunk::controlId, Function.identity()));
        }
    }

    @Test
    void keepsControlsAndEnhancementsButSkipsWithdrawn() {
        assertThat(byId).containsOnlyKeys("AC-1", "AC-2", "AC-2(3)");
    }

    @Test
    void enhancementPointsToItsParentAndFamily() {
        ControlChunk c = byId.get("AC-2(3)");
        assertThat(c.parentId()).isEqualTo("AC-2");
        assertThat(c.family()).isEqualTo("AC");
        assertThat(c.familyTitle()).isEqualTo("Access Control");
        assertThat(c.title()).isEqualTo("Disable Accounts");
        assertThat(c.sortId()).isEqualTo("ac-02.03");
        assertThat(byId.get("AC-2").parentId()).isNull();
    }

    @Test
    void rendersAssignmentParametersLikeThePublishedStandard() {
        assertThat(byId.get("AC-2(3)").statement())
                .startsWith("Disable accounts within [Assignment: organization-defined time period] when the accounts:")
                .contains("(a) Have expired;");
    }

    @Test
    void rendersSelectionParametersWithAllChoices() {
        assertThat(byId.get("AC-1").statement())
                .contains("[Selection (one or more): organization-level; mission/business process-level; system-level]");
    }

    @Test
    void keepsStatementItemLabelsAndNesting() {
        String s = byId.get("AC-2").statement();
        assertThat(s).contains("a. Define and document the types of accounts allowed")
                .contains("b. Assign account managers;");
        // Sub-items (1., 2.) are indented under their parent item.
        assertThat(s).containsPattern("\\n  1\\. ");
    }

    @Test
    void noUnrenderedPlaceholdersRemain() {
        byId.values().forEach(c -> {
            assertThat(c.statement()).doesNotContain("{{");
            assertThat(c.guidance()).doesNotContain("{{");
        });
    }

    @Test
    void guidanceIsNistDiscussionNotAssessmentText() {
        assertThat(byId.get("AC-2").guidance()).startsWith("Examples of system account types include");
    }

    @Test
    void embeddingTextLeadsWithIdAndTitleAndCapsGuidance() {
        ControlChunk c = byId.get("AC-2");
        assertThat(c.embeddingText()).startsWith("AC-2 Account Management.");
        assertThat(c.embeddingText().length()).isLessThan(c.statement().length() + 700);
        assertThat(c.contentHash()).hasSize(64).isEqualTo(byId.get("AC-2").contentHash());
    }

    @Test
    void rejectsJsonThatIsNotACatalog() {
        InputStream notCatalog = new ByteArrayInputStream("{\"profile\":{}}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> parser.parse(notCatalog)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Runs against the full downloaded catalog when present (CI downloads it; scripts/fetch-catalog.*). */
    @Test
    void fullCatalogParsesToUniqueIds() throws Exception {
        Path full = Path.of("data/NIST_SP-800-53_rev5_catalog.json");
        assumeTrue(Files.exists(full), "full catalog not downloaded");
        List<ControlChunk> all;
        try (InputStream in = Files.newInputStream(full)) {
            all = parser.parse(in);
        }
        // 324 controls + 872 enhancements - 182 withdrawn in Rev 5.2.0.
        assertThat(all).hasSize(1014);
        assertThat(new HashSet<>(all.stream().map(ControlChunk::controlId).toList())).hasSize(all.size());
        assertThat(all).allSatisfy(c -> {
            assertThat(c.statement()).isNotBlank().doesNotContain("{{");
            assertThat(c.title()).isNotBlank();
        });
        assertThat(all.stream().map(ControlChunk::family).distinct()).hasSize(20);
    }
}
