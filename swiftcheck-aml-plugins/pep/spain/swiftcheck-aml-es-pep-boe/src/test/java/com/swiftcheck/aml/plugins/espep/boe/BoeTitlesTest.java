package com.swiftcheck.aml.plugins.espep.boe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.swiftcheck.aml.plugins.espep.boe.BoeTitles.Event;
import com.swiftcheck.aml.plugins.espep.boe.BoeTitles.Kind;

/** Titres réels du BOE (section II.A, octobre 2025 et novembre 2023). */
class BoeTitlesTest {

    private static final LocalDate PUBLISHED = LocalDate.of(2025, 10, 22);

    private static Event only(String title) {
        List<Event> events = BoeTitles.read("BOE-A-2025-1", title, "MINISTERIO", PUBLISHED);
        assertEquals(1, events.size(), title);
        return events.get(0);
    }

    @Test
    void readsACessation() {
        Event e = only("Real Decreto 957/2025, de 21 de octubre, por el que se dispone el cese de doña Clara Mapelli "
                + "Marchena como Secretaria de Estado de Función Pública.");
        assertEquals(Kind.END, e.kind());
        assertEquals("Clara Mapelli Marchena", e.name());
        assertEquals("Secretaria de Estado de Función Pública", e.cargo());
        assertEquals(LocalDate.of(2025, 10, 21), e.date());
    }

    @Test
    void readsTheDeclaredCessation() {
        Event e = only("Real Decreto 838/2023, de 22 de noviembre, por el que se declara el cese de don Óscar López "
                + "Águeda como Director del Gabinete de la Presidencia del Gobierno.");
        assertEquals(Kind.END, e.kind());
        assertEquals("Óscar López Águeda", e.name());
        assertEquals("Director del Gabinete de la Presidencia del Gobierno", e.cargo());
    }

    @Test
    void readsAnAppointment() {
        Event e = only("Real Decreto 959/2025, de 21 de octubre, por el que se nombra Secretaria de Estado de Función "
                + "Pública a doña Consuelo Sánchez Naranjo.");
        assertEquals(Kind.APPOINT, e.kind());
        assertEquals("Consuelo Sánchez Naranjo", e.name());
        assertEquals("Secretaria de Estado de Función Pública", e.cargo());
    }

    @Test
    void readsADesignationOfAnAmbassador() {
        Event e = only("Real Decreto 946/2025, de 21 de octubre, por el que se designa Embajadora de España en la "
                + "República de Burundi a doña Paloma Serra Robles.");
        assertEquals("Embajadora de España en la República de Burundi", e.cargo());
        assertEquals("Paloma Serra Robles", e.name());
    }

    @Test
    void skipsTheRankBeforeTheName() {
        Event e = only("Real Decreto 861/2025, de 29 de septiembre, por el que se nombra Vocal Togado del Tribunal "
                + "Militar Central al General Auditor don Fernando Rosa Bobo.");
        assertEquals("Vocal Togado del Tribunal Militar Central", e.cargo());
        assertEquals("Fernando Rosa Bobo", e.name());

        Event decano = only("Real Decreto 837/2025, de 22 de septiembre, por el que se nombra Decano de los Juzgados "
                + "de Benidorm (Alicante) al Magistrado don Abel Fernández Domínguez.");
        assertEquals("Decano de los Juzgados de Benidorm (Alicante)", decano.cargo());
    }

    @Test
    void keepsAnAtInsideTheFunction() {
        Event e = only("Real Decreto 975/2025, de 23 de octubre, por el que se nombra Fiscal adscrita al Fiscal de Sala "
                + "de la Fiscalía de Sala Coordinador de Delitos Económicos a doña María Cacho García.");
        assertEquals("Fiscal adscrita al Fiscal de Sala de la Fiscalía de Sala Coordinador de Delitos Económicos",
                e.cargo());
        assertEquals("María Cacho García", e.name());
    }

    @Test
    void readsAPromotionAndDropsTheCorps() {
        Event e = only("Real Decreto 872/2025, de 30 de septiembre, por el que se promueve al empleo de Teniente "
                + "General del Cuerpo General del Ejército de Tierra al General de División don Raimundo Rodríguez Roca.");
        assertEquals(Kind.APPOINT, e.kind());
        assertEquals("Teniente General", e.cargo());
        assertEquals("Raimundo Rodríguez Roca", e.name());
    }

    @Test
    void ignoresTitlesWithoutAName() {
        assertTrue(BoeTitles.read("x", "Real Decreto 835/2023, de 20 de noviembre, por el que se nombran Ministros "
                + "del Gobierno.", "PRESIDENCIA", PUBLISHED).isEmpty());
        assertTrue(BoeTitles.read("x", "Resolución de 22 de octubre de 2026, de la Subsecretaría, por la que se "
                + "resuelve la convocatoria de libre designación.", "MINISTERIO", PUBLISHED).isEmpty());
    }

    @Test
    void usesThePublicationYearWhenTheTitleHasNone() {
        assertEquals(LocalDate.of(2026, 10, 19), BoeTitles.documentDate("Orden EFD/1153/2026, de 19 de octubre, por la que",
                LocalDate.of(2026, 11, 2)));
        assertEquals(LocalDate.of(2025, 12, 30), BoeTitles.documentDate("Orden X/1/2026, de 30 de diciembre, por la que",
                LocalDate.of(2026, 1, 2)));
    }

    @Test
    void keysIgnoreGenderAndAccents() {
        assertEquals(BoeTitles.key("Directora General de Planificación"), BoeTitles.key("Director General de Planificacion"));
        assertEquals(BoeTitles.key("Ministra de Defensa"), BoeTitles.key("Ministro de Defensa"));
        assertEquals(BoeTitles.key("Presidenta del Tribunal Supremo"), BoeTitles.key("Presidente del Tribunal Supremo"));
    }
}
