package com.swiftcheck.aml.plugins.frpep.jorf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.frpep.jorf.JorfArticles.Article;
import com.swiftcheck.aml.plugins.frpep.jorf.NominationReader.Event;
import com.swiftcheck.aml.plugins.frpep.jorf.NominationReader.Institution;
import com.swiftcheck.aml.plugins.frpep.jorf.NominationReader.Kind;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Tests sans réseau sur 47 articles réels du Journal officiel (30 textes de 1991 à 2026, open data de la DILA),
 * avec une date du jour fixe (30/10/2026).
 */
class FrPepJorfParserTest {

    private static final Path RAW = Path.of("src/test/resources/jorf");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    private final FrPepJorfParser parser = new FrPepJorfParser();

    private FtmDatasetBuilder convert() throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(FrPepJorfParser.ID_PREFIX, FrPepJorfParser.TOPIC);
        parser.convert(RAW, FrPepJorfParser.Settings.of(Map.of()), TODAY, dataset);
        return dataset;
    }

    /** Les mandats d'une personne : fonction, début, fin, statut. */
    private static List<String> mandates(FtmDatasetBuilder dataset, String name) {
        FtmEntity person = dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow(() -> new AssertionError("Personne absente : " + name));
        return dataset.entities().stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(person.getId()))
                .map(o -> {
                    FtmEntity post = dataset.entities().stream()
                            .filter(e -> e.getId().equals(o.getValues("post").iterator().next())).findFirst().orElseThrow();
                    return post.getValues("name").iterator().next() + " | " + first(o, "startDate") + " → "
                            + first(o, "endDate") + " | " + first(o, "status");
                }).toList();
    }

    private static String first(FtmEntity e, String property) {
        return e.getValues(property).stream().findFirst().orElse("");
    }

    private static boolean hasPerson(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream()
                .anyMatch(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name));
    }

    @Test
    void convertsRealArticles() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FrPepJorfParser.Report report = parser.convert(RAW, FrPepJorfParser.Settings.of(Map.of()), TODAY,
                new FtmDatasetBuilder(FrPepJorfParser.ID_PREFIX));

        assertThat(report.articles()).isEqualTo(47);
        assertThat(report.persons()).isEqualTo(46);
        assertThat(dataset.qualityReport().getTotalRejected()).as(dataset.qualityReport().toString()).isZero();
        assertThat(dataset.qualityReport().getIdConflictCount()).isZero();
        assertThat(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())))
                .allSatisfy(p -> assertThat(p.getValues("topics")).contains("role.pep"));
    }

    @Test
    void nominations() throws Exception {
        FtmDatasetBuilder dataset = convert();
        // Phrase : « M. Florent BOUDIÉ est nommé conseiller d'Etat en service ordinaire (tour extérieur). »
        assertThat(mandates(dataset, "Florent BOUDIÉ")).containsExactly("Conseiller d'État | 2026-09-10 →  | unknown");
        // Collective : « Sont nommés conseillers maîtres à la Cour des comptes, à compter du 1er octobre 2026 : »
        assertThat(mandates(dataset, "Jean-Marc OLERON"))
                .containsExactly("Conseiller maître à la Cour des comptes | 2026-10-01 →  | unknown");
        // Magistrature : section « COUR DE CASSATION », puis « Premier avocat général : M. Xavier TARABEUX, … »
        assertThat(mandates(dataset, "Xavier TARABEUX"))
                .containsExactly("Premier avocat général à la Cour de cassation | 2026-08-17 →  | unknown");
        assertThat(mandates(dataset, "Julie CARROS"))
                .containsExactly("Avocat général référendaire à la Cour de cassation | 2026-08-17 →  | unknown");
        // Promotion dans la même institution : le mandat garde son début (maîtresse des requêtes en 2021)
        assertThat(mandates(dataset, "Amélie Fort-Besnard"))
                .containsExactly("Rapporteur public au Conseil d'État | 2021-09-01 →  | unknown");
        // « est désigné dans les fonctions d'avocat général à la Cour des comptes »
        assertThat(mandates(dataset, "Anatole Puiseux"))
                .containsExactly("Avocat général à la Cour des comptes | 2026-10-01 →  | unknown");
        // Ambassadeur thématique
        assertThat(mandates(dataset, "Adrien ABECASSIS"))
                .containsExactly("Ambassadeur pour le numérique et l'intelligence artificielle | 2026-08-24 →  | unknown");
    }

    @Test
    void departures() throws Exception {
        FtmDatasetBuilder dataset = convert();
        // Nommée première vice-présidente d'un tribunal : quitte la Cour de cassation (sa qualité)
        assertThat(mandates(dataset, "Julie VIGNERAS"))
                .containsExactly("Conseiller référendaire à la Cour de cassation |  → 2026-08-21 | ended");
        // Conseiller maître nommé ambassadeur : quitte la Cour des comptes
        assertThat(mandates(dataset, "Wadid BENAABOU")).containsExactlyInAnyOrder(
                "Ambassadeur de France auprès de la République du Kenya | 2026-08-07 →  | unknown",
                "Conseiller maître à la Cour des comptes |  → 2026-08-07 | ended");
        // Nommé président de chambre régionale des comptes un mois après son entrée
        assertThat(mandates(dataset, "Nicolas FERRU"))
                .containsExactly("Conseiller référendaire à la Cour des comptes | 2026-09-01 → 2026-10-01 | ended");
        // Retraite à venir : mandat en cours jusqu'à la date de départ
        assertThat(mandates(dataset, "Antoine BRUGERE"))
                .containsExactly("Conseiller à la Cour de cassation |  → 2026-11-01 | current");
        // Retraite suivie d'un maintien en fonction jusqu'au 31 décembre 2027
        assertThat(mandates(dataset, "Christine LEFEBVRE-CAPITAINE"))
                .containsExactly("Conseiller à la Cour de cassation |  → 2027-12-31 | current");
    }

    @Test
    void extensions() throws Exception {
        FtmDatasetBuilder dataset = convert();
        // Retraite le 19 octobre 2026, puis maintien en activité « du 19 octobre 2026 au 18 octobre 2029 inclus »
        assertThat(mandates(dataset, "Anne-Marie GRIVEL"))
                .containsExactly("Avocat général à la Cour de cassation |  → 2029-10-18 | current");
        // Maintien en détachement auprès de la Cour des comptes « pour une durée de trois ans »
        assertThat(mandates(dataset, "Natacha PINOY")).containsExactly(
                "Conseiller référendaire en service extraordinaire à la Cour des comptes |  → 2029-09-01 | current");
    }

    @Test
    void ambassadorSucceededAtSamePost() throws Exception {
        FtmDatasetBuilder dataset = convert();
        // Portugal : Brochand (1998), Lequertier (2002), Gautrat (2004), puis Asvazadourian (2026) ; les premiers sont
        // fermés par leur successeur depuis plus de 12 mois, donc écartés
        assertThat(mandates(dataset, "Jean-Pierre ASVAZADOURIAN")).containsExactly(
                "Ambassadeur de France auprès de la République portugaise | 2026-08-03 →  | unknown");
        assertThat(mandates(dataset, "Patrick Gautrat")).containsExactly(
                "Ambassadeur de France auprès de la République portugaise | 2004-08-13 → 2026-08-03 | ended");
        assertThat(hasPerson(dataset, "Pierre Brochand")).isFalse();
        assertThat(hasPerson(dataset, "Daniel Lequertier")).isFalse();
    }

    @Test
    void falsePositivesAndOldMandates() throws Exception {
        FtmDatasetBuilder dataset = convert();
        // « représentant permanent de l'Etat en qualité d'administrateur » d'une société : pas un ambassadeur
        assertThat(dataset.entities().stream().filter(e -> "Position".equals(e.getSchema())))
                .noneMatch(p -> p.getValues("name").iterator().next().contains("APTIC"));
        // 1991 : « M. Jean Merlin, …, en remplacement de M. Combes, admis à faire valoir ses droits à la retraite »
        assertThat(hasPerson(dataset, "Jean Merlin")).isFalse();
        // 1992 : procureur général près la Cour de cassation sans fin connue depuis plus de 15 ans
        assertThat(hasPerson(dataset, "Pierre Truche")).isFalse();
    }

    @Test
    void readerOldFormatsAndEndOfFunctions() {
        // Nom puis prénoms entre parenthèses, phrase coupée en deux lignes
        List<Event> events = NominationReader.read(article("Décret du 10 avril 1997 portant admission à la retraite"
                + " (Cour des comptes)", "Par décret du Président de la République en date du 10 avril 1997, M. Attali"
                + " (Bernard), conseiller maître à la Cour des comptes,", "est admis à faire valoir ses droits à la"
                + " retraite à compter du 1er mai 1997."));
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(Kind.DEPARTURE);
            assertThat(e.name()).isEqualTo("Bernard Attali");
            assertThat(e.institution()).isEqualTo(Institution.COUR_COMPTES);
            assertThat(e.date()).isEqualTo(LocalDate.of(1997, 5, 1));
        });
        // Fin de fonctions dans l'institution
        assertThat(NominationReader.read(article("Arrêté du 14 juin 2012 portant fin de fonctions (Conseil d'Etat)",
                "Par arrêté du vice-président du Conseil d'Etat en date du 14 juin 2012, il est mis fin, à sa demande,"
                        + " à compter du 21 mai 2012, aux fonctions de maître des requêtes en service extraordinaire de"
                        + " M. Alexandre Aïdara, administrateur civil.")))
                .singleElement().satisfies(e -> {
                    assertThat(e.kind()).isEqualTo(Kind.DEPARTURE);
                    assertThat(e.institution()).isEqualTo(Institution.CONSEIL_ETAT);
                    assertThat(e.date()).isEqualTo(LocalDate.of(2012, 5, 21));
                });
        // Fin d'une fonction hors de l'institution : le conseiller maître reste à la Cour des comptes
        assertThat(NominationReader.read(article("Décret du 3 mai 2007 portant cessation de fonctions (Cour des"
                + " comptes)", "Par décret du Président de la République en date du 3 mai 2007, il est mis fin, sur sa"
                + " demande, à compter du 1er juillet 2007, aux fonctions de président de la chambre régionale des"
                + " comptes de Languedoc-Roussillon de M. Guy Piolé, conseiller maître à la Cour des comptes.")))
                .isEmpty();
        // Fin d'une direction exercée par un conseiller d'État : il reste au Conseil d'État
        assertThat(NominationReader.read(article("Décret du 21 décembre 2000 portant fin de fonctions du directeur"
                + " des Archives de France", "Art. 1er. - Il est mis fin, sur sa demande, aux fonctions de M. Philippe"
                + " Belaval, conseiller d'Etat, directeur des Archives de France."))).isEmpty();
    }

    @Test
    void readSourceRecordsListsPersons(@TempDir Path tmp) throws Exception {
        Files.copy(RAW.resolve(JorfArticles.ARTICLES_FILE), tmp.resolve(JorfArticles.ARTICLES_FILE));
        // Durées larges : le résultat ne dépend pas de la date du jour
        List<SourceRecord> records = parser.readSourceRecords(new ParserContext("fr_pep_jorf", null, tmp, null,
                Map.of("monthsAfterEnd", "1200", "ambassadorMaxYears", "100", "cassationMaxYears", "100")));
        assertThat(records).isNotEmpty().allSatisfy(r -> {
            assertThat(r.schema()).isEqualTo("Person");
            assertThat(r.sourceRef()).startsWith("https://www.legifrance.gouv.fr/jorf/id/JORFTEXT");
        });
        assertThat(records).extracting(SourceRecord::name).contains("Xavier TARABEUX", "Pierre Truche");
    }

    @Test
    void emptyArchiveIsAnError(@TempDir Path tmp) throws Exception {
        JorfArticles.writeZip(tmp.resolve(JorfArticles.ARTICLES_FILE), new TreeMap<>());
        assertThatThrownBy(() -> parser.convert(tmp, FrPepJorfParser.Settings.of(Map.of()), TODAY,
                new FtmDatasetBuilder(FrPepJorfParser.ID_PREFIX))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void extractsKeptArticlesFromTarGz(@TempDir Path tmp) throws Exception {
        SortedMap<String, String> real = JorfArticles.readZip(RAW.resolve(JorfArticles.ARTICLES_FILE));
        String kept = real.get(real.firstKey());
        String other = kept.replaceAll("<TITRE_TXT([^>]*)>[^<]*</TITRE_TXT>",
                "<TITRE_TXT$1>Arrêté du 1er octobre 2026 relatif aux tarifs postaux</TITRE_TXT>");
        Path archive = tmp.resolve("JORF_20261001-000000.tar.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) {
            tarEntry(out, "20261001/jorf/global/texte/version/JORF/TEXT/JORFTEXT000000000001.xml", "<TEXTE/>");
            tarEntry(out, "20261001/jorf/global/article/JORF/ARTI/" + real.firstKey() + ".xml", kept);
            tarEntry(out, "20261001/jorf/global/article/JORF/ARTI/JORFARTI000000000002.xml", other);
            out.write(new byte[1024]);
        }
        assertThat(JorfDownloader.extractArticles(archive)).containsOnlyKeys(real.firstKey());
    }

    @Test
    void selectsGlobalArchiveThenDailies() {
        List<JorfDownloader.DilaFile> files = JorfDownloader.parseIndex("<a href=\"Freemium_jorf_global_20250713-140000"
                + ".tar.gz\">x</a> <a href=\"JORF_20250712-002000.tar.gz\">x</a> <a href=\"JORF_20250714-002000.tar.gz\">"
                + "x</a> <a href=\"JORF_20261030-002939.tar.gz\">x</a> <a href=\"DILA_JORF_Presentation.pdf\">x</a>");
        assertThat(JorfDownloader.select(files, LocalDate.of(1990, 1, 1))).extracting(JorfDownloader.DilaFile::name)
                .containsExactly("Freemium_jorf_global_20250713-140000.tar.gz", "JORF_20250714-002000.tar.gz",
                        "JORF_20261030-002939.tar.gz");
        // Période récente seulement : l'archive complète n'est pas nécessaire
        assertThat(JorfDownloader.select(files, LocalDate.of(2026, 1, 1))).extracting(JorfDownloader.DilaFile::name)
                .containsExactly("JORF_20261030-002939.tar.gz");
    }

    private static Article article(String title, String... lines) {
        return new Article("JORFARTI000000000001", "JORFTEXT000000000001", title, "DECRET", "2000-01-01",
                "2000-01-01", "1", List.of(lines));
    }

    /** Une entrée tar ustar minimale, pour tester le lecteur sans fichier de la DILA. */
    private static void tarEntry(OutputStream out, String name, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[512];
        System.arraycopy(name.getBytes(StandardCharsets.UTF_8), 0, header, 0, name.length());
        byte[] size = String.format("%011o", data.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        header[156] = '0';
        System.arraycopy("ustar  ".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 7);
        out.write(header);
        out.write(data);
        ByteArrayOutputStream padding = new ByteArrayOutputStream();
        padding.write(new byte[(512 - data.length % 512) % 512]);
        out.write(padding.toByteArray());
    }
}
