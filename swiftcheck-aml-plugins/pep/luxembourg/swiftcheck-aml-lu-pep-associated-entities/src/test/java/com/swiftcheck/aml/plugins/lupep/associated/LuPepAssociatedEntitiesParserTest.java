package com.swiftcheck.aml.plugins.lupep.associated;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugins.lupep.associated.DeclarationReader.Declaration;
import com.swiftcheck.aml.plugins.lupep.associated.DeclarationReader.HoldingKind;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Tests sur de vraies déclarations publiées sur chd.lu (octobre 2026), copiées dans {@code src/test/resources/chd} :
 * formulaire rempli à l'ordinateur (Sven Clement, Barbara Agostino, Françoise Kemp, Yves Cruchten), formulaire vide
 * (Djuna Bernard), scan sans texte (Diane Adehm), scan passé à l'OCR (Meris Sehovic) et déclaration manuscrite mal
 * reconnue (Félix Eischen).
 */
class LuPepAssociatedEntitiesParserTest {

    private static Path resources;

    @BeforeAll
    static void locateResources() throws URISyntaxException {
        resources = Path.of(Objects.requireNonNull(
                LuPepAssociatedEntitiesParserTest.class.getResource("/chd/deputies.json")).toURI()).getParent();
    }

    @Test
    void readsBothSubTablesOfSectionG() throws IOException {
        Declaration d = DeclarationReader.read(resources.resolve("957.pdf"));

        assertThat(d.scanned()).isFalse();
        assertThat(d.mandates()).hasSize(10).contains("VOLLEY CLUB BELAIR ASBL", "Guiden a Scouten fir eng Welt ONGD");
        // Le premier tableau (politique publique) est « Non applicable » : tout est dans le second
        assertThat(d.holdings()).hasSize(6)
                .allMatch(h -> h.kind() == HoldingKind.SIGNIFICANT_INFLUENCE);
        assertThat(d.holdings().get(0).text()).isEqualTo("Clement & Weyer Consulting SARL (50%)");
        assertThat(d.holdings().get(1).text()).isEqualTo("SALARY SA (AccountTech détient 78%)");
    }

    @Test
    void splitsNumbersGluedToThePreviousLine() throws IOException {
        Declaration d = DeclarationReader.read(resources.resolve("1281.pdf"));

        assertThat(d.mandates()).contains("Conseil pastoral de Dudelange", "ONGD Guiden a Scouten fir eng Welt",
                "LGS St Jean Diddeleng", "Leo Lions");
        assertThat(d.mandates()).noneMatch(m -> m.matches(".*\\s\\d\\.\\p{L}.*"));
    }

    @Test
    void ignoresFootnotesAndEmptyForms() throws IOException {
        Declaration ocr = DeclarationReader.read(resources.resolve("1255.pdf"));
        assertThat(ocr.holdings()).isEmpty();
        assertThat(ocr.mandates()).anyMatch(m -> m.contains("Pedibus Esch"));

        Declaration empty = DeclarationReader.read(resources.resolve("966.pdf"));
        assertThat(empty.sectionsFound()).isTrue();
        assertThat(empty.mandates()).isEmpty();
        assertThat(empty.holdings()).isEmpty();
    }

    @Test
    void detectsScannedDeclarations() throws IOException {
        assertThat(DeclarationReader.read(resources.resolve("821.pdf")).scanned()).isTrue();
    }

    @Test
    void cleansOrganisationNames() {
        assertThat(entry("Clement & Weyer Consulting SARL (50%)")).isEqualTo(
                new LuPepAssociatedEntitiesParser.Entry("Clement & Weyer Consulting SARL", "50"));
        // Participation indirecte : le pourcentage est celui d'une autre société, pas celui du député
        assertThat(entry("SALARY SA (AccountTech détient 78%)").percentage()).isNull();
        assertThat(entry("SALARY SA (AccountTech détient 78%)").name()).isEqualTo("SALARY SA");
        assertThat(entry("ALBAIMMO SARL 50%").percentage()).isEqualTo("50");
        assertThat(entry("LET’Z TRAIN SARL (CLOTURE 02/24)").name()).isEqualTo("LET’Z TRAIN SARL");
        assertThat(entry("Membre du comité directeur du Parti démocratique").name()).isEqualTo("Parti démocratique");
        assertThat(entry("Membre du CA Fondation Kräizbierg").name()).isEqualTo("Fondation Kräizbierg");
        assertThat(entry("Amis de l'Orgue Diekirch , membre du conseil d'administration").name())
                .isEqualTo("Amis de l'Orgue Diekirch");
        assertThat(entry("ADR: Membre du bureau exécutif, vice-présidente").name()).isEqualTo("ADR");
        assertThat(entry("Sunelec S.A. , 4 parts").name()).isEqualTo("Sunelec S.A.");
        assertThat(entry("Banque Raiffeisen sc 1 Part sociale à valeur nominale de 25 €").name())
                .isEqualTo("Banque Raiffeisen sc");
        // Écriture manuscrite mal reconnue par l'OCR
        assertThat(LuPepAssociatedEntitiesParser.Entry.of("fÇ AiAfiük")).isNull();
        assertThat(LuPepAssociatedEntitiesParser.Entry.of("S ‘Poet ù^|e/h T f cJ^eQiCn^")).isNull();
    }

    @Test
    void convertsDeclarationsToEntities(@TempDir Path rawDir) throws Exception {
        copyResources(rawDir);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LuPepAssociatedEntitiesParser.ID_PREFIX);

