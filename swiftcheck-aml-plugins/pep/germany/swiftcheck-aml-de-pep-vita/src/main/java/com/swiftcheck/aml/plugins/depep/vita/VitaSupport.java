package com.swiftcheck.aml.plugins.depep.vita;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.swiftcheck.aml.plugins.depep.vita.VitaDocuments.Kind;
import com.swiftcheck.aml.plugins.depep.vita.VitaDocuments.Vita;

/**
 * Ce que les parsers Défense font des CV PDF : téléchargement, archivage du document brut dans {@code rawDir} (le
 * core le signe et le garde sous son SHA-256, sans écraser une version précédente), lecture, notes de preuve, et
 * lecture d'une liste de CV donnée en paramètre ({@code vitaUrls}) pour les personnes qu'aucune page HTML ne présente.
 */
public final class VitaSupport {

    private VitaSupport() {
    }

    /** Télécharge et lit un document ; le PDF reçu est déposé dans rawDir. Ne lève jamais d'exception. */
    public static Vita fetch(HttpClient client, String url, String userAgent, Path rawDir, List<String> warnings) {
        VitaDocuments.Download d = VitaDocuments.download(client, url, userAgent);
        if (d.bytes() == null) {
            String e = d.error() == null ? "échec" : d.error();
            warnings.add((e.startsWith("TOO_LARGE") ? "DOCUMENT_TOO_LARGE " : "SOURCE_BROKEN ") + url + " (" + e + ")");
            return VitaDocuments.read(url, null);
        }
        Vita v = VitaDocuments.read(url, d.bytes());
        if (rawDir != null && v.sha256() != null) {
            try {
                Files.createDirectories(rawDir);
                String ext = VitaDocuments.isPdf(d.bytes()) ? ".pdf" : VitaDocuments.isHtml(d.bytes()) ? ".html"
                        : VitaDocuments.isImage(d.bytes()) ? ".img" : ".bin";
                Files.write(rawDir.resolve("vita-" + v.sha256().substring(0, 16) + ext), d.bytes());
            } catch (IOException e) {
                warnings.add("rawDir : " + e.getMessage());
            }
        }
        if (v.kind() == Kind.IMAGE_ONLY) warnings.add("OCR_REQUIRED " + url);
        if (v.kind() == Kind.TOO_LARGE) warnings.add("DOCUMENT_TOO_LARGE " + url);
        return v;
    }

    /** Résultat de la lecture d'une liste de CV. */
    public record Outcome(List<Vita> people, List<String> warnings, int seniorOfficersSkipped, int withoutPerson) {
    }

    /**
     * Lit les CV donnés en paramètre et garde les personnes à publier : officiers généraux et amiraux (classe F.1
     * stricte), et chefs civils d'un Bundesamt (Präsident, Vizepräsident, Direktor). Les officiers supérieurs sont
     * comptés mais pas publiés, sauf si {@code includeSeniorOfficers}. Les URLs et noms déjà connus (pages HTML) sont
     * ignorés.
     */
    public static Outcome readList(HttpClient client, Collection<String> urls, Set<String> knownUrls, Set<String> knownNames,
                                   String userAgent, Path rawDir, boolean includeSeniorOfficers) {
        List<Vita> people = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int skipped = 0;
        int empty = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : urls) {
            String url = raw.trim();
            if (url.isEmpty() || knownUrls.contains(url) || !seen.add(url)) continue;
            Vita v = fetch(client, url, userAgent, rawDir, warnings);
            if (!v.hasPerson()) {
                empty++;
                continue;
            }
            if (knownNames.contains(key(v.name()))) continue;
            // Sans grade, une fonction de tête (« Präsidentin der Universität der Bundeswehr München ») suffit.
            boolean civilHead = isCivilHead(v.rank())
                    || v.rank() == null && v.function() != null && v.function().matches("(?i)(vize)?präsident(in)?\\b.*");
            if (!v.isGeneralOfficer() && !civilHead && !includeSeniorOfficers) {
                skipped++;
                continue;
            }
            knownNames.add(key(v.name()));
            people.add(v);
            try {
                Thread.sleep(300); // politesse HTTP
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new Outcome(people, warnings, skipped, empty);
    }

    /** Fonction civile de tête d'un Bundesamt ou d'une université de la Bundeswehr. */
    public static boolean isCivilHead(String rank) {
        return rank != null && rank.matches("(?i)(vize)?präsident(in)?|(erste[r]?\\s+)?direktor(in)?\\b.*");
    }

    /** Notes de preuve d'un CV, jointes par des retours à la ligne (format des notes des parsers Défense). */
    public static String evidence(Vita v) {
        return String.join("\n", v.evidenceNotes(LocalDate.now(ZoneOffset.UTC).toString()));
    }

    /** Liste d'URLs d'un paramètre (virgules, retours à la ligne ou tableau JSON). */
    public static List<String> parseList(String value) {
        List<String> list = new ArrayList<>();
        if (value == null || value.isBlank()) return list;
        for (String part : value.replaceAll("[\\[\\]\"]", "").split("[\\r\\n,]+")) {
            if (!part.isBlank()) list.add(part.trim());
        }
        return list;
    }

    /** Clé de comparaison d'un nom : sans titre, sans accents, en minuscules. */
    public static String key(String name) {
        String n = VitaDocuments.splitTitle(name)[1];
        n = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace("ß", "ss");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").replaceAll("\\s+", " ").trim();
    }

    /** Prénom(s) et nom : le dernier mot est le nom de famille, particules (« von ») comprises. */
    public static String[] firstLast(String name) {
        String[] words = name.trim().split("\\s+");
        if (words.length < 2) return new String[] {"", name};
        int last = words.length - 1;
        while (last > 1 && words[last - 1].matches("von|van|de|der|zu|du|di|da|le")) last--;
        return new String[] {String.join(" ", java.util.Arrays.copyOfRange(words, 0, last)),
                String.join(" ", java.util.Arrays.copyOfRange(words, last, words.length))};
    }
}
