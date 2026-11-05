package com.swiftcheck.aml.plugins.atpep.parliament;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.atpep.parliament.ParliamentApi.Body;
import com.swiftcheck.aml.plugins.atpep.parliament.ParliamentApi.Detail;
import com.swiftcheck.aml.plugins.atpep.parliament.ParliamentApi.Mandate;
import com.swiftcheck.aml.plugins.atpep.parliament.ParliamentApi.Member;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>at_pep_parliament</b> – députés du Nationalrat et membres du Bundesrat (catégorie AT-b ; FM-GwG § 2 Z 6 lit. b ;
 * liste notifiée à la Commission, JO C/2023/724).
 *
 * <p>Source : open data du Parlement autrichien, licence <b>CC BY 4.0</b> (source à citer : Parlamentsdirektion,
 * {@code parlament.gv.at}). Type {@code PLUGIN_MANAGED} : le plugin interroge l'interface JSON publique du Parlement,
 * avec un User-Agent générique et un rythme lent, et dépose chaque réponse dans {@code rawDir}.
 * <ol>
 *   <li>liste de toutes les personnes ayant siégé au Nationalrat, puis au Bundesrat (depuis 1918), avec leurs mandats ;</li>
 *   <li>pour chaque personne <i>gardée</i> (mandat en cours, ou terminé depuis moins de {@code monthsAfterEnd} mois), sa
 *       fiche JSON : date et lieu de naissance, date de décès.</li>
 * </ol>
 * Une personne décédée n'est pas publiée. Une personne dont la fiche n'a pas pu être lue est publiée sans naissance
 * (elle est signalée dans le message de l'exécution). Aucune coordonnée personnelle n'est lue.
 *
 * <p>Paramètres du parser (configuration) :
 * <pre>
 * {"listUrl": "https://www.parlament.gv.at/Filter/api/filter/data/409?1=1&amp;showAll=true&amp;export=true",
 *  "personUrl": "https://www.parlament.gv.at/person/{id}?json=true",
 *  "delayMs": "300", "monthsAfterEnd": "12"}
 * </pre>
 */
@Extension
public class AtPepParliamentParser implements Iparser {

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "at-pep-parliament";
    static final String TOPIC = "role.pep";
    static final long DEFAULT_DELAY_MS = 300;
    static final int DEFAULT_MONTHS_AFTER_END = 12;
    static final String DEFAULT_LIST_URL =
            "https://www.parlament.gv.at/Filter/api/filter/data/409?1=1&showAll=true&export=true";
    static final String DEFAULT_PERSON_URL = "https://www.parlament.gv.at/person/{id}?json=true";
    /** Contact générique : jamais l'adresse d'un utilisateur. */
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Autriche - open data du Parlement, CC BY 4.0)";

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(int nationalrat, int bundesrat, int current, int former, int tooOld, int deceased,
            List<String> withoutDetail, int monthsAfterEnd) {

        String message() {
            StringBuilder m = new StringBuilder(String.format(
                    "%d personnes publiées (Nationalrat %d, Bundesrat %d ; %d en fonction, %d sorties depuis moins de %d mois), "
                            + "%d sorties avant et %d décédées écartées. Source : Parlamentsdirektion, CC BY 4.0.",
                    current + former, nationalrat, bundesrat, current, former, monthsAfterEnd, tooOld, deceased));
            if (!withoutDetail.isEmpty()) {
                m.append(String.format(" %d sans fiche (ni naissance ni décès connus) : %s.", withoutDetail.size(),
                        String.join(", ", withoutDetail.stream().limit(10).toList())
                                + (withoutDetail.size() > 10 ? "…" : "")));
            }
            return m.toString();
        }
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String listUrl = text(p, "listUrl", DEFAULT_LIST_URL);
        String personUrl = text(p, "personUrl", DEFAULT_PERSON_URL);
        long delay = number(p, "delayMs", DEFAULT_DELAY_MS);
        int months = monthsAfterEnd(p);
        ParliamentApi.Fetcher fetcher = ParliamentApi.httpFetcher(USER_AGENT);

        ParliamentApi.downloadLists(fetcher, listUrl, ctx.rawDir());
        LocalDate today = LocalDate.now();
        for (String id : idsToDetail(ctx.rawDir(), today, months)) {
            ParliamentApi.downloadPerson(fetcher, personUrl, id, ctx.rawDir(), delay);
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        Report report = convert(ctx.rawDir(), today, months, dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(report.message());
    }

    /** Test du parser : chaque personne gardée, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        convert(ctx.rawDir(), LocalDate.now(), monthsAfterEnd(p), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity e : dataset.entities()) {
            if ("Person".equals(e.getSchema())) {
                records.add(new SourceRecord(e.getId(), "Person", e.getValues("name").iterator().next(),
                        String.join(", ", e.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Les personnes dont il faut lire la fiche : mandat en cours ou terminé depuis moins de {@code months} mois. */
    static List<String> idsToDetail(Path rawDir, LocalDate today, int months) throws IOException {
        List<String> ids = new ArrayList<>();
        for (Member m : kept(rawDir, today, months).values()) {
            ids.add(m.id());
        }
        return ids;
    }

    /** Une personne peut figurer dans les deux organes : ses mandats sont réunis, sous un seul identifiant. */
    private static Map<String, Member> kept(Path rawDir, LocalDate today, int months) throws IOException {
        String oldestEnd = today.minusMonths(months).toString();       // dates ISO : comparables en texte
        Map<String, Member> merged = new LinkedHashMap<>();
        for (Body body : List.of(ParliamentApi.NATIONALRAT, ParliamentApi.BUNDESRAT)) {
            for (Member m : ParliamentApi.members(rawDir, body)) {
                List<Mandate> recent = m.mandates().stream()
                        .filter(x -> x.end() == null || x.end().compareTo(oldestEnd) >= 0).toList();
                if (recent.isEmpty()) {
                    continue;
                }
                merged.merge(m.id(), new Member(m.id(), m.listName(), recent), (a, b) -> {
                    List<Mandate> all = new ArrayList<>(a.mandates());
                    all.addAll(b.mandates());
                    return new Member(a.id(), a.listName(), all);
                });
            }
        }
        return merged;
    }

    /** Lit les fichiers de {@code rawDir} et écrit les entités. Séparé de {@link #parse} pour être testé à une date fixe. */
    Report convert(Path rawDir, LocalDate today, int months, FtmDatasetBuilder dataset) throws IOException {
        int nationalratCount = ParliamentApi.members(rawDir, ParliamentApi.NATIONALRAT).size();
        int bundesratCount = ParliamentApi.members(rawDir, ParliamentApi.BUNDESRAT).size();
        Map<String, Member> kept = kept(rawDir, today, months);
        java.util.Set<String> everyone = new java.util.HashSet<>();
        ParliamentApi.members(rawDir, ParliamentApi.NATIONALRAT).forEach(m -> everyone.add(m.id()));
        ParliamentApi.members(rawDir, ParliamentApi.BUNDESRAT).forEach(m -> everyone.add(m.id()));
        int total = everyone.size();
        int current = 0;
        int former = 0;
        int deceased = 0;
        List<String> withoutDetail = new ArrayList<>();
        for (Member member : kept.values()) {
            Detail detail = ParliamentApi.detail(rawDir, member.id());
            if (detail == null) {
                withoutDetail.add(member.listName());
            } else if (detail.deathDate() != null) {
                deceased++;
                continue;
            }
            boolean active = member.mandates().stream().anyMatch(x -> x.end() == null);
            if (active) {
                current++;
            } else {
                former++;
            }
            write(member, detail, dataset);
        }
        if (kept.isEmpty()) {
            throw new IllegalStateException("Aucun parlementaire en fonction ou récent dans les listes : "
                    + "réponse vide ou format changé.");
        }
        return new Report(nationalratCount, bundesratCount, current, former, Math.max(0, total - kept.size()),
                deceased, withoutDetail, months);
    }

    private static void write(Member member, Detail detail, FtmDatasetBuilder dataset) {
        String pageUrl = "https://www.parlament.gv.at/person/" + member.id();
        String name = detail != null && detail.name() != null ? detail.name() : fallbackName(member.listName());
        String[] parts = splitName(member.listName(), name);

        // ID = identifiant officiel du Parlement (pad_intern), stable depuis 1918
        FtmEntity person = dataset.newEntity("Person", member.id());
        person.addValue("name", name);
        person.addValue("firstName", parts[0]);
        person.addValue("lastName", parts[1]);
        if (detail != null) {
            person.addValue("birthDate", detail.birthDate());
            person.addValue("birthPlace", detail.birthPlace());
        }
        person.addValue("country", "at");
        person.addValue("nationality", "at");
        person.addValue("sourceUrl", pageUrl);

        boolean nationalrat = member.mandates().stream().anyMatch(x -> "Nationalrat".equals(x.body()));
        boolean bundesrat = member.mandates().stream().anyMatch(x -> "Bundesrat".equals(x.body()));
        person.addValue("position", nationalrat ? "Abgeordnete(r) zum Nationalrat" : "Mitglied des Bundesrates");
        dataset.addEntity(person);

        FtmEntity nr = null;
        FtmEntity br = null;
        if (nationalrat) {
            nr = position(dataset, "nationalrat", "Abgeordnete(r) zum Nationalrat (Autriche)", "Nationalrat");
        }
        if (bundesrat) {
            br = position(dataset, "bundesrat", "Mitglied des Bundesrates (Autriche)", "Bundesrat");
        }
        for (Mandate m : member.mandates()) {
            FtmEntity post = "Nationalrat".equals(m.body()) ? nr : br;
            FtmEntity occupancy = dataset.newEntity("Occupancy", member.id(), m.body(), m.start());
            occupancy.addValue("holder", person.getId());
            occupancy.addValue("post", post.getId());
            occupancy.addValue("startDate", m.start());
            occupancy.addValue("endDate", m.end());
            occupancy.addValue("status", m.end() == null ? "current" : "ended");
            occupancy.addValue("politicalGroup", m.group());
            occupancy.addValue("constituency", m.region());
            occupancy.addValue("sourceUrl", pageUrl);
            dataset.addEntity(occupancy);
        }
    }

    private static FtmEntity position(FtmDatasetBuilder dataset, String key, String label, String organization) {
        FtmEntity position = dataset.newEntity("Position", key);
        position.addValue("name", label);
        position.addValue("country", "at");
        position.addValue("organization", organization);
        position.addValue("topics", "gov.legislative");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);
        return position;
    }

    /** « Steiner-Wieser Marlies (bis 8.5.2025: Marlies Doppler) » et « Aldali Lisa, Dr. » → « Nom Prénom » seul. */
    private static String baseName(String listName) {
        return listName.replaceAll("\\s*\\(.*$", "").split(",")[0].trim();
    }

    /** « Aldali Lisa, Dr. » → « Lisa Aldali » quand la fiche n'a pas été lue. */
    static String fallbackName(String listName) {
        String base = baseName(listName);
        int space = base.lastIndexOf(' ');
        return space < 0 ? base : base.substring(space + 1) + " " + base.substring(0, space);
    }

    /**
     * {prénom, nom} : la liste donne « Nom Prénom, titres » et la fiche « Prénom Nom ». Le nom est le plus long début de
     * la liste qui termine le nom d'usage (« Van der Bellen Alexander » + « Alexander Van der Bellen »).
     */
    static String[] splitName(String listName, String displayName) {
        String[] tokens = baseName(listName).split("\\s+");
        for (int k = tokens.length - 1; k >= 1; k--) {
            String last = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, k));
            String first = String.join(" ", java.util.Arrays.copyOfRange(tokens, k, tokens.length));
            if (displayName.equals(first + " " + last)) {
                return new String[] {first, last};
            }
        }
        return new String[] {tokens.length > 1 ? tokens[tokens.length - 1] : null, tokens[0]};
    }

    static int monthsAfterEnd(Map<String, String> p) {
        return (int) number(p, "monthsAfterEnd", DEFAULT_MONTHS_AFTER_END);
    }

    private static long number(Map<String, String> p, String key, long defaultValue) {
        String value = p.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Paramètre « " + key + " » invalide : « " + value + " » (nombre attendu).");
        }
    }

    private static String text(Map<String, String> p, String key, String defaultValue) {
        String value = p.get(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
