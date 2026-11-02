package com.swiftcheck.aml.media;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Institutions citées dans un texte, pour le score de contexte d'une image trouvée sur internet : la fonction de la
 * personne apparaît-elle autour de l'image ? Ne sert pas à rapprocher des personnes entre datasets : chaque photo
 * appartient au dataset qui l'a publiée.
 */
public final class Institutions {

    /** Institution → motif sur un texte (sans accents, minuscules). */
    static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        put("assemblee-nationale", "assemblee nationale|\\bdepute(e)?\\b(?!.*europeen)|national assembly");
        put("senat-fr", "\\bsenat|senateur|senatrice");
        put("parlement-europeen", "parlement europeen|european parliament|depute(e)? europeen");
        put("commission-europeenne", "commission europeenne|european commission|commissaire europeen");
        put("gouvernement-fr", "premier ministre|\\bministre\\b|secretaire d'etat|garde des sceaux|gouvernement");
        put("conseil-constitutionnel", "conseil constitutionnel");
        put("conseil-etat", "conseil\\w* d'etat|maitre(sse)? des requetes");
        put("cour-de-cassation", "cour de cassation");
        put("cour-des-comptes", "cour des comptes");
        put("banque-de-france", "banque de france");
        put("banque-centrale-luxembourg", "banque centrale du luxembourg|\\bbcl\\b");
        put("bce", "banque centrale europeenne|\\bbce\\b|european central bank");
        put("cjue", "cour de justice|tribunal de l'union|general court");
        put("chambre-des-deputes-lu", "chambre des deputes");
        put("cssf", "commission de surveillance du secteur financier|\\bcssf\\b");
        put("caa", "commissariat aux assurances");
        put("ambassade", "ambassad|representant(e)? permanent");
        put("armee", "chef d'etat-major|etat-major|\\barmee\\b|\\bcema\\b");
        put("grand-duche", "grand-duc|grande-duchesse");
        put("parti-politique", "\\bparti\\b|secretaire nation|president(e)? du parti");
    }

    private static void put(String key, String regex) {
        PATTERNS.put(key, Pattern.compile(regex));
    }

    private Institutions() {
    }

    /** Institutions d'après les fonctions écrites (« Conseiller maître à la Cour des comptes » → « cour-des-comptes »). */
    public static Set<String> of(Collection<String> functions) {
        Set<String> keys = new LinkedHashSet<>();
        for (String f : functions) {
            String t = fold(f);
            PATTERNS.forEach((key, pattern) -> {
                if (pattern.matcher(t).find()) {
                    keys.add(key);
                }
            });
        }
        return keys;
    }

    static String fold(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('’', '\'');
    }
}
