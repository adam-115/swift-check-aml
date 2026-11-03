package com.swiftcheck.aml.plugins.bepep.publicenterprises;

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
 * Tests sans réseau sur les réponses réelles de l'API Regimand (Cour des comptes) pour les 15 entreprises publiques
 * belges configurées.
 */
class BePepPublicEnterprisesParserTest {

    private static final Path RAW = Path.of("src/test/resources/regimand");

    private final BePepPublicEnterprisesParser parser = new BePepPublicEnterprisesParser();

    @Test
    void convertsRealRegimandPublicEnterprises() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(BePepPublicEnterprisesParser.ID_PREFIX);
        BePepPublicEnterprisesParser.Report report = parser.convert(RAW, dataset);

        assertThat(report.enterprises()).isGreaterThanOrEqualTo(12);
        assertThat(report.persons()).isGreaterThan(100);
        assertThat(report.mandates()).isGreaterThan(100);
        assertThat(report.sfpiAvailable()).isTrue();
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.toConversionResult().conflictCount()).as("ID conflicts between different schemas").isZero();

        List<FtmEntity> companies = dataset.entities().stream()
                .filter(e -> "Company".equals(e.getSchema()))
                .toList();
        assertThat(companies).hasSize(report.enterprises())
                .allSatisfy(c -> assertThat(c.getValues("topics")).containsExactly("gov.soe"));

        List<FtmEntity> persons = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()))
                .toList();
        assertThat(persons).hasSize(report.persons())
                .allSatisfy(p -> {
                    assertThat(p.getValues("topics")).containsExactly("role.pep");
                    assertThat(p.getValues("country")).containsExactly("be");
                });
    }

    @Test
    void filtersNonGovernanceRoles() {
        assertThat(BePepPublicEnterprisesParser.isGovernanceRole("Membre du conseil d'administration")).isTrue();
        assertThat(BePepPublicEnterprisesParser.isGovernanceRole("Président du comité de direction")).isTrue();
        assertThat(BePepPublicEnterprisesParser.isGovernanceRole("Commissaire du gouvernement")).isTrue();
        assertThat(BePepPublicEnterprisesParser.isGovernanceRole("Administrateur délégué")).isTrue();
        assertThat(BePepPublicEnterprisesParser.isGovernanceRole("Employé")).isFalse();
        assertThat(BePepPublicEnterprisesParser.isGovernanceRole("Bediende")).isFalse();
        assertThat(BePepPublicEnterprisesParser.isGovernanceRole("Retraité")).isFalse();
    }

    @Test
    void readSourceRecordsListsLeaders() throws Exception {
        List<SourceRecord> records = parser.readSourceRecords(
                new ParserContext("be_pep_public_enterprises", null, RAW, null, Map.of()));
        assertThat(records).hasSizeGreaterThan(100).allSatisfy(r -> {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.name()).isNotBlank();
        });
    }

    @Test
    void emptyDirectoryIsAnError(@TempDir Path tmp) {
        assertThatThrownBy(() -> parser.convert(tmp, new FtmDatasetBuilder(BePepPublicEnterprisesParser.ID_PREFIX)))
                .isInstanceOf(IllegalStateException.class);
    }
}

