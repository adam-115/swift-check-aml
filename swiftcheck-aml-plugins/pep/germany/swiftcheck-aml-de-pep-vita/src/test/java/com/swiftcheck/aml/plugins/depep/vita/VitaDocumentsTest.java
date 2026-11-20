package com.swiftcheck.aml.plugins.depep.vita;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugins.depep.vita.VitaDocuments.Kind;
import com.swiftcheck.aml.plugins.depep.vita.VitaDocuments.Vita;

/** Mises en page réelles des CV de bundeswehr.de (corpus de la Story 3, 19/11/2026). */
class VitaDocumentsTest {

    private static Vita read(String url, String... pages) {
        return VitaDocuments.extract(url, List.of(pages), "sha", 1000, new ArrayList<>());
    }

    @Test
    void rankAndNameOnOneLineThenFunction() {
        // Le nom du fichier ne contient pas le nom : seul le contenu identifie la personne (UAT « Alfons Mais »).
        Vita v = read("https://www.bundeswehr.de/resource/blob/181930/x/04-download-kdoh-insph-vita-data.pdf", """
                CURRICULUM VITAE
                Generalleutnant Dr. Christian Freuding
                Inspekteur des Heeres
                Name Dr. Christian Freuding
                Geburtsdatum 20. August 1971
                Militärischer Werdegang
                """);
        assertEquals(Kind.CV, v.kind());
        assertEquals("Generalleutnant", v.rank());
        assertEquals("Dr.", v.title());
        assertEquals("Christian Freuding", v.name());
        assertEquals("Inspekteur des Heeres", v.function());
        assertEquals("20. August 1971", v.birthDate());
        assertEquals(1, v.page());
        assertEquals("high", v.confidence());
        assertTrue(v.isGeneralOfficer());
    }

    @Test
    void functionAboveRankAndNameBelow() {
        Vita v = read("vita-gl-katz.pdf", """
                Kommandierender General
                Luftwaffentruppenkommando
                Generalleutnant
                Günter Katz
                Geboren am 29. August 1962
                in Wiesbaden
                """);
        assertEquals("Generalleutnant", v.rank());
        assertEquals("Günter Katz", v.name());
        assertEquals("Kommandierender General Luftwaffentruppenkommando", v.function());
        assertEquals("Wiesbaden", v.birthPlace());
    }

    @Test
    void nameAboveRank() {
        Vita v = read("vita-amtschef-bg-havenith.pdf", """
                Luftfahrtamt
                der Bundeswehr
                VITA
                Armin Havenith
                Brigadegeneral
                """);
        assertEquals("Brigadegeneral", v.rank());
        assertEquals("Armin Havenith", v.name());
    }

    @Test
    void rankSentenceWithFunction() {
        Vita v = read("generalmajor-hogrebe.pdf", """
                LUFTWAFFE
                Geboren am 28. Juni 1965
                Generalmajor Michael Hogrebe ist
                seit Januar 2025 Stellvertreter des
                Kommandeurs und Chef des Stabes
                im Air Component Command.
                1984 Eintritt in die Bundeswehr
                """);
        assertEquals("Generalmajor", v.rank());
        assertEquals("Michael Hogrebe", v.name());
        assertEquals("Stellvertreter des Kommandeurs und Chef des Stabes im Air Component Command", v.function());
    }

    @Test
    void letterSpacedHeaderMatchedAgainstNameField() {
        Vita v = read("cv-ltgen-poschwatta.pdf", """
                CURRICULUM VITAE
                G e n e r al l e ut n a n t T h o r s t e n P o s c h w at t a
                K o m m an d e ur A I R C O M P O N E N T c O M M A N D
                Name Thorsten Poschwatta
                Geburtsdatum 18. August 1961
                """);
        assertEquals("Generalleutnant", v.rank());
        assertEquals("Thorsten Poschwatta", v.name());
    }

