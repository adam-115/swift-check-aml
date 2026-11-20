package com.swiftcheck.aml.plugins.depep.lobbyregister;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.depep.lobbyregister.GovFunctions.PepFunction;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterApiClient.IngestionResult;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.ClientRef;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.Contract;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.DetailedSearchResults;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.LobbyistIdentity;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.PersonEntry;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.SearchResult;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_lobbyregister</b> – Lobbyregister du Bundestag : anciens titulaires de fonctions publiques qui y sont
 * inscrits (PEP, GwG § 1 Abs. 12) et personnes notoirement proches qui en découlent (RCA, GwG § 1 Abs. 14).
 * <p>
 * <b>PEP</b> : seules les fonctions déclarées de rang PEP sont retenues ({@link GovFunctions}) : députés au
 * Bundestag, membres du gouvernement fédéral, Staatssekretäre et Botschafter. Un Referent ou un stagiaire d'un
 * ministère n'est pas une PEP. Statut ACTIVE_PEP, COOLING (12 mois après la fin) ou EXPIRED.
 * <p>
 * <b>RCA.1</b> (§ 1 Abs. 14 Nr. 2, « sonstige enge Geschäftsbeziehungen »), à partir d'une PEP en fonction ou en
 * refroidissement (la « PEP pivot ») :
 * <ol>
 *   <li>les autres représentants légaux (legalRepresentatives) de l'entité où siège la PEP ;</li>
 *   <li>les personnes chargées du lobbying (entrustedPersons) d'un cabinet d'au plus cinq personnes dirigé par une
 *       PEP ou dont l'unique client est une entité dirigée par une PEP ;</li>
 *   <li>les personnes chargées d'un mandat (contracts → contractors) pour un client inscrit au registre et dirigé
 *       par une PEP.</li>
 * </ol>
 * <b>RCA.2</b> : quand l'entité est désinscrite (LOBBY.2), les liens passent en RCA.2 avec la date de fin, sans
 * suppression. Une personne qui est elle-même PEP ne reçoit jamais le statut RCA (correctif UAT).
 * <p>
 * Chaque lien porte un motif d'audit fixe : alinéa du GwG, PEP pivot, entité et numéro Rxxxxxx, rôle en allemand,
 * URL officielle. Les personnes du registre qui ne sont ni PEP ni RCA ne sont pas publiées (paramètre
 * {@code publishAllPersons=true} pour les garder).
 */
