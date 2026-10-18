package com.swiftcheck.aml.plugins.lupep.municipal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/** Conversion d'un extrait réel du fichier des collèges échevinaux (encodage Windows-1252). */
class LuPepMunicipalParserTest {

    private static FtmDatasetBuilder result;

    @BeforeAll
    static void convertSample() throws Exception {
        result = convert(Path.of(LuPepMunicipalParserTest.class.getResource("/municipal-councils.csv").toURI()));
    }

    private static FtmDatasetBuilder convert(Path csv) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LuPepMunicipalParser.ID_PREFIX, LuPepMunicipalParser.TOPIC);
        new LuPepMunicipalParser().convert(csv,
                LuPepMunicipalParser.oldestEndDate(Map.of(), LocalDate.of(2026, 10, 17)), dataset);
        return dataset;
    }

    /** Renvoie l'entité portant cet ID, ou échoue avec un message clair. */
    private static FtmEntity entity(String id) {
        return result.entities().stream()
                .filter(e -> e.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Entité absente : " + id));
    }

    @Test
    void mayorPostIsLinkedToItsCommune() {
        FtmEntity mayor = entity("lu-pep-municipal-mayor-C003");
        assertEquals(Set.of("Bourgmestre de Beaufort"), mayor.getValues("name"));
        assertEquals(Set.of("Beaufort"), mayor.getValues("subnationalArea"));
        assertEquals(Set.of("gov.muni"), mayor.getValues("topics"));

        FtmEntity mandate = entity("lu-pep-municipal-Nosbusch-Jean-Luc-C003-mayor-2023-10-27");
        assertEquals(Set.of("lu-pep-municipal-Nosbusch-Jean-Luc-C003"), mandate.getValues("holder"));
        assertEquals(Set.of("current"), mandate.getValues("status"), "« 27-Oct-23 » → 2023-10-27, pas de fin");
    }

    @Test
    void accentedFunctionAndEndDateAreRead() {
        // « Échevin » est encodé en Windows-1252 dans le fichier
        assertEquals(Set.of("Échevin de Luxembourg"), entity("lu-pep-municipal-alderman-C066").getValues("name"));

        FtmEntity mandate = entity("lu-pep-municipal-Wilmes-Serge-C066-alderman-2023-07-06");
        assertEquals(Set.of("ended"), mandate.getValues("status"));
        assertEquals(Set.of("2023-11-16"), mandate.getValues("endDate"));
    }

    @Test
    void conversionHasNoRejectedValue() {
        assertEquals(0, result.qualityReport().getTotalRejected());
    }

    @Test
    void unreadableDateStopsTheConversion(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("broken.csv");
        Files.writeString(csv, "COM_CODE,COM_LABEL,MAN_LABEL,ELU_LAST_NAME,ELU_FIRST_NAME,ELU_SEX,COAC_START_DATE,COAC_END_DATE\n"
                + "C003,Beaufort,Bourgmestre,Nosbusch,Jean-Luc,M,27-Oct-23,bientôt\n");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> convert(csv));
        assertTrue(error.getMessage().contains("ligne 2"), error.getMessage());
    }
}
