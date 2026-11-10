package com.swiftcheck.aml.plugins.espep.courtofaudit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class EsPepCourtOfAuditParserTest {

    private static final Path RAW = Path.of("src/test/resources/seed/court-of-audit.json");

    private final EsPepCourtOfAuditParser parser = new EsPepCourtOfAuditParser();

    @Test
    void convertsCourtOfAudit() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                EsPepCourtOfAuditParser.ID_PREFIX,
                EsPepCourtOfAuditParser.TOPIC
        );
        int count = parser.convert(RAW, dataset);

        assertThat(count).isEqualTo(14);
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();

        long persons = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).count();
        assertThat(persons).isEqualTo(14);

        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> {
                    assertThat(p.getValues("country")).containsExactly("es");
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                });
    }
}

