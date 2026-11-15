package com.swiftcheck.aml.plugins.depep.lobbyregister;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterApiClient.IngestionResult;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.ClientOrganization;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.DetailedSearchResults;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.PersonEntry;
import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.SearchResult;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>de_pep_lobbyregister</b> - Ingestion du Lobbyregister du Bundestag et detection des personnes
 * notoirement proches (RCA GwG § 1 Abs. 14) et anciens mandats etatiques (A.2 / B.2 - Drehtuer-Effekt).
 *
 * <p>Conforme aux specifications des Stories 16 et 18 :
 * <ul>
 *   <li>API officielle du Bundestag GET /sucheDetailJson avec cle d'authentification test / prod</li>
 *   <li>Conservation fidele des libelles en allemand sans traduction</li>
 *   <li>Detection automatique des anciens mandats publics (Regierungs- oder Parlamentsfunktionen -> A.2 / B.2)</li>
 *   <li>Detection RCA par co-representation : tous les co-representants d'une PEP au sein d'une entite passent en RCA.1</li>
 *   <li>Detection RCA par relations d'affaires etroites (clientOrganizations / Auftraggeber -> GwG § 1 Abs. 14 Nr. 2)</li>
 *   <li>Trancabilite integrale : empreinte SHA-256, URL source de la fiche (detailsPageUrl)</li>
 * </ul>
 */
