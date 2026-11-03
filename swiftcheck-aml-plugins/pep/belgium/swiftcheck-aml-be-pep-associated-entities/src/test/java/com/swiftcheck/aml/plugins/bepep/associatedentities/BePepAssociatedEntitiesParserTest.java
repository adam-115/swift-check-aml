package com.swiftcheck.aml.plugins.bepep.associatedentities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Tests sans réseau sur les réponses réelles de l'API Regimand (Cour des comptes) pour les déclarations de mandats
 * des parlementaires et membres de gouvernement belges.
 */
class BePepAssociatedEntitiesParserTest {

    private static final Path RAW = Path.of("src/test/resources/regimand");

    private final BePepAssociatedEntitiesParser parser = new BePepAssociatedEntitiesParser();

    @Test
    void convertsRealRegimandAssociatedEntities() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepAssociatedEntitiesParser.ID_PREFIX);
        BePepAssociatedEntitiesParser.Report report = parser.convert(RAW, dataset);

        assertThat(report.statementsRead()).isGreaterThan(300);
        assertThat(report.pepsWithLinks()).isGreaterThan(100);
        assertThat(report.organizations()).isGreaterThan(20);
        assertThat(report.directorships()).isGreaterThan(100);
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();

        List<FtmEntity> orgs = dataset.entities().stream()
                .filter(e -> "Organization".equals(e.getSchema()))
                .toList();
        assertThat(orgs).hasSize(report.organizations())
                .allSatisfy(o -> assertThat(o.getValues("topics")).containsExactly("role.rca"));

        List<FtmEntity> peps = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertThat(peps).hasSize(report.pepsWithLinks())
                .allSatisfy(p -> assertThat(p.getValues("topics")).containsExactly("role.pep"));
    }

    @Test
    void readSourceRecordsListsAssociatedOrganizations() throws Exception {
        List<SourceRecord> records = parser.readSourceRecords(
                new ParserContext("be_pep_associated_entities", null, RAW, null, Map.of()));
        assertThat(records).hasSizeGreaterThan(20).allSatisfy(r -> {
            assertThat(r.schema()).isEqualTo("Organization");
            assertThat(r.name()).isNotBlank();
            assertThat(r.sourceRef()).isEqualTo("https://public.regimand.be");
        });
    }

    @Test
    void emptyDirectoryIsAnError(@TempDir Path tmp) {
        assertThatThrownBy(() -> parser.convert(tmp, new FtmDatasetBuilder(BePepAssociatedEntitiesParser.ID_PREFIX)))
                .isInstanceOf(IllegalStateException.class);
    }
}