@Extension
public class DePepLobbyregisterParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepLobbyregisterParser.class);

    static final String ID_PREFIX = "de-pep-lobbyregister";
    static final String TOPIC_PEP = "role.pep";
    static final String TOPIC_RCA = "role.rca";
    static final String GWG_14_NR2 = "GwG § 1 Abs. 14 Nr. 2 (sonstige enge Geschäftsbeziehungen zu einer politisch exponierten Person)";
    /** Taille maximale d'un « cabinet » pour la règle 2 (représentants et personnes chargées du lobbying). */
    static final int SMALL_FIRM_MAX_PERSONS = 5;

    public static final String DEFAULT_API_URL = "https://www.lobbyregister.bundestag.de/sucheDetailJson";

    private final LobbyregisterApiClient apiClient;

    public DePepLobbyregisterParser() {
        this.apiClient = new LobbyregisterApiClient();
    }

    public DePepLobbyregisterParser(LobbyregisterApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.getOrDefault("url", DEFAULT_API_URL).trim();
        String apiKey = p.getOrDefault("testApiKey", LobbyregisterApiClient.DEFAULT_TEST_API_KEY).trim();
        boolean publishAll = "true".equalsIgnoreCase(p.get("publishAllPersons"));
        // Base PEP SwiftCheck remise par le core (paramètre pepCorrelation=true du parser) : étape 1 de la Story 5.
        String indexFile = p.get("pepIndexFile");
        PepIndex index = indexFile != null && !indexFile.isBlank() && Files.exists(Path.of(indexFile))
                ? PepIndex.load(Path.of(indexFile)) : PepIndex.empty();

        Path rawFile = ctx.rawDir() != null ? ctx.rawDir().resolve("lobbyregister.json") : null;
        DetailedSearchResults data;
        String sha256;

        if (ctx.inputFile() != null && Files.exists(ctx.inputFile())) {
            log.info("Chargement offline du fichier Lobbyregister : {}", ctx.inputFile());
            sha256 = LobbyregisterApiClient.computeSha256Hex(ctx.inputFile());
            data = apiClient.parseRawFile(ctx.inputFile());
        } else if (rawFile != null && Files.exists(rawFile)) {
            log.info("Chargement depuis cache raw : {}", rawFile);
            sha256 = LobbyregisterApiClient.computeSha256Hex(rawFile);
            data = apiClient.parseRawFile(rawFile);
        } else {
            IngestionResult res = apiClient.fetchAndSave(url, apiKey, ctx.rawDir());
            data = res.searchResults();
            sha256 = res.sha256Hex();
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        AuditReport audit = convert(data, dataset, sha256, publishAll, LocalDate.now(ZoneOffset.UTC), index);
        if (ctx.outputFile() != null) {
            dataset.writeNdjsonFile(ctx.outputFile());
        }
        return dataset.toConversionResult().withMessage(audit.summaryMessage());
    }

    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        Path target = ctx.inputFile() != null && Files.exists(ctx.inputFile())
                ? ctx.inputFile()
                : (ctx.rawDir() != null ? ctx.rawDir().resolve("lobbyregister.json") : null);
        if (target == null || !Files.exists(target)) {
            return Collections.emptyList();
        }
        DetailedSearchResults data = apiClient.parseRawFile(target);
        List<SourceRecord> records = new ArrayList<>();
        if (data.results != null) {
            for (SearchResult r : data.results) {
                String regNo = r.registerNumber != null ? r.registerNumber : "UNKNOWN";
                records.add(new SourceRecord("de-lobby-" + regNo, "Organization", entityName(r), pageUrl(r)));
            }
        }
        return records;
    }

    /** Bilan d'exécution. */
    public record AuditReport(int totalEntities, int pepCount, int pepActiveOrCooling, int rca1Count, int rca2Count,
                              int declaredNotPep, int contractsRead, String sha256, int indexSize,
                              int matchedConfirmed, List<String> candidates) {

        AuditReport(int totalEntities, int pepCount, int pepActiveOrCooling, int rca1Count, int rca2Count,
                    int declaredNotPep, int contractsRead, String sha256) {
            this(totalEntities, pepCount, pepActiveOrCooling, rca1Count, rca2Count, declaredNotPep, contractsRead,
                    sha256, 0, 0, List.of());
        }

        public String summaryMessage() {
            String base = String.format(
                    "Lobbyregister Bund : %d entites ; %d PEP inscrites (%d en fonction ou en refroidissement) ; "
                            + "%d personnes RCA.1 et %d RCA.2 (GwG § 1 Abs. 14 Nr. 2) ; %d fonctions declarees sans "
                            + "rang PEP non retenues ; %d mandats lus. SHA-256: %s",
                    totalEntities, pepCount, pepActiveOrCooling, rca1Count, rca2Count, declaredNotPep, contractsRead,
                    sha256 != null ? sha256.substring(0, Math.min(12, sha256.length())) : "N/A");
            if (indexSize == 0) {
                return base + " Base PEP SwiftCheck non croisee (parametre pepCorrelation absent).";
            }
            return base + String.format(" Croisement avec la base PEP SwiftCheck (%d PEP) : %d personnes reconnues "
                    + "comme PEP ; %d homonymes non confirmes faute de contexte, non retenus comme PEP%s.", indexSize,
                    matchedConfirmed, candidates.size(),
                    candidates.isEmpty() ? "" : " (ex. " + String.join(" ; ", candidates.subList(0, Math.min(5, candidates.size()))) + ")");
        }
    }

    AuditReport convert(DetailedSearchResults data, FtmDatasetBuilder dataset, String sha256) {
        return convert(data, dataset, sha256, false, LocalDate.now(ZoneOffset.UTC), PepIndex.empty());
    }

    AuditReport convert(DetailedSearchResults data, FtmDatasetBuilder dataset, String sha256, boolean publishAll,
                        LocalDate today) {
        return convert(data, dataset, sha256, publishAll, today, PepIndex.empty());
    }

    // --- Moteur de corrélation ----------------------------------------------------------------------------------

    /** Une PEP pivot : la personne, sa fonction qualifiée, l'entité où elle siège. */
    record Pivot(PersonEntry person, PepFunction function, String regNo, String entityName) {
    }

    /** Un motif RCA (un par lien) : il est figé dans les notes de la fiche et dans l'entité Associate. */
    record RcaLink(Pivot pivot, int rule, String regNo, String entityName, String roleDe, String url, boolean ended,
                   String endDate, String detail) {

        String motif() {
            return (ended ? "RCA.2" : "RCA.1") + " — " + GWG_14_NR2 + " — Regel " + rule + " : " + detail
                    + " ; PEP : " + pivot.person().fullName() + " (" + pivot.function().label() + ", "
                    + pivot.function().status() + ")"
                    + " ; Eintrag : " + entityName + " [" + regNo + "]"
                    + " ; Rolle : " + roleDe
                    + (ended ? " ; beendet am " + endDate : "")
                    + " ; Quelle : " + url;
        }
    }

    AuditReport convert(DetailedSearchResults data, FtmDatasetBuilder dataset, String sha256, boolean publishAll,
                        LocalDate today, PepIndex index) {
        if (data == null || data.results == null) {
            return new AuditReport(0, 0, 0, 0, 0, 0, 0, sha256);
        }
        String extractedAt = today.toString();

        // 1. Toutes les PEP du registre, et les pivots (PEP en fonction ou en refroidissement) par entité.
        Map<String, PersonPep> peps = new LinkedHashMap<>();         // clé de personne -> PEP
        Map<String, List<Pivot>> pivotsByEntity = new LinkedHashMap<>();
        int[] declaredNotPep = {0};
        Map<String, String> candidates = new LinkedHashMap<>();       // homonymes non confirmés (bilan)
        for (SearchResult r : data.results) {
            String regNo = regNo(r);
            for (PersonEntry p : leaders(r)) {
                PersonPep pp = qualify(p, r, today, index, peps, candidates, declaredNotPep);
                if (pp != null && pp.function.propagates()) {
                    pivotsByEntity.computeIfAbsent(regNo, k -> new ArrayList<>()).add(new Pivot(p, pp.function, regNo, entityName(r)));
                }
            }
            for (PersonEntry p : entrusted(r)) {
                qualify(p, r, today, index, peps, candidates, declaredNotPep);
            }
        }
        for (String k : peps.keySet()) candidates.remove(k);
        int matchedConfirmed = (int) peps.values().stream().filter(pp -> pp.match != null).count();

        // 2. Liens RCA, entité par entité.
        Map<String, List<RcaLink>> rcaByPerson = new LinkedHashMap<>();  // clé personne+entité -> liens
        Map<String, PersonEntry> rcaPersons = new LinkedHashMap<>();
        Map<String, SearchResult> rcaEntity = new LinkedHashMap<>();
        int contractsRead = 0;
        for (SearchResult r : data.results) {
            String regNo = regNo(r);
            String name = entityName(r);
            String url = pageUrl(r);
            boolean ended = !isActive(r);
            String endDate = ended && r.accountDetails != null && r.accountDetails.inactiveLobbyistStartDate != null
                    ? r.accountDetails.inactiveLobbyistStartDate.substring(0, Math.min(10, r.accountDetails.inactiveLobbyistStartDate.length()))
                    : null;
            List<Pivot> pivots = pivotsByEntity.getOrDefault(regNo, List.of());

            // Règle 1 : co-représentants légaux d'une PEP.
            for (Pivot pv : pivots) {
                for (PersonEntry rep : legalRepresentatives(r)) {
                    if (same(rep, pv.person()) || isPep(peps, rep)) continue;
                    add(rcaByPerson, rcaPersons, rcaEntity, r, rep, new RcaLink(pv, 1, regNo, name,
                            role(rep, "Vertretungsberechtigte Person"), url, ended, endDate,
                            "vertretungsberechtigt neben der PEP im selben Registereintrag"));
                }
            }

            // Règle 2 : personnes chargées du lobbying d'un petit cabinet dirigé par une PEP ou au client unique PEP.
            List<ClientRef> clients = clientOrganizations(r);
            Set<String> persons = new LinkedHashSet<>();
            for (PersonEntry p : legalRepresentatives(r)) persons.add(personKey(p));
            for (PersonEntry p : entrusted(r)) persons.add(personKey(p));
            if (persons.size() <= SMALL_FIRM_MAX_PERSONS) {
                List<Pivot> smallPivots = new ArrayList<>(pivots);
                String why = "betraute Person eines Büros mit höchstens " + SMALL_FIRM_MAX_PERSONS + " Personen unter Leitung der PEP";
                if (smallPivots.isEmpty() && clients.size() == 1 && clients.get(0).referenceRegisterNumber != null) {
                    smallPivots.addAll(pivotsByEntity.getOrDefault(clients.get(0).referenceRegisterNumber.trim(), List.of()));
                    why = "betraute Person eines Büros mit höchstens " + SMALL_FIRM_MAX_PERSONS
                            + " Personen, einziger Auftraggeber " + clientName(clients.get(0)) + " [" + clients.get(0).referenceRegisterNumber.trim() + "] unter Leitung der PEP";
                }
                for (Pivot pv : smallPivots) {
                    for (PersonEntry emp : entrusted(r)) {
                        if (same(emp, pv.person()) || isPep(peps, emp) || isLegalRepresentative(r, emp)) continue;
                        add(rcaByPerson, rcaPersons, rcaEntity, r, emp, new RcaLink(pv, 2, regNo, name,
                                "Mit der Interessenvertretung betraute Person", url, ended, endDate, why));
                    }
                }
            }

            // Règle 3 : lobbyistes chargés d'un mandat pour un client inscrit et dirigé par une PEP.
            if (r.contracts != null && r.contracts.contracts != null) {
                for (Contract c : r.contracts.contracts) {
                    contractsRead++;
                    if (c.clients == null || c.contractors == null || c.contractors.entrustedPersons == null) continue;
                    for (ClientRef client : c.clients.clientOrganizations) {
                        if (client.referenceRegisterNumber == null) continue;
                        String clientReg = client.referenceRegisterNumber.trim();
                        if (clientReg.equals(regNo)) continue;
                        for (Pivot pv : pivotsByEntity.getOrDefault(clientReg, List.of())) {
                            for (PersonEntry lob : c.contractors.entrustedPersons) {
                                if (same(lob, pv.person()) || isPep(peps, lob)) continue;
                                add(rcaByPerson, rcaPersons, rcaEntity, r, lob, new RcaLink(pv, 3, regNo, name,
                                        "Mit der Interessenvertretung betraute Person (Auftrag)", url, ended, endDate,
                                        "handelt im Auftrag von " + clientName(client) + " [" + clientReg + "], geleitet von der PEP"));
                            }
                        }
                    }
                }
            }
        }

        // 3. Publication.
        Set<String> publishedEntities = new LinkedHashSet<>();
        int pepActive = 0;
        for (PersonPep pp : peps.values()) {
            if (pp.function.propagates()) pepActive++;
            writePep(dataset, pp, publishedEntities, extractedAt, sha256);
        }
        int rca1 = 0;
        int rca2 = 0;
        for (Map.Entry<String, List<RcaLink>> e : rcaByPerson.entrySet()) {
            List<RcaLink> links = e.getValue();
            boolean anyActive = links.stream().anyMatch(l -> !l.ended());
            if (anyActive) rca1++; else rca2++;
            writeRca(dataset, e.getKey(), rcaPersons.get(e.getKey()), rcaEntity.get(e.getKey()), links, anyActive,
                    peps, publishedEntities, extractedAt, sha256);
        }
        if (publishAll) {
            for (SearchResult r : data.results) {
                for (PersonEntry p : leaders(r)) {
                    String key = personKey(p) + "-" + regNo(r).toLowerCase();
                    if (peps.containsKey(personKey(p)) || rcaByPerson.containsKey(key) || p.lastName == null) continue;
                    FtmEntity person = dataset.newEntity("Person", "person-" + key);
                    person.addValue("name", plainName(p));
                    person.addValue("title", titles(p));
                    person.addValue("firstName", p.firstName);
                    person.addValue("lastName", p.lastName);
                    person.addValue("sourceUrl", pageUrl(r));
                    dataset.addEntity(person);
                    directorship(dataset, r, person, p, publishedEntities);
                }
            }
        }
        return new AuditReport(data.results.size(), peps.size(), pepActive, rca1, rca2, declaredNotPep[0], contractsRead,
                sha256, index.size(), matchedConfirmed, new ArrayList<>(candidates.values()));
    }

    /**
     * PEP de la personne dans cette entité, ou null : d'abord la fonction déclarée au registre, sinon le
     * rapprochement confirmé avec la base PEP SwiftCheck (étape 1). L'entité est ajoutée à la fiche PEP.
     */
    private static PersonPep qualify(PersonEntry p, SearchResult r, LocalDate today, PepIndex index,
                                     Map<String, PersonPep> peps, Map<String, String> candidates, int[] declaredNotPep) {
        String key = personKey(p);
        PepFunction declared = p.recentGovernmentFunctionPresent && p.recentGovernmentFunction != null
                ? GovFunctions.qualify(p.recentGovernmentFunction, today) : null;
        if (declared == null && p.recentGovernmentFunctionPresent && p.recentGovernmentFunction != null) {
            declaredNotPep[0]++;
        }
        PersonPep pp = peps.get(key);
        if (pp == null && declared != null) {
            pp = new PersonPep(p, declared, null);
        }
        if (pp == null && index.size() > 0) {
            PepIndex.Match m = index.match(p.firstName, p.lastName, context(p, r));
            if (m != null && m.confirmed()) {
                pp = new PersonPep(p, m.pep().function(today), m);
            } else if (m != null) {
                candidates.putIfAbsent(key, plainName(p) + " [" + regNo(r) + "] ~ " + m.pep().names().get(0)
                        + " (" + m.pep().dataset() + ")");
            }
        }
        if (pp == null) return null;
        peps.putIfAbsent(key, pp);
        if (!pp.entities.contains(r)) pp.entities.add(r);
        return pp;
    }

    /** Ce que le registre dit de la personne et de l'entité, pour le contrôle du contexte d'un rapprochement. */
    static List<String> context(PersonEntry p, SearchResult r) {
        List<String> c = new ArrayList<>();
        c.add(p.function);
        c.add(entityName(r));
        if (r.lobbyistIdentity != null) c.add(r.lobbyistIdentity.companyName);
        var f = p.recentGovernmentFunction;
        if (f != null) {
            if (f.type != null) c.add(f.type.de);
            if (f.houseOfRepresentatives != null && f.houseOfRepresentatives.function != null) c.add(f.houseOfRepresentatives.function.de);
            if (f.federalGovernment != null) {
                if (f.federalGovernment.function != null) c.add(f.federalGovernment.function.de);
                if (f.federalGovernment.department != null) c.add(f.federalGovernment.department.title);
            }
            if (f.federalAdministration != null) {
                c.add(f.federalAdministration.function);
                c.add(f.federalAdministration.supremeFederalAuthority);
            }
        }
        return c;
    }

    /** Une PEP du registre et toutes les entités où elle figure. */
    static final class PersonPep {
        final PersonEntry person;
        final PepFunction function;
        final List<SearchResult> entities = new ArrayList<>();
        /** Rapprochement avec la base PEP SwiftCheck, ou null si la fonction est déclarée au registre. */
        final PepIndex.Match match;

        PersonPep(PersonEntry person, PepFunction function, PepIndex.Match match) {
            this.person = person;
            this.function = function;
            this.match = match;
        }
    }

    private static void add(Map<String, List<RcaLink>> byPerson, Map<String, PersonEntry> persons,
                            Map<String, SearchResult> entityOf, SearchResult r, PersonEntry p, RcaLink link) {
        if (p.lastName == null || p.lastName.isBlank()) return;
        // Une même personne dans deux entités : deux fiches (le contexte distingue les homonymes).
        String key = personKey(p) + "-" + regNo(r).toLowerCase();
        List<RcaLink> links = byPerson.computeIfAbsent(key, k -> new ArrayList<>());
        boolean dup = links.stream().anyMatch(l -> l.rule() == link.rule() && same(l.pivot().person(), link.pivot().person())
                && l.regNo().equals(link.regNo()));
        if (!dup) links.add(link);
        persons.putIfAbsent(key, p);
        entityOf.putIfAbsent(key, r);
    }

    private void writePep(FtmDatasetBuilder dataset, PersonPep pp, Set<String> publishedEntities, String extractedAt,
                          String sha256) {
        PersonEntry p = pp.person;
        String slug = personKey(p);
        FtmEntity person = dataset.newEntity("Person", "person-" + slug);
        person.addValue("name", plainName(p));
        person.addValue("title", titles(p));
        person.addValue("firstName", p.firstName);
        person.addValue("lastName", p.lastName);
        person.addValue("country", "de");
        person.addValue("nationality", "de");
        person.addValue("topics", TOPIC_PEP);
        person.addValue("position", pp.function.label());
        person.addValue("notes", "pep_category: " + pp.function.gwgReference());
        person.addValue("notes", pp.function.statusNote());
        person.addValue("notes", "level: FEDERAL");
        if (pp.match != null) {
            // Étape 1 : PEP connue d'un autre jeu SwiftCheck, reliée ici à ses entrées Rxxxxxx.
            person.addValue("notes", pp.match.note());
            person.addValue("sourceUrl", pp.match.pep().sourceUrl());
        }
        for (SearchResult r : pp.entities) {
            person.addValue("sourceUrl", pageUrl(r));
            person.addValue("notes", "Lobbyregister: " + entityName(r) + " [" + regNo(r) + "] ; " + role(p, "eingetragene Person")
                    + " ; source: " + pageUrl(r) + " ; extracted_at: " + extractedAt
                    + (sha256 == null ? "" : " ; sha256 (Registerabzug): " + sha256));
        }
        dataset.addEntity(person);

        FtmEntity position = dataset.newEntity("Position", "gov-function-" + slug);
        position.addValue("name", pp.function.label());
        position.addValue("country", "de");
        position.addValue("topics", "gov.national");
        dataset.addEntity(position);

        FtmEntity occupancy = dataset.newEntity("Occupancy", "person-" + slug, "occupancy-ended");
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("status", pp.function.status() == GovFunctions.Status.ACTIVE_PEP ? "current" : "ended");
        if (pp.function.endDate() != null && !"?".equals(pp.function.endDate())) {
            occupancy.addValue("endDate", pp.function.endDate());
        }
        occupancy.addValue("description", pp.function.statusNote());
        occupancy.addValue("sourceUrl", pageUrl(pp.entities.get(0)));
        dataset.addEntity(occupancy);

        for (SearchResult r : pp.entities) {
            directorship(dataset, r, person, p, publishedEntities);
        }
    }

    private void writeRca(FtmDatasetBuilder dataset, String key, PersonEntry p, SearchResult r, List<RcaLink> links,
                          boolean anyActive, Map<String, PersonPep> peps, Set<String> publishedEntities,
                          String extractedAt, String sha256) {
        FtmEntity person = dataset.newEntity("Person", "person-" + key);
        person.addValue("name", plainName(p));
        person.addValue("title", titles(p));
        person.addValue("firstName", p.firstName);
        person.addValue("lastName", p.lastName);
        person.addValue("country", "de");
        person.addValue("topics", TOPIC_RCA);
        person.addValue("sourceUrl", pageUrl(r));
        person.addValue("notes", "rca_status: " + (anyActive ? "RCA.1" : "RCA.2"));
        for (RcaLink l : links) {
            person.addValue("notes", l.motif());
        }
        person.addValue("notes", "source: " + pageUrl(r) + " ; extracted_at: " + extractedAt
                + (sha256 == null ? "" : " ; sha256 (Registerabzug): " + sha256));
        person.addValue("summary", links.get(0).motif());
        dataset.addEntity(person);
        directorshipOrEmployment(dataset, r, person, p, links.get(0).roleDe(), publishedEntities);

        for (RcaLink l : links) {
            String pivotId = dataset.newEntity("Person", "person-" + personKey(l.pivot().person())).getId();
            FtmEntity associate = dataset.newEntity("Associate", personKey(l.pivot().person()), key, l.regNo().toLowerCase(),
                    "r" + l.rule());
            associate.addValue("person", pivotId);
            associate.addValue("associate", person.getId());
            associate.addValue("relationship", l.motif());
            associate.addValue("sourceUrl", l.url());
            if (l.ended() && l.endDate() != null) associate.addValue("endDate", l.endDate());
            dataset.addEntity(associate);
        }
    }

    private void directorship(FtmDatasetBuilder dataset, SearchResult r, FtmEntity person, PersonEntry p,
                              Set<String> publishedEntities) {
        directorshipOrEmployment(dataset, r, person, p, role(p, "Vertretungsberechtigte Person"), publishedEntities);
    }

    private void directorshipOrEmployment(FtmDatasetBuilder dataset, SearchResult r, FtmEntity person, PersonEntry p,
                                          String role, Set<String> publishedEntities) {
        FtmEntity org = company(dataset, r, publishedEntities);
        boolean leader = isLegalRepresentative(r, p) || isNaturalLobbyist(r, p);
        String suffix = leader ? "directorship" : "employment";
        FtmEntity rel = dataset.newEntity(leader ? "Directorship" : "Employment", org.getId(), person.getId(), suffix);
        if (leader) {
            rel.addValue("director", person.getId());
            rel.addValue("organization", org.getId());
        } else {
            rel.addValue("employee", person.getId());
            rel.addValue("employer", org.getId());
        }
        rel.addValue("role", role);
        rel.addValue("status", isActive(r) ? "current" : "ended");
        rel.addValue("sourceUrl", pageUrl(r));
        dataset.addEntity(rel);
    }

    private FtmEntity company(FtmDatasetBuilder dataset, SearchResult r, Set<String> published) {
        String regNo = regNo(r);
        FtmEntity org = dataset.newEntity("Company", "org-" + regNo.toLowerCase());
        if (published.add(regNo)) {
            org.addValue("name", entityName(r));
            org.addValue("registrationNumber", regNo);
            org.addValue("jurisdiction", "de");
            org.addValue("sourceUrl", pageUrl(r));
            org.addValue("status", isActive(r) ? "LOBBY.1 (aktiv)" : "LOBBY.2 (nicht mehr aktiv)");
            if (r.lobbyistIdentity != null && r.lobbyistIdentity.legalForm != null && r.lobbyistIdentity.legalForm.de != null) {
                org.addValue("legalForm", r.lobbyistIdentity.legalForm.de);
            }
            dataset.addEntity(org);
        }
        return org;
    }

    // --- Lecture du registre ----------------------------------------------------------------------------------

    /** Représentants légaux, plus le lobbyiste lui-même quand c'est une personne physique. */
    static List<PersonEntry> leaders(SearchResult r) {
        List<PersonEntry> list = new ArrayList<>(legalRepresentatives(r));
        PersonEntry natural = naturalLobbyist(r);
        if (natural != null) list.add(natural);
        return list;
    }

    static List<PersonEntry> legalRepresentatives(SearchResult r) {
        return r.lobbyistIdentity == null || r.lobbyistIdentity.legalRepresentatives == null
                ? List.of() : r.lobbyistIdentity.legalRepresentatives;
    }

    /** Personnes chargées du lobbying (entrustedPersons) et salariés nommés (ancien champ namedEmployees). */
    static List<PersonEntry> entrusted(SearchResult r) {
        List<PersonEntry> list = new ArrayList<>();
        if (r.lobbyistIdentity != null) {
            if (r.lobbyistIdentity.entrustedPersons != null) list.addAll(r.lobbyistIdentity.entrustedPersons);
            if (r.lobbyistIdentity.namedEmployees != null) list.addAll(r.lobbyistIdentity.namedEmployees);
        }
        return list;
    }

    /** Lobbyiste personne physique (identity = NATURAL), vu comme une personne du registre. */
    static PersonEntry naturalLobbyist(SearchResult r) {
        LobbyistIdentity li = r.lobbyistIdentity;
        if (li == null || !"NATURAL".equals(li.identity) || li.lastName == null) return null;
        PersonEntry p = new PersonEntry();
        p.academicDegreeBefore = li.academicDegreeBefore;
        p.firstName = li.firstName;
        p.lastName = li.lastName;
        p.academicDegreeAfter = li.academicDegreeAfter;
        p.function = "Interessenvertreter (natürliche Person)";
        p.recentGovernmentFunctionPresent = li.recentGovernmentFunctionPresent;
        p.recentGovernmentFunction = li.recentGovernmentFunction;
        return p;
    }

    static List<ClientRef> clientOrganizations(SearchResult r) {
        List<ClientRef> list = new ArrayList<>();
        if (r.contracts == null || r.contracts.contracts == null) return list;
        Set<String> seen = new LinkedHashSet<>();
        for (Contract c : r.contracts.contracts) {
            if (c.clients == null || c.clients.clientOrganizations == null) continue;
            for (ClientRef ref : c.clients.clientOrganizations) {
                if (seen.add(clientName(ref) + "|" + ref.referenceRegisterNumber)) list.add(ref);
            }
        }
        return list;
    }

    private static boolean isLegalRepresentative(SearchResult r, PersonEntry p) {
        return legalRepresentatives(r).stream().anyMatch(x -> same(x, p));
    }

    private static boolean isNaturalLobbyist(SearchResult r, PersonEntry p) {
        PersonEntry n = naturalLobbyist(r);
        return n != null && same(n, p);
    }

    private static boolean isPep(Map<String, PersonPep> peps, PersonEntry p) {
        return peps.containsKey(personKey(p));
    }

    static boolean same(PersonEntry a, PersonEntry b) {
        return personKey(a).equals(personKey(b));
    }

    /** Clé d'une personne : nom normalisé sans les titres (« Dr. »), espaces remplacées par des tirets. */
    static String personKey(PersonEntry p) {
        String n = ((p.firstName == null ? "" : p.firstName) + " " + (p.lastName == null ? "" : p.lastName)).toLowerCase();
        return n.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", "-");
    }

    /** Nom sans titre (Story 2 : les titres vont dans « title », jamais dans le nom). */
    static String plainName(PersonEntry p) {
        return ((p.firstName == null ? "" : p.firstName.trim()) + " " + (p.lastName == null ? "" : p.lastName.trim())).trim();
    }

    /** Titres académiques déclarés avant et après le nom, ou null. */
    static String titles(PersonEntry p) {
        String t = ((p.academicDegreeBefore == null ? "" : p.academicDegreeBefore.trim()) + " "
                + (p.academicDegreeAfter == null ? "" : p.academicDegreeAfter.trim())).trim();
        return t.isEmpty() ? null : t;
    }

    private static String role(PersonEntry p, String fallback) {
        return p.function != null && !p.function.isBlank() ? p.function.trim() : fallback;
    }

    private static String clientName(ClientRef c) {
        String n = c.referenceName != null ? c.referenceName : c.name;
        return n == null ? "?" : n.trim();
    }

    static String regNo(SearchResult r) {
        return r.registerNumber != null ? r.registerNumber.trim() : "UNKNOWN";
    }

    static boolean isActive(SearchResult r) {
        return r.accountDetails == null || r.accountDetails.activeLobbyist;
    }

    static String entityName(SearchResult r) {
        LobbyistIdentity li = r.lobbyistIdentity;
        if (li == null) return "Lobbyist " + regNo(r);
        if (li.name != null && !li.name.isBlank()) return li.name.trim();
        PersonEntry n = naturalLobbyist(r);
        if (n != null) return n.fullName() + (li.companyName != null ? " (" + li.companyName.trim() + ")" : "");
        return "Lobbyist " + regNo(r);
    }

    static String pageUrl(SearchResult r) {
        return r.registerEntryDetails != null && r.registerEntryDetails.detailsPageUrl != null
                ? r.registerEntryDetails.detailsPageUrl
                : "https://www.lobbyregister.bundestag.de/suche/" + regNo(r);
    }
}
