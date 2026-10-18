package com.swiftcheck.aml.plugins.lupep.legilux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.lupep.legilux.AppointmentReader.Appointment;
import com.swiftcheck.aml.plugins.lupep.legilux.AppointmentReader.Kind;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Conversion de 10 vrais actes de Legilux (src/test/resources/legilux) : Conseil d'État (nomination, démission),
 * Cour de cassation, renouvellements à la CSSF, à la BCL et au Commissariat aux assurances, Cour des comptes, et
 * trois actes « Magistrature » qui ne concernent pas une haute fonction.
 * Les fichiers sont déposés dans rawDir comme le ferait LegiluxDownloader ; la date du jour est fixée.
 */
class LuPepLegiluxParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    @TempDir
    Path rawDir;

    /** Dépose la liste des actes et l'archive des textes, à partir des fichiers réels de test. */
    private void prepareRawDir(String... extraActs) throws Exception {
        Path resources = Path.of(getClass().getResource("/legilux/actes.csv").toURI()).getParent();
        StringBuilder csv = new StringBuilder(Files.readString(resources.resolve("actes.csv")));
        try (OutputStream file = Files.newOutputStream(rawDir.resolve(LegiluxActs.TEXTS_FILE));
                ZipOutputStream zip = new ZipOutputStream(file);
                Stream<Path> xmls = Files.list(resources)) {
            for (Path xml : xmls.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(xml.getFileName().toString()));
                zip.write(Files.readAllBytes(xml));
                zip.closeEntry();
            }
            // Actes supplémentaires construits par un test : {uri, titre, texte}
            for (int i = 0; i < extraActs.length; i += 3) {
                csv.append('"').append(extraActs[i]).append("\",2026-10-01,\"").append(extraActs[i + 1]).append("\"\n");
                zip.putNextEntry(new ZipEntry(LegiluxActs.textEntryName(extraActs[i])));
                zip.write(akomaNtoso(extraActs[i + 2]).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Files.writeString(rawDir.resolve(LegiluxActs.ACT_LIST_FILE), csv);
    }

    private FtmDatasetBuilder convert(String... extraActs) throws Exception {
        prepareRawDir(extraActs);
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LuPepLegiluxParser.ID_PREFIX, LuPepLegiluxParser.TOPIC);
        new LuPepLegiluxParser().convert(rawDir, TODAY.minusYears(5), TODAY, dataset);
        return dataset;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream()
                .filter(e -> "Person".equals(e.getSchema()) && e.getValues("name").contains(name))
                .findFirst().orElseThrow(() -> new AssertionError("Personne absente : " + name));
    }

    /** Le mandat de la personne (un seul dans ces actes). */
    private static FtmEntity mandate(FtmDatasetBuilder dataset, FtmEntity person) {
        return dataset.entities().stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(person.getId()))
                .findFirst().orElseThrow();
    }

    private static FtmEntity post(FtmDatasetBuilder dataset, FtmEntity occupancy) {
        String id = occupancy.getValues("post").iterator().next();
        return dataset.entities().stream().filter(e -> e.getId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void councilOfStateNominationOpensAMandateWithUnknownStatus() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FtmEntity person = person(dataset, "Stephen DE RON");
        assertEquals(Set.of("Stephen"), person.getValues("firstName"));
        assertEquals(Set.of("DE RON"), person.getValues("lastName"));
        assertEquals(Set.of("role.pep"), person.getValues("topics"));

        FtmEntity mandate = mandate(dataset, person);
        assertEquals(Set.of("2026-09-15"), mandate.getValues("startDate"), "date d'effet, pas date de l'arrêté");
        assertEquals(Set.of("unknown"), mandate.getValues("status"), "pas de démission connue");
        assertEquals(Set.of("Membre du Conseil d'État"), post(dataset, mandate).getValues("name"));
    }

    @Test
    void resignationWithoutKnownNominationGivesAnEndedMandate() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FtmEntity mandate = mandate(dataset, person(dataset, "Deidre DU BOIS"));
        assertEquals(Set.of(), mandate.getValues("startDate"), "nommée avant 2017 : début inconnu");
        assertEquals(Set.of("2026-09-15"), mandate.getValues("endDate"));
        assertEquals(Set.of("ended"), mandate.getValues("status"));
    }

    @Test
    void highCourtNominationIsKept() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FtmEntity mandate = mandate(dataset, person(dataset, "Yola SCHMIT"));
        assertEquals(Set.of("2026-11-21"), mandate.getValues("startDate"));
        assertEquals(Set.of("Conseiller à la Cour de cassation"), post(dataset, mandate).getValues("name"));
        assertEquals(Set.of("gov.judicial"), post(dataset, mandate).getValues("topics"));
    }

    @Test
    void renewalForAPeriodGivesTheExpectedEnd() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FtmEntity cssf = mandate(dataset, person(dataset, "Jean-Pierre FABER"));
        assertEquals(Set.of(), cssf.getValues("startDate"), "renouvellement : début du premier mandat inconnu");
        assertEquals(Set.of("2031-08-31"), cssf.getValues("endDate"), "cinq ans à partir du 1er septembre 2026");
        assertEquals(Set.of("current"), cssf.getValues("status"));

        assertEquals(Set.of("2030-12-31"), mandate(dataset, person(dataset, "Gaston REINESCH")).getValues("endDate"));
        assertEquals(Set.of("2032-07-31"), mandate(dataset, person(dataset, "Valérie SCHEEPERS")).getValues("endDate"));
    }

    @Test
    void appointmentUntilADateAndNameWithoutCapitals() throws Exception {
        FtmDatasetBuilder dataset = convert();
        FtmEntity person = person(dataset, "Patrick Weymerskirch");
        assertEquals(Set.of("Weymerskirch"), person.getValues("lastName"), "pas de majuscules : dernier mot = nom");
        FtmEntity mandate = mandate(dataset, person);
        assertEquals(Set.of("2024-12-01"), mandate.getValues("startDate"));
        assertEquals(Set.of("2029-12-31"), mandate.getValues("endDate"));
        assertEquals(Set.of("current"), mandate.getValues("status"));
    }

    @Test
    void lowerCourtJudgesAreNotPep() throws Exception {
        FtmDatasetBuilder dataset = convert();
        List<String> names = dataset.entities().stream().filter(e -> "Person".equals(e.getSchema()))
                .flatMap(e -> e.getValues("name").stream()).sorted().toList();
        assertEquals(List.of("Deidre DU BOIS", "Gaston REINESCH", "Jean-Pierre FABER", "Patrick Weymerskirch",
                "Stephen DE RON", "Valérie SCHEEPERS", "Yola SCHMIT"), names,
                "Sonja STREICHER, Vanessa HAYO et les remplaçants du tribunal disciplinaire ne sont pas PEP");
        assertEquals(0, dataset.qualityReport().getTotalRejected());
    }

    @Test
    void resignationClosesTheMandateOpenedByAnEarlierNomination() throws Exception {
        // Textes construits sur le modèle des actes réels
        FtmDatasetBuilder dataset = convert(
                "http://data.legilux.public.lu/eli/etat/adm/pa/2026/10/01/t1/jo", "Conseil d’État - Nomination.",
                "Par arrêté grand-ducal du 1er octobre 2026, Madame Anne TEST, juriste, est nommée membre du"
                        + " Conseil d’État, avec effet au 1er octobre 2026.",
                "http://data.legilux.public.lu/eli/etat/adm/pa/2026/10/02/t2/jo", "Conseil d’État - Démission.",
                "Par arrêté grand-ducal du 2 octobre 2026, démission honorable de ses fonctions de membre du"
                        + " Conseil d’État est accordée à Madame Anne TEST, avec effet au 10 octobre 2026.");
        FtmEntity mandate = mandate(dataset, person(dataset, "Anne TEST"));
        assertEquals(Set.of("2026-10-01"), mandate.getValues("startDate"));
        assertEquals(Set.of("2026-10-10"), mandate.getValues("endDate"));
        assertEquals(Set.of("ended"), mandate.getValues("status"));
    }

    @Test
    void repeatedNominationOrResignationDoesNotCreateAnotherMandate() throws Exception {
        // Cas réels : une démission « rapportée » puis remplacée, une nomination publiée deux fois
        FtmDatasetBuilder dataset = convert(
                "http://data.legilux.public.lu/eli/etat/adm/pa/2026/10/01/t1/jo", "Conseil d’État - Nomination.",
                "Par arrêté grand-ducal du 1er octobre 2026, Madame Anne TEST, juriste, est nommée membre du"
                        + " Conseil d’État, avec effet au 1er octobre 2026.",
                "http://data.legilux.public.lu/eli/etat/adm/pa/2026/10/02/t2/jo", "Conseil d’État - Nomination.",
                "Par arrêté grand-ducal du 2 octobre 2026, Madame Anne TEST, juriste, est nommée membre du"
                        + " Conseil d’État, avec effet au 2 octobre 2026.",
                "http://data.legilux.public.lu/eli/etat/adm/pa/2026/10/03/t3/jo", "Conseil d’État - Démission.",
                "Par arrêté grand-ducal du 3 octobre 2026, démission honorable de ses fonctions de membre du"
                        + " Conseil d’État est accordée à Madame Anne TEST, avec effet au 5 octobre 2026.",
                "http://data.legilux.public.lu/eli/etat/adm/pa/2026/10/04/t4/jo", "Conseil d’État - Démission.",
                "Par arrêté grand-ducal du 4 octobre 2026, Madame Anne TEST est démissionnée de ses fonctions de"
                        + " conseiller d’État avec effet au 9 octobre 2026.");
        FtmEntity person = person(dataset, "Anne TEST");
        List<FtmEntity> mandates = dataset.entities().stream()
                .filter(e -> "Occupancy".equals(e.getSchema()) && e.getValues("holder").contains(person.getId()))
                .toList();
        assertEquals(1, mandates.size());
        assertEquals(Set.of("2026-10-01"), mandates.get(0).getValues("startDate"));
        assertEquals(Set.of("2026-10-09"), mandates.get(0).getValues("endDate"), "la dernière démission fait foi");
    }

    @Test
    void otherRealWordingsAreRead() {
        List<Appointment> continued = AppointmentReader.read("Par arrêté grand-ducal du 3 mars 2017, Monsieur"
                + " Georges WIVENES a été continué dans sa fonction de Président du Conseil d’État et Monsieur Albert"
                + " RODESCH a été continué dans sa fonction de Vice-Président du Conseil d’Etat, avec effet au 30 mars"
                + " 2017.");
        assertEquals(List.of("Georges WIVENES", "Albert RODESCH"),
                continued.stream().map(Appointment::fullName).toList());
        assertEquals(LocalDate.of(2017, 3, 30), continued.get(0).effective());

        List<Appointment> resignations = AppointmentReader.read("Par arrêté grand-ducal du 4 décembre 2020,"
                + " démission honorable de ses fonctions de vice-président de la Cour supérieur de justice, de"
                + " conseiller à la Cour de cassation et de membre de la Cour constitutionnelle est accordée, pour cause"
                + " de limite d’âge, à Madame Eliane EICHER. La mise à la retraite est prononcée avec effet au 1er"
                + " janvier 2021.");
        assertEquals(List.of("vice-président de la Cour supérieur de justice", "conseiller à la Cour de cassation",
                "membre de la Cour constitutionnelle"), resignations.stream().map(Appointment::function).toList());
        assertEquals("Eliane EICHER", resignations.get(0).fullName());
        assertEquals(LocalDate.of(2021, 1, 1), resignations.get(0).effective());

        Appointment renewed = AppointmentReader.read("Par arrêté grand-ducal du 22 novembre 2017, la nomination de"
                + " Monsieur Marc GENGLER, Président de la Cour des comptes, est renouvelée pour une durée de six ans à"
                + " partir du 1er janvier 2018.").get(0);
        assertEquals("Président de la Cour des comptes", renewed.function());
        assertEquals(LocalDate.of(2023, 12, 31), renewed.until());
    }

    @Test
    void unrecognisedWordingOnAHighFunctionIsListedWithoutStoppingTheConversion() throws Exception {
        prepareRawDir("http://data.legilux.public.lu/eli/etat/adm/pa/2026/10/03/t3/jo",
                "Conseil d’État - Nominations.",
                "Par arrêté grand-ducal du 3 octobre 2026, Madame Anne TEST et Monsieur Paul TEST sont nommés"
                        + " membres du Conseil d’État.");
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(LuPepLegiluxParser.ID_PREFIX, LuPepLegiluxParser.TOPIC);
        LuPepLegiluxParser.Report report = new LuPepLegiluxParser().convert(rawDir, TODAY.minusYears(5), TODAY,
                dataset);

        assertEquals(List.of("pa-2026-10-03-t3"), report.atypicalActs());
        assertTrue(report.message(0).contains("1 actes atypiques"), report.message(0));
        assertEquals(11, report.actCount());
    }

    @Test
    void noPersonFoundStopsTheConversion() throws Exception {
        Files.writeString(rawDir.resolve(LegiluxActs.ACT_LIST_FILE), "\"act\",\"date\",\"title\"\n");
        assertThrows(IllegalStateException.class, () -> new LuPepLegiluxParser().convert(rawDir,
                TODAY.minusYears(5), TODAY, new FtmDatasetBuilder("test")));
    }

    @Test
    void sourceRecordsHaveTheSameIdsAsTheConversion() throws Exception {
        FtmDatasetBuilder dataset = convert();
        List<SourceRecord> records = new LuPepLegiluxParser().readSourceRecords(
                new ParserContext("lu_pep_legilux", null, rawDir, null, Map.of()));
        assertEquals(dataset.entities().stream().filter(e -> "Person".equals(e.getSchema())).map(FtmEntity::getId)
                .sorted().toList(), records.stream().map(SourceRecord::entityId).sorted().toList());
    }

    @Test
    void sentenceWithNominationAndResignationGivesTwoEvents() {
        List<Appointment> events = AppointmentReader.read("Par arrêté grand-ducal du 24 juillet 2026, Madame Vanessa"
                + " HAYO, premier juge au Tribunal d’arrondissement de Luxembourg, est nommée juge de la jeunesse avec"
                + " effet au 16 septembre 2026 et démission de sa fonction de juge aux affaires familiales est"
                + " accordée à Madame Vanessa HAYO, premier juge au Tribunal d’arrondissement de Luxembourg, avec"
                + " effet au 16 septembre 2026.");
        assertEquals(List.of(Kind.NOMINATION, Kind.RESIGNATION), events.stream().map(Appointment::kind).toList());
        assertEquals("juge de la jeunesse", events.get(0).function());
        assertEquals("juge aux affaires familiales", events.get(1).function());
        assertEquals(LocalDate.of(2026, 9, 16), events.get(1).effective());
    }

    @Test
    void supportFunctionsAreNeverPep() {
        assertNull(LuPepLegiluxParser.pepFunction("greffier à la Cour de cassation"));
        assertNull(LuPepLegiluxParser.pepFunction(
                "premier juge au pool de complément des magistrats du siège près de la Cour supérieure de Justice"));
        assertEquals("cour-superieure",
                LuPepLegiluxParser.pepFunction("président de la Cour supérieure de justice").key());
    }

    @Test
    void textOfAParagraphWithAReferenceIsKeptWhole() throws Exception {
        String text = LegiluxActs.bodyText(new java.io.ByteArrayInputStream(
                "<akomaNtoso><act><body><p>L’<ref href=\"x\">arrêté du 17 juillet 2026</ref> est rapporté.</p></body></act></akomaNtoso>"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals("L’arrêté du 17 juillet 2026 est rapporté.", text);
    }

    /** Un acte minimal au format de Legilux (Akoma Ntoso), un paragraphe. */
    private static String akomaNtoso(String paragraph) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><akomaNtoso xmlns=\"http://docs.oasis-open.org/legaldocml/ns/akn/3.0/CSD13\">"
                + "<act><body><alinea><content><p>" + paragraph + "</p></content></alinea></body></act></akomaNtoso>";
    }
}
