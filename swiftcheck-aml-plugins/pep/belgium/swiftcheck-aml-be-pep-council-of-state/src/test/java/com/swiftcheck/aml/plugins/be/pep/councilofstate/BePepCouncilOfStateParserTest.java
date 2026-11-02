package com.swiftcheck.aml.plugins.be.pep.councilofstate;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires hors-ligne de {@link BePepCouncilOfStateParser} sur la page
 * officielle {@code raadvst-consetat.be}.
 */
class BePepCouncilOfStateParserTest {

    @Test
    void parsesJudicialLeadersAndExcludesAdministrativeStaff(@TempDir Path tempDir) throws IOException {
        Path htmlFile = tempDir.resolve(BePepCouncilOfStateParser.FILE_NAME);
        copyResource("council-of-state-direction.html", htmlFile);

        BePepCouncilOfStateParser parser = new BePepCouncilOfStateParser();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(
                BePepCouncilOfStateParser.ID_PREFIX, BePepCouncilOfStateParser.TOPIC);

        List<BePepCouncilOfStateParser.MemberRecord> records = parser.convert(htmlFile, dataset);
        assertEquals(4, records.size(), "Les 4 chefs de corps / hauts magistrats doivent être extraits");

        assertTrue(records.stream().anyMatch(r ->
                "Wilfried".equals(r.firstName()) && "VAN VAERENBERGH".equals(r.lastName())
                && "Premier Président".equals(r.function())));
        assertTrue(records.stream().anyMatch(r ->
                "Pascale".equals(r.firstName()) && "VANDERNACHT".equals(r.lastName())
                && "Présidente".equals(r.function())));
        assertTrue(records.stream().anyMatch(r ->
                "Luc".equals(r.firstName()) && "VERMEIRE".equals(r.lastName())
                && "Auditeur général".equals(r.function())));
        assertTrue(records.stream().anyMatch(r ->
                "Eric".equals(r.firstName()) && "THIBAUT".equals(r.lastName())
                && "Auditeur général adjoint".equals(r.function())));

        assertFalse(records.stream().anyMatch(r -> r.function().toLowerCase().contains("greffier")));
        assertFalse(records.stream().anyMatch(r -> r.function().toLowerCase().contains("administrateur")));

        List<FtmEntity> persons = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertEquals(4, persons.size());
    }

    private static void copyResource(String resourceName, Path target) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                BePepCouncilOfStateParserTest.class.getClassLoader().getResourceAsStream(resourceName),
                "Ressource de test introuvable : " + resourceName)) {
            Files.copy(in, target);
        }
    }
}
