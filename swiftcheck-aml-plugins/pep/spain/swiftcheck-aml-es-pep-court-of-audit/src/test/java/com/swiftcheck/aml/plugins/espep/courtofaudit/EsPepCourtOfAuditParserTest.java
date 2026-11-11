package com.swiftcheck.aml.plugins.espep.courtofaudit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class EsPepCourtOfAuditParserTest {

    private static final Path FIXTURE = Path.of("src/test/resources/tcu-organizacion.html");

    @Test
    void extractsPlenaryMembersFromRealPage() throws Exception {
        List<String[]> found = EsPepCourtOfAuditParser.extract(Files.readString(FIXTURE, StandardCharsets.UTF_8));
        assertThat(found).isNotEmpty();
        assertThat(found.stream().map(f -> f[0])).contains("Enriqueta Chicano Jávega");
        assertThat(found.stream().map(f -> f[0])).doesNotHaveDuplicates();
    }

    @Test
    void convertsExtractedMembers(@TempDir Path dir) throws Exception {
        List<String[]> found = EsPepCourtOfAuditParser.extract(Files.readString(FIXTURE, StandardCharsets.UTF_8));
        ObjectMapper om = new ObjectMapper();
        var root = om.createObjectNode();
        var arr = root.putArray("members");
        for (String[] f : found) {
            arr.addObject().put("name", f[0]).put("role", f[1]).put("status", "current");
        }
        Path raw = dir.resolve("raw.json");
        Files.writeString(raw, om.writeValueAsString(root), StandardCharsets.UTF_8);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepCourtOfAuditParser.ID_PREFIX, EsPepCourtOfAuditParser.TOPIC);
        int count = new EsPepCourtOfAuditParser().convert(raw, dataset);

        assertThat(count).isEqualTo(found.size());
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();
    }
}