@Extension
public class DePepLobbyregisterParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(DePepLobbyregisterParser.class);

    static final String ID_PREFIX = "de-pep-lobbyregister";
    static final String TOPIC_PEP = "role.pep";
    static final String TOPIC_RCA = "role.rca";

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

        Path rawFile = ctx.rawDir() != null ? ctx.rawDir().resolve("lobbyregister.json") : null;
        DetailedSearchResults data;
        String sha256 = null;

        // Mode offline / test si le fichier raw existe deja ou est fourni
        if (ctx.inputFile() != null && Files.exists(ctx.inputFile())) {
            log.info("Chargement offline du fichier Lobbyregister : {}", ctx.inputFile());
            byte[] bytes = Files.readAllBytes(ctx.inputFile());
            sha256 = LobbyregisterApiClient.computeSha256Hex(bytes);
            data = apiClient.parseRawFile(ctx.inputFile());
        } else if (rawFile != null && Files.exists(rawFile)) {
            log.info("Chargement depuis cache raw : {}", rawFile);
            byte[] bytes = Files.readAllBytes(rawFile);
            sha256 = LobbyregisterApiClient.computeSha256Hex(bytes);
            data = apiClient.parseRawFile(rawFile);
        } else {
            IngestionResult res = apiClient.fetchAndSave(url, apiKey, ctx.rawDir());
            data = res.searchResults();
            sha256 = res.sha256Hex();
        }

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        AuditReport audit = convert(data, dataset, sha256);

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
                String name = r.lobbyistIdentity != null && r.lobbyistIdentity.name != null
                        ? r.lobbyistIdentity.name : regNo;
                String pageUrl = r.registerEntryDetails != null ? r.registerEntryDetails.detailsPageUrl : "https://www.lobbyregister.bundestag.de";
                records.add(new SourceRecord("de-lobby-" + regNo, "Organization", name, pageUrl));
            }
        }
        return records;
    }

    public record AuditReport(int totalEntities, int pepDirectCount, int rcaCount, int clientCount, String sha256) {
        public String summaryMessage() {
            return String.format(
                    "Lobbyregister Bund ingere : %d entites analysees, %d PEP directes/anciens mandats (A.2/B.2), " +
                    "%d liens RCA.1 crees (GwG § 1 Abs. 14), %d clients traites. SHA-256: %s",
                    totalEntities, pepDirectCount, rcaCount, clientCount, sha256 != null ? sha256.substring(0, Math.min(12, sha256.length())) : "N/A");
        }
    }

    AuditReport convert(DetailedSearchResults data, FtmDatasetBuilder dataset, String sha256) {
        if (data == null || data.results == null) {
            return new AuditReport(0, 0, 0, 0, sha256);
        }

        int pepCount = 0;
        int rcaCount = 0;
        int clientCount = 0;

        for (SearchResult r : data.results) {
            String regNo = r.registerNumber != null ? r.registerNumber.trim() : "UNKNOWN";
            String pageUrl = r.registerEntryDetails != null && r.registerEntryDetails.detailsPageUrl != null
                    ? r.registerEntryDetails.detailsPageUrl
                    : "https://www.lobbyregister.bundestag.de/suche/" + regNo;

            boolean isActive = r.accountDetails == null || r.accountDetails.activeLobbyist;
            String entityStatus = isActive ? "LOBBY.1" : "LOBBY.2";

            // 1. Entite de lobbying (Organisation / Société)
            String orgName = (r.lobbyistIdentity != null && r.lobbyistIdentity.name != null)
                    ? r.lobbyistIdentity.name.trim()
                    : "Lobbyist " + regNo;

            String orgId = "org-" + regNo.toLowerCase();
            FtmEntity orgEntity = dataset.newEntity("Company", orgId);
            orgEntity.addValue("name", orgName);
            orgEntity.addValue("registrationNumber", regNo);
            orgEntity.addValue("jurisdiction", "de");
            orgEntity.addValue("sourceUrl", pageUrl);
            if (r.lobbyistIdentity != null && r.lobbyistIdentity.legalForm != null && r.lobbyistIdentity.legalForm.de != null) {
                orgEntity.addValue("legalForm", r.lobbyistIdentity.legalForm.de);
            }
            dataset.addEntity(orgEntity);

            List<PersonEntry> representatives = new ArrayList<>();
            if (r.lobbyistIdentity != null && r.lobbyistIdentity.legalRepresentatives != null) {
                representatives.addAll(r.lobbyistIdentity.legalRepresentatives);
            }

            List<PersonEntry> employees = new ArrayList<>();
            if (r.lobbyistIdentity != null) {
                if (r.lobbyistIdentity.entrustedPersons != null) {
                    employees.addAll(r.lobbyistIdentity.entrustedPersons);
                }
                if (r.lobbyistIdentity.namedEmployees != null) {
                    employees.addAll(r.lobbyistIdentity.namedEmployees);
                }
            }

            // Detecter les PEP au sein des representants
            List<PersonEntry> pepRepsInEntity = new ArrayList<>();
            List<PersonEntry> otherRepsInEntity = new ArrayList<>();

            for (PersonEntry rep : representatives) {
                if (rep.lastName == null || rep.lastName.isBlank()) {
                    continue;
                }
                if (rep.recentGovernmentFunctionPresent && rep.recentGovernmentFunction != null) {
                    pepRepsInEntity.add(rep);
                } else {
                    otherRepsInEntity.add(rep);
                }
            }

            // Traitement des Representants PEP (Anciens mandats etatiques -> A.2 / B.2)
            for (PersonEntry pepRep : pepRepsInEntity) {
                pepCount++;
                String personSlug = pepRep.normalizedName().replace(" ", "-");
                String personId = "person-" + personSlug;
                FtmEntity person = dataset.newEntity("Person", personId);
                person.addValue("name", pepRep.fullName());
                person.addValue("firstName", pepRep.firstName);
                person.addValue("lastName", pepRep.lastName);
                person.addValue("country", "de");
                person.addValue("nationality", "de");
                person.addValue("topics", TOPIC_PEP);
                person.addValue("sourceUrl", pageUrl);

                String funcDesc = buildGovFunctionDescription(pepRep);
                person.addValue("position", funcDesc);
                dataset.addEntity(person);

                // Mandat passe
                FtmEntity position = dataset.newEntity("Position", "gov-function-" + personSlug);
                position.addValue("name", funcDesc);
                position.addValue("country", "de");
                dataset.addEntity(position);

                FtmEntity occupancy = dataset.newEntity("Occupancy", personId, "occupancy-ended");
                occupancy.addValue("holder", person.getId());
                occupancy.addValue("post", position.getId());
                occupancy.addValue("status", "ended");
                if (pepRep.recentGovernmentFunction != null && pepRep.recentGovernmentFunction.endDate != null) {
                    occupancy.addValue("endDate", pepRep.recentGovernmentFunction.endDate);
                }
                occupancy.addValue("sourceUrl", pageUrl);
                dataset.addEntity(occupancy);

                // Lien de direction dans l'entite de lobbying
                FtmEntity directorship = dataset.newEntity("Directorship", orgId, personId);
                directorship.addValue("director", person.getId());
                directorship.addValue("organization", orgEntity.getId());
                directorship.addValue("role", pepRep.function != null ? pepRep.function : "Vertretungsberechtigte Person");
                directorship.addValue("status", isActive ? "current" : "ended");
                directorship.addValue("sourceUrl", pageUrl);
                dataset.addEntity(directorship);
            }

            // Traitement des autres representants : s'il y a au moins une PEP dans l'entite,
            // Regle 2 (Story 18) : Tous les co-representants passent en RCA.1 !
            boolean entityHasPep = !pepRepsInEntity.isEmpty();

            for (PersonEntry otherRep : otherRepsInEntity) {
                String personSlug = otherRep.normalizedName().replace(" ", "-");
                String personId = "person-" + personSlug;
                FtmEntity person = dataset.newEntity("Person", personId);
                person.addValue("name", otherRep.fullName());
                person.addValue("firstName", otherRep.firstName);
                person.addValue("lastName", otherRep.lastName);
                person.addValue("country", "de");
                person.addValue("nationality", "de");
                person.addValue("sourceUrl", pageUrl);

                if (entityHasPep) {
                    rcaCount++;
                    person.addValue("topics", TOPIC_RCA);
                    person.addValue("summary", "Personne notoirement proche (RCA.1, GwG § 1 Abs. 14) : co-representant de " + orgName);
                }
                dataset.addEntity(person);

                // Directorship
                FtmEntity directorship = dataset.newEntity("Directorship", orgId, personId);
                directorship.addValue("director", person.getId());
                directorship.addValue("organization", orgEntity.getId());
                directorship.addValue("role", otherRep.function != null ? otherRep.function : "Vertretungsberechtigte Person");
                directorship.addValue("status", isActive ? "current" : "ended");
                directorship.addValue("sourceUrl", pageUrl);
                dataset.addEntity(directorship);

                // Règle 2 : Lien Associate explicite entre chaque PEP et ses co-dirigeants
                if (entityHasPep) {
                    for (PersonEntry pepRep : pepRepsInEntity) {
                        String pepSlug = pepRep.normalizedName().replace(" ", "-");
                        String pepId = dataset.newEntity("Person", "person-" + pepSlug).getId();

                        FtmEntity associate = dataset.newEntity("Associate", pepSlug, personSlug, regNo.toLowerCase());
                        associate.addValue("person", pepId);
                        associate.addValue("associate", person.getId());
                        associate.addValue("relationship", String.format(
                                "Co-représentant dans l'entité %s [%s] avec la PEP %s (GwG § 1 Abs. 14)",
                                orgName, regNo, pepRep.fullName()));
                        associate.addValue("sourceUrl", pageUrl);
                        dataset.addEntity(associate);
                    }
                }
            }

            // Traitement des employes / benannte Beschäftigte
            for (PersonEntry emp : employees) {
                if (emp.lastName == null || emp.lastName.isBlank()) {
                    continue;
                }
                String empSlug = emp.normalizedName().replace(" ", "-");
                String empId = "person-" + empSlug;
                FtmEntity empPerson = dataset.newEntity("Person", empId);
                empPerson.addValue("name", emp.fullName());
                empPerson.addValue("firstName", emp.firstName);
                empPerson.addValue("lastName", emp.lastName);
                empPerson.addValue("country", "de");
                empPerson.addValue("nationality", "de");
                empPerson.addValue("sourceUrl", pageUrl);

                if (emp.recentGovernmentFunctionPresent && emp.recentGovernmentFunction != null) {
                    pepCount++;
                    empPerson.addValue("topics", TOPIC_PEP);
                    empPerson.addValue("position", buildGovFunctionDescription(emp));
                }
                dataset.addEntity(empPerson);

                FtmEntity employment = dataset.newEntity("Employment", orgId, empId);
                employment.addValue("employee", empPerson.getId());
                employment.addValue("employer", orgEntity.getId());
                employment.addValue("role", "Benannte Beschäftigte (Lobbying)");
                employment.addValue("sourceUrl", pageUrl);
                dataset.addEntity(employment);
            }

            // Regle 3 (Story 18) : Traitement des clients / Auftraggeber (Relations d'affaires etroites)
            if (r.clientOrganizations != null) {
                for (ClientOrganization client : r.clientOrganizations) {
                    if (client.name == null || client.name.isBlank()) {
                        continue;
                    }
                    clientCount++;
                    String clientSlug = client.name.toLowerCase().replaceAll("[^a-z0-9]", "-");
                    String clientId = "client-" + clientSlug;
                    FtmEntity clientEntity = dataset.newEntity("Organization", clientId);
                    clientEntity.addValue("name", client.name.trim());
                    clientEntity.addValue("jurisdiction", "de");
                    clientEntity.addValue("sourceUrl", pageUrl);
                    if (client.legalForm != null && client.legalForm.de != null) {
                        clientEntity.addValue("legalForm", client.legalForm.de);
                    }
                    dataset.addEntity(clientEntity);

                    // Lien d'affaires entre le lobbyiste et le client
                    FtmEntity relation = dataset.newEntity("Associate", orgId, clientId);
                    relation.addValue("person", orgEntity.getId());
                    relation.addValue("associate", clientEntity.getId());
                    relation.addValue("relationship", "Auftraggeber / Client relation (GwG § 1 Abs. 14 Nr. 2)");
                    relation.addValue("sourceUrl", pageUrl);
                    dataset.addEntity(relation);
                }
            }
        }

        return new AuditReport(data.results.size(), pepCount, rcaCount, clientCount, sha256);
    }

    private static String buildGovFunctionDescription(PersonEntry p) {
        if (p.recentGovernmentFunction == null || p.recentGovernmentFunction.federalAdministration == null) {
            return "Ehemalige Regierungs- oder Parlamentsfunktion (GwG § 1 Abs. 12)";
        }
        var fa = p.recentGovernmentFunction.federalAdministration;
        StringBuilder sb = new StringBuilder("Ehemalige Funktion: ");
        if (fa.function != null && !fa.function.isBlank()) {
            sb.append(fa.function.trim());
        }
        if (fa.supremeFederalAuthorityShort != null && !fa.supremeFederalAuthorityShort.isBlank()) {
            sb.append(" (").append(fa.supremeFederalAuthorityShort.trim()).append(")");
        } else if (fa.supremeFederalAuthority != null && !fa.supremeFederalAuthority.isBlank()) {
            sb.append(" (").append(fa.supremeFederalAuthority.trim()).append(")");
        }
        return sb.toString();
    }
}

