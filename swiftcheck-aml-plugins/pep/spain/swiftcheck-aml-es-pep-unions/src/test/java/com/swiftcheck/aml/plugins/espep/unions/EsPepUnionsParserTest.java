package com.swiftcheck.aml.plugins.espep.unions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;

class EsPepUnionsParserTest {

    private static String read(String f) throws Exception {
        return Files.readString(Path.of("src/test/resources/" + f), StandardCharsets.UTF_8);
    }

    @Test
    void extractsCeoeLeadershipFromRealPage() throws Exception {
        List<String[]> found = EsPepUnionsParser.extractCeoe(read("ceoe-equipo.html"));
        assertThat(found.stream().map(f -> f[0])).contains("Antonio Garamendi Lecanda", "Lorenzo Amor Acedo");
        assertThat(found.stream().filter(f -> f[0].startsWith("Antonio")).findFirst().get()[1]).isEqualTo("Presidente");
    }

    @Test
    void extractsUsoExecutiveFromRealPage() throws Exception {
        List<String[]> found = EsPepUnionsParser.extractUso(read("uso-ejecutiva.html"));
        assertThat(found.stream().map(f -> f[0])).contains("Joaquín Pérez da Silva");
        assertThat(found.stream().map(f -> f[0])).doesNotHaveDuplicates();
    }

    @Test
    void convertsExtractedLeaders(@TempDir Path dir) throws Exception {
        ObjectMapper om = new ObjectMapper();
        var root = om.createObjectNode();
        var arr = root.putArray("leaders");
        for (String[] f : EsPepUnionsParser.extractCeoe(read("ceoe-equipo.html"))) {
            arr.addObject().put("name", f[0]).put("role", f[1]).put("org", "CEOE").put("type", "employer").put("status", "current");
        }
        for (String[] f : EsPepUnionsParser.extractUso(read("uso-ejecutiva.html"))) {
            arr.addObject().put("name", f[0]).put("role", f[1]).put("org", "USO").put("type", "union").put("status", "current");
        }
        Path raw = dir.resolve("raw.json");
        Files.writeString(raw, om.writeValueAsString(root), StandardCharsets.UTF_8);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(EsPepUnionsParser.ID_PREFIX, EsPepUnionsParser.TOPIC);
        int count = new EsPepUnionsParser().convert(raw, dataset);
        assertThat(count).isEqualTo(arr.size());
        assertThat(dataset.qualityReport().getTotalRejected()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();
    }
}
