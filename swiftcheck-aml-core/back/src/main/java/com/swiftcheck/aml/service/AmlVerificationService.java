package com.swiftcheck.aml.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.dto.AmlVerificationRequestDTO;

import lombok.extern.slf4j.Slf4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.swiftcheck.aml.dto.AmlMatchDTO;
import com.swiftcheck.aml.dto.AmlVerificationResponseDTO;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class AmlVerificationService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${YENTE_URL:http://localhost:8000}")
    private String YENTE_URL;
    
    private final AmlSearchHistoryService historyService;

    @org.springframework.beans.factory.annotation.Autowired
    public AmlVerificationService(AmlSearchHistoryService historyService) {
        this.historyService = historyService;
    }

    private static final Map<String, FatfJurisdictionRecord> FATF_MAP = new HashMap<>();

    public record FatfJurisdictionRecord(String nameFr, String listType, String riskLevel, String obligations) {}

    static {
        // Blacklist (Contre-mesures obligatoires)
        FATF_MAP.put("kp", new FatfJurisdictionRecord("Corée du Nord (RPDC)", "BLACK_LIST", "CRITICAL", "Application stricte de contre-mesures ciblées (fermeture de succursales bancaires, déclaration systématique Tracfin, gel immédiat des avoirs, embargo financier total)."));
        FATF_MAP.put("ir", new FatfJurisdictionRecord("Iran", "BLACK_LIST", "CRITICAL", "Application obligatoire de contre-mesures financières (vigilance renforcée de niveau maximal, déclaration systématique Tracfin, limitation stricte des relations financières)."));
        FATF_MAP.put("mm", new FatfJurisdictionRecord("Myanmar (Birmanie)", "BLACK_LIST", "CRITICAL", "Vigilance renforcée systématique et proportionnée (EDD), examen approfondi des bénéficiaires effectifs et de l'origine des fonds."));

        // Greylist (Surveillance renforcée)
        FATF_MAP.put("dz", new FatfJurisdictionRecord("Algérie", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI (Oct. 2024), vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("ao", new FatfJurisdictionRecord("Angola", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("bo", new FatfJurisdictionRecord("Bolivie", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("bg", new FatfJurisdictionRecord("Bulgarie", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("bf", new FatfJurisdictionRecord("Burkina Faso", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("cm", new FatfJurisdictionRecord("Cameroun", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("ci", new FatfJurisdictionRecord("Côte d'Ivoire", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("cd", new FatfJurisdictionRecord("RDC (Congo Kinshasa)", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("ht", new FatfJurisdictionRecord("Haïti", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("ke", new FatfJurisdictionRecord("Kenya", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("lb", new FatfJurisdictionRecord("Liban", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI (Oct. 2024), vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("ml", new FatfJurisdictionRecord("Mali", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("mc", new FatfJurisdictionRecord("Monaco", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI (Juin 2024), vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("mz", new FatfJurisdictionRecord("Mozambique", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("na", new FatfJurisdictionRecord("Namibie", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("ng", new FatfJurisdictionRecord("Nigeria", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("ph", new FatfJurisdictionRecord("Philippines", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("sn", new FatfJurisdictionRecord("Sénégal", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("za", new FatfJurisdictionRecord("Afrique du Sud", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("ss", new FatfJurisdictionRecord("Soudan du Sud", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("sy", new FatfJurisdictionRecord("Syrie", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("tz", new FatfJurisdictionRecord("Tanzanie", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("ve", new FatfJurisdictionRecord("Venezuela", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("vn", new FatfJurisdictionRecord("Vietnam", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("ye", new FatfJurisdictionRecord("Yémen", "GREY_LIST", "HIGH", "Surveillance renforcée GAFI, vigilance accrue Art. L. 561-10 CMF."));
        FATF_MAP.put("ru", new FatfJurisdictionRecord("Fédération de Russie", "GREY_LIST", "CRITICAL", "Membre suspendu du GAFI, sanctions internationales strictes."));
    }

    public AmlVerificationResponseDTO verifyClient(AmlVerificationRequestDTO requestDto) {
        AmlVerificationResponseDTO responseDto = new AmlVerificationResponseDTO();
        try {
            // Build the FollowTheMoney (FTM) match payload
            Map<String, Object> entityConfig = new HashMap<>();
            entityConfig.put("schema", requestDto.getSchema());
            entityConfig.put("properties", requestDto.getProperties());

            Map<String, Object> queries = new HashMap<>();
            queries.put("query1", entityConfig);

            Map<String, Object> payload = new HashMap<>();
            payload.put("queries", queries);

            String jsonPayload = objectMapper.writeValueAsString(payload);
            
            String datasetInput = requestDto.getDataset();
            String yentePath = "/match/default";
            StringBuilder queryParams = new StringBuilder("?threshold=0.5&limit=10");
            
            if (datasetInput != null && !datasetInput.trim().isEmpty() && !datasetInput.equals("default")) {
                String[] datasets = datasetInput.split(",");
                for (String ds : datasets) {
                    String trimmed = ds.trim();
                    if (!trimmed.isEmpty()) {
                        queryParams.append("&include_dataset=").append(trimmed);
                    }
                }
            }

            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(YENTE_URL + yentePath + queryParams.toString()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode resultsNode = root.path("responses").path("query1").path("results");
                if (!resultsNode.isMissingNode() && resultsNode.isArray()) {
                    List<AmlMatchDTO> matches = objectMapper.convertValue(resultsNode, new TypeReference<List<AmlMatchDTO>>() {});
                    if (matches != null) {
                        for (AmlMatchDTO m : matches) {
                            enrichComplianceMetadata(m);
                        }
                    }
                    responseDto.setMatches(matches != null ? matches : new ArrayList<>());
                    
                    // Alerte de conformité géographique GAFI / FATF
                    checkFatfCountryCompliance(requestDto, responseDto);

                    // Log the search
                    historyService.logSearch(requestDto, responseDto.getMatches());
                } else {
                    responseDto.setMatches(new ArrayList<>());
                    
                    // Alerte de conformité géographique GAFI / FATF
                    checkFatfCountryCompliance(requestDto, responseDto);

                    historyService.logSearch(requestDto, responseDto.getMatches());
                }
                return responseDto;
            } else {
                log.error("Erreur Yente: HTTP {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("Erreur lors de l'appel à Yente: " + response.statusCode());
            }

        } catch (Exception e) {
            log.error("Erreur d'exécution de la vérification", e);
            throw new RuntimeException("Erreur lors de la vérification AML: " + e.getMessage());
        }
    }

    private void checkFatfCountryCompliance(AmlVerificationRequestDTO requestDto, AmlVerificationResponseDTO responseDto) {
        if (requestDto == null || requestDto.getProperties() == null || responseDto == null) return;
        
        List<String> codesToCheck = new ArrayList<>();
        
        for (String key : List.of("country", "nationality", "jurisdiction")) {
            List<String> vals = requestDto.getProperties().get(key);
            if (vals != null) {
                for (String v : vals) {
                    if (v != null && !v.trim().isEmpty()) {
                        codesToCheck.add(v.trim().toLowerCase());
                    }
                }
            }
        }
        
        for (String code : codesToCheck) {
            FatfJurisdictionRecord record = FATF_MAP.get(code);
            if (record != null) {
                boolean alreadyMatched = responseDto.getMatches().stream()
                        .anyMatch(m -> m.getId() != null && m.getId().contains("fatf-" + code));
                if (!alreadyMatched) {
                    boolean isBlack = "BLACK_LIST".equalsIgnoreCase(record.listType());
                    AmlMatchDTO alert = new AmlMatchDTO();
                    alert.setId("fatf-geo-" + code);
                    alert.setCaption("Alerte Juridiction GAFI / FATF : " + record.nameFr());
                    alert.setSchema("LegalEntity");
                    alert.setScore(1.0);
                    alert.setDatasets(List.of("fatf_jurisdictions"));
                    Map<String, List<String>> props = new HashMap<>();
                    props.put("name", List.of(record.nameFr()));
                    props.put("country", List.of(code));
                    props.put("topics", List.of("sanction.counter", "sanction"));
                    props.put("notes", List.of(record.obligations()));
                    alert.setProperties(props);
                    
                    alert.setPepStatus(isBlack ? "FATF_BLACKLIST" : "FATF_GREYLIST");
                    alert.setPepStatusLabel(isBlack ? "Liste Noire GAFI / FATF (Juridiction à Haut Risque - Contre-Mesures)"
                                                    : "Liste Grise GAFI / FATF (Juridiction sous Surveillance Renforcée)");
                    alert.setPepTier("TIER_1");
                    alert.setPepTierLabel(isBlack ? "Tier 1 - Sanction / Contre-Mesures GAFI Obligatoires (Risque Maximal)"
                                                  : "Tier 1 - Vigilance Renforcée GAFI / Art. L. 561-10 CMF (Risque Élevé)");
                    alert.setRiskCategory(record.riskLevel());
                    
                    responseDto.getMatches().add(0, alert);
                }
            }
        }
    }

    private void enrichComplianceMetadata(AmlMatchDTO match) {
        if (match == null) return;
        
        List<String> datasets = match.getDatasets() != null ? match.getDatasets() : List.of();
        Map<String, List<String>> props = match.getProperties() != null ? match.getProperties() : Map.of();
        List<String> topics = props.getOrDefault("topics", List.of());
        List<String> notes = props.getOrDefault("notes", List.of());
        String caption = match.getCaption() != null ? match.getCaption() : "";
        String rawNotes = (String.join(" ", notes) + " " + caption);
        String allNotesStr = java.text.Normalizer.normalize(rawNotes, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
        
        boolean isWanted = datasets.stream().anyMatch(d -> d.contains("europol") || d.contains("interpol") || d.contains("dea") || d.contains("fbi") || d.contains("wanted"))
                || topics.contains("crime") || topics.contains("wanted") || allNotesStr.contains("fbi") || allNotesStr.contains("europol") || allNotesStr.contains("interpol") || allNotesStr.contains("dea") || allNotesStr.contains("drug enforcement") || allNotesStr.contains("fugitif") || allNotesStr.contains("recherche par") || allNotesStr.contains("notice rouge");
        boolean isFbi = datasets.stream().anyMatch(d -> d.contains("fbi")) || allNotesStr.contains("fbi") || allNotesStr.contains("federal bureau of investigation");
        boolean isDea = datasets.stream().anyMatch(d -> d.contains("dea")) || allNotesStr.contains("dea") || allNotesStr.contains("drug enforcement administration");
        boolean isAcpr = datasets.stream().anyMatch(d -> d.contains("acpr")) || allNotesStr.contains("sanction reglementaire acpr") || allNotesStr.contains("sanction reglementaire amf") || allNotesStr.contains("commission des sanctions");
        boolean isAmf = datasets.stream().anyMatch(d -> d.contains("amf") && !d.contains("acpr")) || allNotesStr.contains("liste noire amf") || allNotesStr.contains("usurpation amf");
        boolean isFca = datasets.stream().anyMatch(d -> d.contains("fca")) || allNotesStr.contains("fca warning") || allNotesStr.contains("financial conduct authority");
        boolean isSec = datasets.stream().anyMatch(d -> d.contains("sec_pause") || d.contains("sec_harmed") || (d.contains("sec") && !d.contains("seco"))) || allNotesStr.contains("sec pause") || allNotesStr.contains("securities and exchange commission") || allNotesStr.contains("us sec");
        boolean isBis = datasets.stream().anyMatch(d -> d.contains("bis")) || topics.contains("export.control") || allNotesStr.contains("us bis") || allNotesStr.contains("bureau of industry and security") || allNotesStr.contains("denied persons list") || allNotesStr.contains("entity list");
        boolean isDisqualified = datasets.stream().anyMatch(d -> d.contains("disqualified") || d.contains("coh") || d.contains("bodacc") || d.contains("fnig"))
                || topics.contains("corp.disqual")
                || allNotesStr.contains("disqualified director")
                || allNotesStr.contains("dirigeant interdit de gestion")
                || allNotesStr.contains("interdiction judiciaire de gerer")
                || allNotesStr.contains("interdiction de gerer")
                || allNotesStr.contains("faillite personnelle")
                || allNotesStr.contains("company directors disqualification")
                || allNotesStr.contains("disqualification under");
        boolean isWorldBank = datasets.stream().anyMatch(d -> d.contains("worldbank")) || (topics.contains("debarment") && !datasets.stream().anyMatch(d -> d.contains("afdb") || d.contains("idb"))) || allNotesStr.contains("banque mondiale") || allNotesStr.contains("world bank");
        boolean isAfdb = datasets.stream().anyMatch(d -> d.contains("afdb")) || allNotesStr.contains("banque africaine de developpement") || allNotesStr.contains("afdb") || allNotesStr.contains("cross-debarment: adb") || allNotesStr.contains("cross debarment : ebrd");
        boolean isIdb = datasets.stream().anyMatch(d -> d.contains("idb")) || allNotesStr.contains("banque interamericaine de developpement") || allNotesStr.contains("bid / idb") || allNotesStr.contains("inter-american development bank") || (allNotesStr.contains("idb") && allNotesStr.contains("debarment"));
        boolean isCanada = datasets.stream().anyMatch(d -> d.contains("canada")) || allNotesStr.contains("canada") || allNotesStr.contains("sema") || allNotesStr.contains("jvcfoa");
        boolean isFatf = datasets.stream().anyMatch(d -> d.contains("fatf"))
                || topics.contains("sanction.counter")
                || allNotesStr.contains("gafi")
                || allNotesStr.contains("fatf")
                || allNotesStr.contains("financial action task force");
        boolean isSanction = datasets.stream().anyMatch(d -> d.contains("sanctions") || d.contains("ofac") || d.contains("eu_") || d.contains("onu") || d.contains("uksl") || d.contains("dgtresor") || d.contains("seco") || d.contains("dfat") || d.contains("worldbank") || d.contains("afdb") || d.contains("idb") || d.contains("canada") || d.contains("amf") || d.contains("acpr") || d.contains("fca") || (d.contains("sec") && !d.contains("seco")) || d.contains("bis") || d.contains("disqualified") || d.contains("coh") || d.contains("bodacc") || d.contains("fnig") || d.contains("fatf")) || isWorldBank || isAfdb || isIdb || isCanada || isAmf || isAcpr || isFca || isSec || isBis || isDisqualified || isFatf;
        boolean isRca = topics.contains("role.rca") || allNotesStr.contains("rca") || allNotesStr.contains("famille") || allNotesStr.contains("conjoint") || allNotesStr.contains("epouse") || allNotesStr.contains("epoux") || allNotesStr.contains("enfant de");
        boolean isSoe = topics.contains("role.soe") || allNotesStr.contains("soe") || allNotesStr.contains("entreprise publique");
        boolean isPep = topics.contains("role.pep") || datasets.contains("wikidata_pep") || datasets.contains("datagouv_pep") || isSoe;
        
        // 1. Statut & Cooling Off (Règle des 12 mois)
        if (isWanted) {
            match.setPepStatus("WANTED_CRIMINAL");
            if (isFbi) {
                match.setPepStatusLabel("Fugitif Recherché FBI (Crimes Financiers, Fraude & Criminalité Organisée)");
                match.setPepTierLabel("Tier 1 - Alerte Criminelle FBI / US Law Enforcement (Risque Critique)");
            } else if (isDea) {
                match.setPepStatusLabel("Fugitif Recherché DEA (Trafic International & Narcotiques)");
                match.setPepTierLabel("Tier 1 - Alerte Criminelle DEA / Cartels (Risque Critique)");
            } else if (datasets.stream().anyMatch(d -> d.contains("interpol")) || allNotesStr.contains("interpol") || allNotesStr.contains("notice rouge")) {
                match.setPepStatusLabel("Notice Rouge Interpol (Mandat International)");
                match.setPepTierLabel("Tier 1 - Alerte Criminelle Internationale (Risque Critique)");
            } else {
                match.setPepStatusLabel("Fugitif Recherché (Europol / Notice UE)");
                match.setPepTierLabel("Tier 1 - Alerte Criminelle (Risque Critique)");
            }
            match.setPepTier("TIER_1");
            match.setRiskCategory("CRITICAL");
        } else if (isAcpr) {
            match.setPepStatus("REGULATORY_SANCTION");
            match.setPepStatusLabel("Sanction ACPR / AMF (Banque de France / LCB-FT)");
            match.setPepTier("TIER_1");
            match.setPepTierLabel("Tier 1 - Sanction Prudentielle & LCB-FT Régulateur Français");
            match.setRiskCategory("CRITICAL");
        } else if (isAmf) {
            match.setPepStatus("FINANCIAL_FRAUD");
            match.setPepStatusLabel("Liste Noire AMF (Acteur Non Autorisé / Fraude)");
            match.setPepTier("TIER_1");
            match.setPepTierLabel("Tier 1 - Fraude Financière & Escroquerie Régulateur Français");
            match.setRiskCategory("CRITICAL");
        } else if (isFca) {
            match.setPepStatus("INVESTOR_ALERT");
            match.setPepStatusLabel("Alerte Régulateur UK FCA (Warning List / Clone Firm)");
            match.setPepTier("TIER_1");
            match.setPepTierLabel("Tier 1 - Alerte Régulateur Financier UK FCA (Société Clone / Non Autorisée)");
            match.setRiskCategory("CRITICAL");
        } else if (isSec) {
            match.setPepStatus("INVESTOR_ALERT");
            match.setPepStatusLabel("Alerte Régulateur US SEC (PAUSE List - Entité Non Agréée)");
            match.setPepTier("TIER_1");
            match.setPepTierLabel("Tier 1 - Alerte Régulateur Financier US SEC (Fraude & Sollicitation)");
            match.setRiskCategory("CRITICAL");
        } else if (isBis) {
            match.setPepStatus("EXPORT_CONTROL");
            match.setPepStatusLabel("Contrôle des Exportations US BIS (Entity List / Denied Persons)");
            match.setPepTier("TIER_1");
            match.setPepTierLabel("Tier 1 - Restrictions Commerciales & Embargo Technologique US (Trade Compliance)");
            match.setRiskCategory("CRITICAL");
        } else if (isDisqualified) {
            match.setPepStatus("DISQUALIFIED_DIRECTOR");
            if (datasets.stream().anyMatch(d -> d.contains("bodacc") || d.contains("fnig")) || allNotesStr.contains("bodacc") || allNotesStr.contains("faillite personnelle") || allNotesStr.contains("l. 653-8")) {
                match.setPepStatusLabel("Interdiction Judiciaire de Gérer (France BODACC / FNIG)");
                match.setPepTierLabel("Tier 1 - Interdiction Judiciaire de Gérer (France - Art. L. 653-8 C. Com.)");
            } else {
                match.setPepStatusLabel("Dirigeant Interdit de Gestion UK (Companies House / Insolvency Service)");
                match.setPepTierLabel("Tier 1 - Interdiction Judiciaire de Gérance & Faillite Frauduleuse");
            }
            match.setPepTier("TIER_1");
            match.setRiskCategory("CRITICAL");
        } else if (isWorldBank) {
            match.setPepStatus("SANCTION");
            match.setPepStatusLabel("Exclusion Banque Mondiale (Marchés Publics / Fraude)");
            match.setPepTier("TIER_1");
            match.setPepTierLabel("Tier 1 - Exclusion Banque Mondiale (Fraude & Corruption)");
            match.setRiskCategory("CRITICAL");
        } else if (isAfdb) {
            match.setPepStatus("MDB_SANCTION");
            match.setPepStatusLabel("Exclusion Banque Africaine de Développement (BAD / Cross-Debarment MDBs)");
            match.setPepTier("TIER_1");
            match.setPepTierLabel("Tier 1 - Exclusion Banque Multilatérale (Fraude, Corruption & Marchés Publics)");
            match.setRiskCategory("CRITICAL");
        } else if (isIdb) {
            match.setPepStatus("MDB_SANCTION");
            match.setPepStatusLabel("Exclusion Banque Interaméricaine de Développement (BID / IDB)");
            match.setPepTier("TIER_1");
            match.setPepTierLabel("Tier 1 - Exclusion Banque Multilatérale (Fraude, Corruption & Marchés Publics)");
            match.setRiskCategory("CRITICAL");
        } else if (isCanada) {
            match.setPepStatus("SANCTION");
            match.setPepStatusLabel("Sanction Canada (SEMA / Affaires Mondiales)");
            match.setPepTier("TIER_1");
            match.setPepTierLabel("Tier 1 - Sanction Économique Canadienne");
            match.setRiskCategory("CRITICAL");
        } else if (isFatf) {
            boolean isBlacklist = allNotesStr.contains("black list")
                    || allNotesStr.contains("contre-mesures")
                    || allNotesStr.contains("countermeasures")
                    || allNotesStr.contains("coree du nord")
                    || allNotesStr.contains("rpdc")
                    || allNotesStr.contains("iran")
                    || allNotesStr.contains("myanmar")
                    || allNotesStr.contains("birmanie")
                    || allNotesStr.contains("russie");
            match.setPepStatus(isBlacklist ? "FATF_BLACKLIST" : "FATF_GREYLIST");
            if (isBlacklist) {
                match.setPepStatusLabel("Liste Noire GAFI / FATF (Juridiction à Haut Risque - Contre-Mesures)");
                match.setPepTierLabel("Tier 1 - Sanction / Contre-Mesures GAFI Obligatoires (Risque Maximal)");
                match.setRiskCategory("CRITICAL");
            } else {
                match.setPepStatusLabel("Liste Grise GAFI / FATF (Juridiction sous Surveillance Renforcée)");
                match.setPepTierLabel("Tier 1 - Vigilance Renforcée GAFI / Art. L. 561-10 CMF (Risque Élevé)");
                match.setRiskCategory("HIGH");
            }
            match.setPepTier("TIER_1");
        } else if (isSanction) {
            match.setPepStatus("SANCTION");
            match.setPepStatusLabel("Sanction Internationale");
            match.setRiskCategory("CRITICAL");
        } else if (isRca) {
            match.setPepStatus("RCA");
            match.setPepStatusLabel("Personne Liée / Famille (RCA)");
            match.setPepTier("TIER_1");
            match.setPepTierLabel("Tier 1 - Risque Très Élevé (Réseau Proche)");
            match.setRiskCategory("HIGH");
        } else if (isPep) {
            List<String> endDates = props.getOrDefault("endDate", List.of());
            String endDateStr = endDates.isEmpty() ? null : endDates.get(0);
            
            boolean inCoolingOff = false;
            boolean formerPep = false;
            
            if (endDateStr != null && endDateStr.length() >= 4) {
                try {
                    String cleanDate = endDateStr.substring(0, Math.min(10, endDateStr.length()));
                    java.time.LocalDate end = java.time.LocalDate.parse(cleanDate);
                    java.time.LocalDate now = java.time.LocalDate.now();
                    java.time.LocalDate coolingLimit = end.plusMonths(12);
                    
                    if (now.isAfter(end)) {
                        if (now.isBefore(coolingLimit) || now.isEqual(coolingLimit)) {
                            inCoolingOff = true;
                            match.setCoolingOffEndDate(coolingLimit.toString());
                        } else {
                            formerPep = true;
                        }
                    }
                } catch (Exception ignored) {}
            }
            
            if (inCoolingOff) {
                match.setPepStatus("COOLING_OFF");
                match.setPepStatusLabel("Période de Refroidissement (12 mois)");
                match.setRiskCategory("HIGH");
            } else if (formerPep) {
                match.setPepStatus("FORMER_PEP");
                match.setPepStatusLabel("Ancien PEP (Historique)");
                match.setRiskCategory("MEDIUM");
            } else {
                match.setPepStatus("ACTIVE_PEP");
                match.setPepStatusLabel("PEP Actif (Mandat en cours)");
                match.setRiskCategory("HIGH");
            }
            
            // 2. Risk Tiering (Tier 1 à Tier 4)
            if (allNotesStr.contains("president de la republique") || allNotesStr.contains("roi") || allNotesStr.contains("chef d'etat") ||
                allNotesStr.contains("premier ministre") || allNotesStr.contains("chancelier") || allNotesStr.contains("president du conseil") ||
                allNotesStr.contains("secretaire general de l'onu") || allNotesStr.contains("directeur general du fmi") || allNotesStr.contains("president de la bce") ||
                allNotesStr.contains("president des etats-unis") || allNotesStr.contains("emir") || allNotesStr.contains("prince heritier") ||
                allNotesStr.contains("commission europeenne") || allNotesStr.contains("emmanuel macron") || allNotesStr.contains("joe biden") ||
                allNotesStr.contains("donald trump") || allNotesStr.contains("volodymyr zelensky") || allNotesStr.contains("pedro sanchez") ||
                allNotesStr.contains("giorgia meloni") || allNotesStr.contains("donald tusk") || allNotesStr.contains("mohammed vi") ||
                allNotesStr.contains("aziz akhannouch") || allNotesStr.contains("xi jinping") || allNotesStr.contains("narendra modi")) {
                match.setPepTier("TIER_1");
                match.setPepTierLabel("Tier 1 - Risque Très Élevé");
            } else if (allNotesStr.contains("depute") || allNotesStr.contains("senateur") || allNotesStr.contains("membre du congres") ||
                       allNotesStr.contains("mep") || allNotesStr.contains("ministre") || allNotesStr.contains("ambassadeur") ||
                       allNotesStr.contains("diplomate") || allNotesStr.contains("corps diplomatique") || allNotesStr.contains("representant permanent") ||
                       allNotesStr.contains("chef d'etat-major") || allNotesStr.contains("cema") || allNotesStr.contains("general d'armee") ||
                       allNotesStr.contains("general de corps d'armee") || allNotesStr.contains("general") || allNotesStr.contains("amiral") ||
                       allNotesStr.contains("bundeswehr") || allNotesStr.contains("joint chiefs") || allNotesStr.contains("saceur") ||
                       allNotesStr.contains("commandement militaire") || allNotesStr.contains("otan") || allNotesStr.contains("nato") ||
                       allNotesStr.contains("cour constitutionnelle") || allNotesStr.contains("conseil constitutionnel") || allNotesStr.contains("cour supreme") ||
                       allNotesStr.contains("banque centrale") || allNotesStr.contains("gouverneur") ||
                       allNotesStr.contains("directrice generale") || allNotesStr.contains("directeur general") || allNotesStr.contains("president-directeur general") ||
                       allNotesStr.contains("president executif") || allNotesStr.contains("engie") || allNotesStr.contains("orange") ||
                       allNotesStr.contains("thales") || allNotesStr.contains("safran") || allNotesStr.contains("airbus") ||
                       allNotesStr.contains("renault") || allNotesStr.contains("totalenergies") || isSoe) {
                match.setPepTier("TIER_2");
                match.setPepTierLabel("Tier 2 - Risque Élevé");
            } else if (allNotesStr.contains("regional") || allNotesStr.contains("departemental") || allNotesStr.contains("metropole") ||
                       allNotesStr.contains("maire de paris") || allNotesStr.contains("maire de marseille") || allNotesStr.contains("maire de lyon") ||
                       allNotesStr.contains("maire de nice") || allNotesStr.contains("maire de toulouse") || allNotesStr.contains("maire de bordeaux") ||
                       allNotesStr.contains("maire de nantes") || allNotesStr.contains("maire de strasbourg") || allNotesStr.contains("maire de montpellier") ||
                       allNotesStr.contains("maire de lille") || allNotesStr.contains("maire de rennes")) {
                match.setPepTier("TIER_3");
                match.setPepTierLabel("Tier 3 - Risque Modéré");
            } else {
                match.setPepTier("TIER_4");
                match.setPepTierLabel("Tier 4 - Risque Standard");
            }
        } else {
            match.setPepStatus("OTHER");
            match.setPepStatusLabel("Entité Signalée");
            match.setRiskCategory("LOW");
        }
    }

    public com.swiftcheck.aml.dto.AmlAdvancedVerificationResponseDTO verifyAdvanced(com.swiftcheck.aml.dto.AmlAdvancedVerificationRequestDTO requestDto) {
        com.swiftcheck.aml.dto.AmlAdvancedVerificationResponseDTO response = new com.swiftcheck.aml.dto.AmlAdvancedVerificationResponseDTO();
        
        try {
            // Verify Company
            AmlVerificationRequestDTO companyReq = new AmlVerificationRequestDTO();
            companyReq.setDataset(requestDto.getDataset());
            companyReq.setSchema(requestDto.getSchema() != null ? requestDto.getSchema() : "Company");
            companyReq.setProperties(new HashMap<>());
            companyReq.getProperties().put("name", List.of(requestDto.getCompanyName()));
            
            if (requestDto.getProperties() != null) {
                requestDto.getProperties().forEach((k, v) -> {
                    if (!"name".equals(k)) {
                        companyReq.getProperties().put(k, v);
                    }
                });
            }
            
            response.setCompanyResult(verifyClient(companyReq));
            
            // Verify UBOs in parallel
            Map<String, AmlVerificationResponseDTO> uboResults = new java.util.concurrent.ConcurrentHashMap<>();
            if (requestDto.getUbos() != null && !requestDto.getUbos().isEmpty()) {
                requestDto.getUbos().parallelStream().forEach(uboName -> {
                    if (uboName != null && !uboName.trim().isEmpty()) {
                        AmlVerificationRequestDTO uboReq = new AmlVerificationRequestDTO();
                        uboReq.setDataset(requestDto.getDataset());
                        uboReq.setSchema("Person");
                        uboReq.setProperties(new HashMap<>());
                        uboReq.getProperties().put("name", List.of(uboName.trim()));
                        
                        uboResults.put(uboName.trim(), verifyClient(uboReq));
                    }
                });
            }
            
            response.setUboResults(uboResults);
            
        } catch (Exception e) {
            log.error("Erreur d'exécution de la vérification avancée", e);
            response.setErrorMessage("Erreur lors de la vérification avancée: " + e.getMessage());
        }
        
        return response;
    }

    public byte[] generateReport(com.swiftcheck.aml.dto.AmlReportRequestDTO reportRequest) {
        try {
            // Load JRXML
            java.io.InputStream reportStream = getClass().getResourceAsStream("/reports/aml_report.jrxml");
            if (reportStream == null) {
                throw new RuntimeException("Le template du rapport est introuvable");
            }
            net.sf.jasperreports.engine.JasperReport jasperReport = net.sf.jasperreports.engine.JasperCompileManager.compileReport(reportStream);

            // Parameters
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("dataset", reportRequest.getRequest().getDataset());
            parameters.put("schema", reportRequest.getRequest().getSchema());
            
            // Format properties beautifully for HTML rendering in PDF
            StringBuilder propsStr = new StringBuilder();
            if (reportRequest.getRequest().getProperties() != null) {
                reportRequest.getRequest().getProperties().forEach((key, vals) -> {
                    propsStr.append("<font color='#64748B'><b>").append(key).append("</b></font>: ").append(String.join(", ", vals)).append("<br>");
                });
            }
            parameters.put("properties", propsStr.toString());

            // Data source
            List<AmlMatchDTO> matches = reportRequest.getResponse().getMatches();
            if (matches == null) {
                matches = new ArrayList<>();
            }
            
            // Total matches count
            parameters.put("totalMatches", String.valueOf(matches.size()));

            // Load Logo
            java.io.InputStream logoStream = getClass().getResourceAsStream("/static/images/logo.png");
            parameters.put("logoPath", logoStream);
            
            // If matches is empty, add a dummy row to indicate "CLEAN"
            if (matches.isEmpty()) {
                AmlMatchDTO cleanMatch = new AmlMatchDTO();
                cleanMatch.setId("-");
                cleanMatch.setCaption("Aucune correspondance trouvée (CLEAN)");
                cleanMatch.setSchema("-");
                cleanMatch.setScore(0.0);
                cleanMatch.setMatchDetailsHtml("");
                matches.add(cleanMatch);
            } else {
                matches.forEach(match -> {
                    enrichComplianceMetadata(match);
                    StringBuilder detailsHtml = new StringBuilder();
                    if (match.getPepStatusLabel() != null) {
                        detailsHtml.append("<font color='#0F172A'><b>Statut AML:</b></font> <font color='#1D4ED8'><b>").append(match.getPepStatusLabel()).append("</b></font><br>");
                    }
                    if (match.getPepTierLabel() != null) {
                        detailsHtml.append("<font color='#0F172A'><b>Niveau de Risque:</b></font> <font color='#DC2626'><b>").append(match.getPepTierLabel()).append("</b></font><br>");
                    }
                    if (match.getCoolingOffEndDate() != null) {
                        detailsHtml.append("<font color='#D97706'><b>Fin Refroidissement:</b> ").append(match.getCoolingOffEndDate()).append("</font><br>");
                    }
                    if (match.getProperties() != null && !match.getProperties().isEmpty()) {
                        match.getProperties().forEach((key, values) -> {
                            detailsHtml.append("<font color='#64748B'><b>").append(key).append("</b></font>: ")
                                       .append(String.join(", ", values)).append("<br>");
                        });
                    }
                    match.setMatchDetailsHtml(detailsHtml.toString());
                });
            }

            net.sf.jasperreports.engine.data.JRBeanCollectionDataSource dataSource = new net.sf.jasperreports.engine.data.JRBeanCollectionDataSource(matches);

            // Fill
            net.sf.jasperreports.engine.JasperPrint jasperPrint = net.sf.jasperreports.engine.JasperFillManager.fillReport(jasperReport, parameters, dataSource);

            // Export to PDF byte array
            return net.sf.jasperreports.engine.JasperExportManager.exportReportToPdf(jasperPrint);

        } catch (Exception e) {
            log.error("Erreur lors de la génération du rapport", e);
            throw new RuntimeException("Erreur de génération de rapport: " + e.getMessage());
        }
    }
}