    @Test
    void titlesAndDegreesNeverInName() {
        assertArrayEquals(new String[] {"Assessor jur.", "Stefan Weber"}, VitaDocuments.splitTitle("Assessor jur. Stefan Weber"));
        assertArrayEquals(new String[] {"Dipl. Sportwissenschaftler (Univ.)", "Helge Gerken"},
                VitaDocuments.splitTitle("Dipl. Sportwissenschaftler (Univ.) Helge Gerken"));
        assertArrayEquals(new String[] {"Univ.-Prof. Dipl.-Ing. Dr. mont. Dr.-Ing. habil.", "Eva-Maria Kern"},
                VitaDocuments.splitTitle("Univ.-Prof. Dipl.-Ing. Dr. mont. Dr.-Ing. habil. Eva-Maria Kern, MBA"));
        assertArrayEquals(new String[] {null, "Sabine Reppin"}, VitaDocuments.splitTitle("Sabine Reppin, Ph.D."));
        assertArrayEquals(new String[] {"Prof. Dr.", "Roland Börger"}, VitaDocuments.splitTitle("Prof. Dr. Roland Börger"));
    }

    @Test
    void civilServants() {
        Vita uhle = read("lebenslauf-thomas-uhle.pdf", """
                CURRICULUM VITAE
                Vizepräsident und Ständiger Vertreter des Präsidenten des
                Bundesamtes für das Personalmanagement der Bundeswehr
                Erster Direktor beim BAPersBw Thomas Uhle
                geboren am 30.12.1966 in Bonn
                """);
        assertEquals("Thomas Uhle", uhle.name());
        assertEquals("Erster Direktor beim BAPersBw", uhle.rank());
        assertTrue(VitaSupport.isCivilHead(uhle.rank()));
        assertFalse(uhle.isGeneralOfficer());

        Vita beckmann = read("vita-praesident.pdf", """
                Curriculum Vitae
                Präsident HSU/UniBw H, Prof. Dr. Klaus Beckmann
                Geburtsdatum: 11. November 1965
                """);
        assertEquals("Klaus Beckmann", beckmann.name());
        assertEquals("Prof. Dr.", beckmann.title());
        assertEquals("Präsident HSU/UniBw H", beckmann.function());
    }

    @Test
    void imageOnlyDocumentNeedsOcr() {
        Vita v = read("vita-praesidentin-baainbw.pdf", """
                WWW.BUNDESWEHR.DE
                PRÄSIDENTIN
                BUNDESAMT FÜR AUSRÜSTUNG,
                56073 Koblenz
                Tel. +49 (0) 261 400 - 12100
                CURRICULUM VITAE
                """);
        assertEquals(Kind.IMAGE_ONLY, v.kind());
        assertFalse(v.hasPerson());
        assertTrue(v.warnings().stream().anyMatch(w -> w.contains("OCR")));
    }

    @Test
    void gzipBodyIsDecompressedAndImagesAreFlagged() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(png);
        }
        byte[] plain = VitaDocuments.gunzipIfNeeded(out.toByteArray());
        assertArrayEquals(png, plain);
        Vita v = VitaDocuments.read("organigramm.png", plain);
        assertEquals(Kind.IMAGE_ONLY, v.kind());
        assertEquals(VitaDocuments.sha256(png), v.sha256());
        assertNull(v.name());
    }

    @Test
    void nameKeysIgnoreTitlesAndAccents() {
        assertEquals(VitaSupport.key("Dr. Heico Hübner"), VitaSupport.key("Heico Huebner".replace("ue", "ü")));
        assertArrayEquals(new String[] {"Gero", "von Fritschen"}, VitaSupport.firstLast("Gero von Fritschen"));
    }

    /** Pages « Vita » HTML de la Führungsakademie (bundeswehr.de, juillet et mai 2026). */
    @Test
    void htmlVitaPagesAreRead() throws Exception {
        byte[] rackwitz = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/test/resources/vita-html/rackwitz.html"));
        Vita v = VitaDocuments.read("https://www.bundeswehr.de/de/organisation/fuehrungsakademie-der-bundeswehr/vita-kommandeur-fueakbw-6108920", rackwitz);
        assertEquals(Kind.CV, v.kind(), String.valueOf(v));
        assertEquals("Sascha Helge Rackwitz", v.name(), String.valueOf(v));
        assertEquals("Konteradmiral", v.rank());
        assertTrue(v.isGeneralOfficer());
        assertTrue(v.function() != null && v.function().contains("Führungsakademie"), String.valueOf(v));

        byte[] lange = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/test/resources/vita-html/lange.html"));
        Vita l = VitaDocuments.read("https://www.bundeswehr.de/de/organisation/fuehrungsakademie-der-bundeswehr/vita-direktor-strategie-und-fakultaeten-6098612", lange);
        assertEquals("Sven Lange", l.name(), String.valueOf(l));
        assertEquals("Brigadegeneral", l.rank());
        assertEquals("Dr.", l.title());
    }
}
