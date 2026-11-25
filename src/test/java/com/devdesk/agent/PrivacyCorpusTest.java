package com.devdesk.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyCorpusTest {
    record Observation(String corpus, String id, boolean expectedBlocked, boolean blocked) {}

    private static final List<String> ORIGINAL_CORPORA =
            List.of("privacy-cases", "privacy-holdout", "privacy-postfreeze");

    @Test
    void previouslyMeasuredInputsKeepTheirExpectedDecisions() throws Exception {
        var rows = observe(ORIGINAL_CORPORA);
        assertThat(rows).hasSize(46).filteredOn(row -> row.blocked() != row.expectedBlocked()).isEmpty();
    }

    @Test
    void firstIndependentSampleNowServesAsRegression() throws Exception {
        assertThat(observe(List.of("privacy-independent-final"))).hasSize(18)
                .filteredOn(row -> row.blocked() != row.expectedBlocked()).isEmpty();
    }

    @Test
    void recordsCorpusDecisionsWhenRequested() throws Exception {
        var output = System.getenv("PRIVACY_OBSERVATIONS");
        org.junit.jupiter.api.Assumptions.assumeTrue(output != null);
        String independent = System.getenv("PRIVACY_INDEPENDENT_CORPUS");
        var corpora = new ArrayList<>(ORIGINAL_CORPORA);
        if (independent != null) {
            corpora.addAll(List.of(independent.split(",")));
        }
        var rows = observe(corpora);
        Files.writeString(Path.of(output), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(rows),
                StandardOpenOption.CREATE_NEW);
    }

    private List<Observation> observe(List<String> corpora) throws Exception {
        var mapper = new ObjectMapper();
        var guard = new UserInputSafetyAdvisor();
        var rows = new ArrayList<Observation>();
        for (var corpus : corpora) {
            try (var stream = getClass().getResourceAsStream("/quantitative/" + corpus + ".json")) {
                for (var row : mapper.readTree(stream)) {
                    rows.add(new Observation(corpus, row.required("id").asText(),
                            row.required("expectedBlocked").asBoolean(),
                            guard.rejectionFor(row.required("input").asText()) != null));
                }
            }
        }
        return rows;
    }
}