        LuPepAssociatedEntitiesParser.Report report = new LuPepAssociatedEntitiesParser().convert(rawDir, dataset);

        assertThat(report.deputies()).isEqualTo(10);
        assertThat(report.declarations()).isEqualTo(8);
        assertThat(report.scanned()).containsExactly("Diane Adehm");
        assertThat(report.skipped()).isGreaterThan(0);                    // déclaration manuscrite de F. Eischen
        assertThat(report.message()).contains("scannées sans texte, non lues : Diane Adehm");
        assertThat(dataset.toConversionResult().rejectedCount()).isZero();
        assertThat(dataset.toConversionResult().conflictCount()).isZero();

        Map<String, FtmEntity> byId = dataset.entities().stream()
                .collect(Collectors.toMap(FtmEntity::getId, e -> e));
        FtmEntity clement = byId.get(dataset.buildEntityId("deputy", "957"));
        assertThat(clement.getSchema()).isEqualTo("Person");
        assertThat(clement.getValues("name")).containsExactly("Sven Clement");
        assertThat(clement.getValues("topics")).containsExactly("role.pep");

        FtmEntity consulting = byId.get(dataset.buildEntityId("org", "Clement & Weyer Consulting SARL"));
        assertThat(consulting.getValues("topics")).containsExactly("role.rca");
        FtmEntity ownership = byId.get(dataset.buildEntityId("957", "owner", consulting.getId()));
        assertThat(ownership.getValues("owner")).containsExactly(clement.getId());
        assertThat(ownership.getValues("percentage")).containsExactly("50");
        assertThat(ownership.getValues("summary")).containsExactly("Participation conférant une influence significative");
        assertThat(ownership.getValues("sourceUrl"))
                .containsExactly("https://wdocs-pub.chd.lu/declarations/957_2023-2028_DIF_rev3.pdf");

        // Même organisme en section E et G : une seule entité, deux liens
        FtmEntity salary = byId.get(dataset.buildEntityId("org", "SALARY SA"));
        assertThat(byId).containsKeys(dataset.buildEntityId("957", "director", salary.getId()),
                dataset.buildEntityId("957", "owner", salary.getId()));

        // Formulaire vide, scan et déclaration illisible : pas de personne écrite
        assertThat(byId).doesNotContainKeys(dataset.buildEntityId("deputy", "966"),
                dataset.buildEntityId("deputy", "821"));
        assertThat(names(dataset, "Organization")).noneMatch(n -> n.contains("^") || n.endsWith("0.-"))
                .contains("Tennis Club Clemency asbl", "ALBAIMMO SARL", "Pedibus Esch a.s.b.I.");
    }

    @Test
    void choosesTheLatestDeclarationOnTheDeputyPage() throws IOException {
        String page = Files.readString(resources.resolve("deputy-957.html"), StandardCharsets.UTF_8);

        assertThat(ChdDeclarationDownloader.name(page)).contains("Sven Clement");
        // Législature 2023-2028 avant les déclarations sans législature, puis la révision la plus haute
        assertThat(ChdDeclarationDownloader.latestDeclaration(page, "957"))
                .contains("https://wdocs-pub.chd.lu/declarations/957_2023-2028_DIF_rev3.pdf");
        assertThat(ChdDeclarationDownloader.latestDeclaration(page, "999")).isEmpty();
    }

    @Test
    void downloadsEachDeputyDeclaration(@TempDir Path rawDir) throws Exception {
        String list = "<a href=\"/fr/deputy/957\">Sven Clement</a> <a href=\"/fr/deputy/1\">X</a>"
                + " <a href=\"/fr/deputy/957\">doublon</a>";
        Map<String, byte[]> site = new HashMap<>();
        site.put("https://www.chd.lu/fr/deputies", list.getBytes(StandardCharsets.UTF_8));
        site.put("https://www.chd.lu/fr/deputy/957", Files.readAllBytes(resources.resolve("deputy-957.html")));
        site.put("https://www.chd.lu/fr/deputy/1", "<title>Sans Déclaration | Chambre</title>".getBytes(StandardCharsets.UTF_8));
        site.put("https://wdocs-pub.chd.lu/declarations/957_2023-2028_DIF_rev3.pdf",
                Files.readAllBytes(resources.resolve("957.pdf")));

        List<ChdDeclarationDownloader.Deputy> deputies = new ChdDeclarationDownloader(
                url -> Optional.ofNullable(site.get(url)).orElseThrow(() -> new IOException("404 " + url)), 0)
                .download("https://www.chd.lu/fr/deputies", rawDir);

        assertThat(deputies).extracting(ChdDeclarationDownloader.Deputy::name)
                .containsExactly("Sven Clement", "Sans Déclaration");
        assertThat(deputies.get(1).file()).isNull();
        assertThat(rawDir.resolve("957.pdf")).exists();
        assertThat(rawDir.resolve(ChdDeclarationDownloader.DEPUTIES_FILE)).exists();
    }

    private static LuPepAssociatedEntitiesParser.Entry entry(String text) {
        return LuPepAssociatedEntitiesParser.Entry.of(text);
    }

    private static List<String> names(FtmDatasetBuilder dataset, String schema) {
        return dataset.entities().stream().filter(e -> schema.equals(e.getSchema()))
                .flatMap(e -> e.getValues("name").stream()).toList();
    }

    private static void copyResources(Path target) throws IOException {
        try (var files = Files.list(resources)) {
            for (Path f : files.toList()) {
                try (InputStream in = Files.newInputStream(f)) {
                    Files.copy(in, target.resolve(f.getFileName().toString()));
                }
            }
        }
    }
}
