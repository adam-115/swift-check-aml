package com.swiftcheck.aml.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class InstitutionsTest {

    @Test
    void institutionsComeFromWrittenFunctions() {
        assertEquals(Set.of("cour-des-comptes"), Institutions.of(List.of("Conseiller maître à la Cour des comptes")));
        assertEquals(Set.of("parlement-europeen"), Institutions.of(List.of("Député européen")));
        assertEquals(Set.of("assemblee-nationale"), Institutions.of(List.of("Députée de la 3e circonscription")));
        assertEquals(Set.of("conseil-etat"), Institutions.of(List.of("Conseillère d’État")));
        assertTrue(Institutions.of(List.of("Boulanger")).isEmpty());
    }
}
