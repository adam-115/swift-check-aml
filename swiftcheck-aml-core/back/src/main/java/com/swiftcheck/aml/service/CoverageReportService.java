package com.swiftcheck.aml.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.swiftcheck.aml.dto.CountryDTO;
import com.swiftcheck.aml.dto.CoverageDTO;
import com.swiftcheck.aml.dto.CoverageDTO.CategoryCoverage;
import com.swiftcheck.aml.dto.CoverageDTO.CoveringParser;
import com.swiftcheck.aml.dto.CoverageDTO.FunctionCoverage;
import com.swiftcheck.aml.dto.CoverageDTO.Status;
import com.swiftcheck.aml.entities.Completeness;
import com.swiftcheck.aml.entities.Country;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.PepCategoryKind;
import com.swiftcheck.aml.repositories.ParserRepository;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;

/**
 * Rapport PDF détaillé de la couverture PEP (JasperReports : {@code reports/coverage_report.jrxml} et son
 * sous-rapport de synthèse {@code reports/coverage_summary.jrxml}).
 *
 * <p>Reprend ce que montre la page « Couverture PEP », pour un pays ou pour tous : page de garde avec indicateurs et
 * tableau de synthèse par pays, puis pour chaque pays, chaque catégorie et chaque fonction, les parsers qui la couvrent
 * (ce qu'ils couvrent, ce qu'ils font, leur source de données, leur statut actuel). Les données sont mises à plat :
 * une ligne par couple (fonction, parser), regroupée par pays, catégorie et fonction dans le gabarit. La fiche
 * technique d'un parser n'est imprimée qu'à sa première apparition dans le pays.
 */
@Service
public class CoverageReportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int BAR_SEGMENTS = 20;

    private static final String GREY = "#64748B";

    private final PepReferenceService reference;
    private final CoverageService coverage;
    private final ParserRepository parsers;

    private volatile JasperReport report;
    private volatile JasperReport summary;

    public CoverageReportService(PepReferenceService reference, CoverageService coverage, ParserRepository parsers) {
        this.reference = reference;
        this.coverage = coverage;
        this.parsers = parsers;
    }

    /** Totaux d'un pays. */
    private record Stats(int officialCategories, int coveredOfficial, int functions, int covered, int partial, int persons) {
        int none() {
            return functions - covered - partial;
        }
    }

    /** PDF du rapport ; {@code countryIso} vide = tous les pays du référentiel. */
    public byte[] generate(String countryIso) {
        try {
            return JasperExportManager.exportReportToPdf(print(countryIso));
        } catch (JRException e) {
            throw new IllegalStateException("Génération du rapport de couverture impossible : " + e.getMessage(), e);
        }
    }

    /** Rapport rempli, avant export. */
    JasperPrint print(String countryIso) {
        try {
            List<Country> countries = countryIso == null || countryIso.isBlank()
                    ? reference.findCountries() : List.of(reference.getCountry(countryIso.trim().toUpperCase()));
            Map<Long, Parser> parserById = new HashMap<>();
            parsers.findAll().forEach(p -> parserById.put(p.getId(), p));

            List<Map<String, ?>> rows = new ArrayList<>();
            List<Map<String, ?>> summaryRows = new ArrayList<>();
            Set<Long> publishing = new HashSet<>();
            int functions = 0;
            int covered = 0;
            int partial = 0;
            int persons = 0;
            for (Country country : countries) {
                CoverageDTO c = coverage.coverage(country.getIso());
                Stats s = stats(c);
                addCountry(rows, c, s, parserById, publishing);
                summaryRows.add(summaryRow(c, s));
                functions += s.functions();
                covered += s.covered();
                partial += s.partial();
                persons += s.persons();
            }
            if (rows.isEmpty()) {
                throw new IllegalStateException("Aucune donnée de couverture à imprimer.");
            }

            Map<String, Object> params = new HashMap<>();
            params.put("generatedAt", DATE.format(Instant.now()));
            params.put("scope", countries.size() == 1 ? countries.get(0).getName() + " (" + countries.get(0).getIso() + ")"
                    : "Tous les pays (" + countries.size() + ")");
            params.put("kpiCountries", String.valueOf(countries.size()));
            params.put("kpiFunctions", String.valueOf(functions));
            params.put("kpiCovered", String.valueOf(covered));
            params.put("kpiCoveredPct", functions == 0 ? "—" : Math.round(covered * 100.0 / functions) + " % des fonctions");
            params.put("kpiPartial", String.valueOf(partial));
            params.put("kpiNone", String.valueOf(functions - covered - partial));
            params.put("kpiPersons", String.format("%,d", persons).replace(',', ' '));
            params.put("kpiParsers", String.valueOf(publishing.size()));
            params.put("summaryReport", summaryReport());
            params.put("summaryData", new JRMapCollectionDataSource(summaryRows));
            try (InputStream logo = getClass().getResourceAsStream("/static/images/logo.png")) {
                params.put("logoPath", logo == null ? null : new ByteArrayInputStream(logo.readAllBytes()));
            }
            return JasperFillManager.fillReport(mainReport(), params, new JRMapCollectionDataSource(rows));
        } catch (JRException | IOException e) {
            throw new IllegalStateException("Génération du rapport de couverture impossible : " + e.getMessage(), e);
        }
    }

    private JasperReport mainReport() throws JRException {
        JasperReport r = report;
        if (r == null) {
            report = r = compile("/reports/coverage_report.jrxml");
        }
        return r;
    }

    private JasperReport summaryReport() throws JRException {
        JasperReport r = summary;
        if (r == null) {
            summary = r = compile("/reports/coverage_summary.jrxml");
        }
        return r;
    }

    private JasperReport compile(String resource) throws JRException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Le gabarit " + resource + " est introuvable.");
            }
            return JasperCompileManager.compileReport(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ données

    private static Stats stats(CoverageDTO c) {
        int total = 0;
        int covered = 0;
        int partial = 0;
        for (CategoryCoverage cat : c.categories()) {
            for (FunctionCoverage f : cat.functions()) {
                total++;
                if (f.status() == Status.COVERED) {
                    covered++;
                } else if (f.status() == Status.PARTIAL) {
                    partial++;
                }
            }
        }
        return new Stats(c.officialCategories(), c.coveredOfficialCategories(), total, covered, partial, c.personCount());
    }

    private static Map<String, Object> summaryRow(CoverageDTO c, Stats s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("country", c.country().name());
        row.put("iso", c.country().iso());
        row.put("categories", s.coveredOfficial() + " / " + s.officialCategories());
        row.put("functions", String.valueOf(s.functions()));
        row.put("covered", String.valueOf(s.covered()));
        row.put("partial", String.valueOf(s.partial()));
        row.put("none", String.valueOf(s.none()));
        row.put("persons", String.format("%,d", s.persons()).replace(',', ' '));
        row.put("bar", bar(s));
        return row;
    }

    /** Barre de 20 segments : C couvert, P partiel, N non couvert, E sans fonction. */
    static String bar(Stats s) {
        if (s.functions() == 0) {
            return "E".repeat(BAR_SEGMENTS);
        }
        int c = (int) Math.round(s.covered() * (double) BAR_SEGMENTS / s.functions());
        int p = (int) Math.round((s.covered() + s.partial()) * (double) BAR_SEGMENTS / s.functions()) - c;
        p = Math.max(0, Math.min(p, BAR_SEGMENTS - c));
        return "C".repeat(c) + "P".repeat(p) + "N".repeat(BAR_SEGMENTS - c - p);
    }

    private void addCountry(List<Map<String, ?>> rows, CoverageDTO c, Stats s, Map<Long, Parser> parserById, Set<Long> publishing) {
        CountryDTO country = c.country();
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("countryIso", country.iso());
        head.put("countryName", country.name());
        head.put("countryCode", country.iso() + (country.euMember() ? "  ·  Union européenne" : "  ·  hors Union européenne"));
        head.put("countryInfo", countryHtml(c));
        head.put("cFunctions", s.covered() + " / " + s.functions());
        head.put("cCategories", s.coveredOfficial() + " / " + s.officialCategories());
        head.put("cPartial", String.valueOf(s.partial()));
        head.put("cNone", String.valueOf(s.none()));
        head.put("cPersons", String.format("%,d", s.persons()).replace(',', ' '));

        Set<Long> described = new HashSet<>();
        if (c.categories().isEmpty()) {
            Map<String, Object> row = new LinkedHashMap<>(head);
            row.put("categoryCode", "-");
            row.put("categoryTitle", "Aucune catégorie définie");
            row.put("categoryMeta", "Aucune catégorie PEP n'est définie pour ce pays dans le référentiel.");
            row.put("categoryStatus", Status.NONE.name());
            row.put("categoryStatusLabel", "—");
            row.put("functionKey", country.iso() + "-none");
            rows.add(row);
        }
        for (CategoryCoverage cat : c.categories()) {
            Map<String, Object> catRow = new LinkedHashMap<>(head);
            catRow.put("categoryCode", cat.code());
            catRow.put("categoryTitle", cat.code() + "  —  " + cat.label());
            catRow.put("categoryMeta", categoryMeta(cat));
            catRow.put("categoryStatus", cat.status().name());
            catRow.put("categoryStatusLabel", label(cat.status()));
            if (cat.functions().isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>(catRow);
                row.put("functionKey", cat.code() + "-none");
                rows.add(row);
            }
            for (FunctionCoverage f : cat.functions()) {
                Map<String, Object> fnRow = new LinkedHashMap<>(catRow);
                fnRow.put("functionKey", String.valueOf(f.id()));
                fnRow.put("functionTitle", f.label());
                fnRow.put("functionInstitution", f.institution() == null || f.institution().isBlank() ? null : f.institution());
                fnRow.put("functionHolders", f.expectedHolders() == null ? "n.r." : String.valueOf(f.expectedHolders()));
                fnRow.put("functionStatus", f.status().name());
                fnRow.put("functionStatusLabel", label(f.status()));
                fnRow.put("functionReason", functionReason(f));
                if (f.parsers().isEmpty()) {
                    rows.add(fnRow);
                }
                for (CoveringParser p : f.parsers()) {
                    Map<String, Object> row = new LinkedHashMap<>(fnRow);
                    Parser parser = parserById.get(p.id());
                    boolean first = described.add(p.id());
                    if (p.enabled() && p.lastPublishedAt() != null) {
                        publishing.add(p.id());
                    }
                    row.put("pCode", p.datasetName());
                    row.put("pName", p.name());
                    row.put("pCompleteness", p.completeness() == Completeness.COMPLETE ? "COMPLET" : "PARTIEL");
                    row.put("pCompleteKind", p.completeness().name());
                    row.put("pExec", execBadge(p));
                    row.put("pExecKind", execKind(p));
                    row.put("pCover", coverHtml(p));
                    row.put("pSource", sourceHtml(parser));
                    row.put("pStatus", statusHtml(p, parser));
                    row.put("pDescription", descriptionHtml(parser));
                    String tech = first ? technical(parser) : null;
                    row.put("pTech", tech == null ? null : caption("Fiche technique — " + p.datasetName() + " (détaillée une seule fois par pays)") + "<br>" + esc(tech));
                    row.put("pRepeat", first ? null : "Fiche technique de " + p.datasetName() + " : voir sa première apparition dans ce pays.");
                    rows.add(row);
                }
            }
        }
    }

    // ------------------------------------------------------------------ textes

    private static String countryHtml(CoverageDTO c) {
        CountryDTO k = c.country();
        StringBuilder s = new StringBuilder();
        s.append(caption("Base légale")).append("<br>").append(orDash(k.legalBasis()));
        s.append("<br>").append(caption("Rétention après la fin des fonctions")).append("  ").append(k.retentionMonths()).append(" mois");
        s.append("<br>").append(caption("Liste officielle des fonctions PEP")).append("  ").append(orDash(k.officialListReference()));
        if (k.officialListDate() != null) {
            s.append(" du ").append(k.officialListDate().format(DAY));
        }
        if (k.officialListUrl() != null && !k.officialListUrl().isBlank()) {
            s.append("<br>").append(esc(k.officialListUrl()));
        }
        return s.toString();
    }

    private static String categoryMeta(CategoryCoverage cat) {
        long cov = cat.functions().stream().filter(f -> f.status() == Status.COVERED).count();
        long par = cat.functions().stream().filter(f -> f.status() == Status.PARTIAL).count();
        int n = cat.functions().size();
        StringBuilder s = new StringBuilder();
        s.append(kind(cat.kind())).append("  ·  classe de risque ").append(cat.riskClass()).append(" (1 = la plus élevée)");
        if (cat.legalReference() != null && !cat.legalReference().isBlank()) {
            s.append("  ·  ").append(esc(cat.legalReference()));
        }
        s.append("<br>").append(cov).append(" fonction(s) couverte(s) à 100 %, ").append(par).append(" partielle(s), ")
                .append(n - cov - par).append(" non couverte(s) sur ").append(n).append('.');
        return s.toString();
    }

    private static String functionReason(FunctionCoverage f) {
        StringBuilder s = new StringBuilder();
        if (f.institution() != null && !f.institution().isBlank()) {
            s.append(caption("Institution")).append("  ").append(esc(f.institution())).append("<br>");
        }
        s.append(f.reason() == null ? "" : esc(f.reason()));
        if (f.issues() != null && !f.issues().isEmpty()) {
            s.append("<br><font color='#B91C1C'><b>À corriger :</b> ").append(esc(String.join(" ", f.issues()))).append("</font>");
        }
        return s.toString();
    }

    private static String coverHtml(CoveringParser p) {
        StringBuilder s = new StringBuilder();
        s.append(caption("Couvre pour cette fonction")).append("<br>").append(orNotFilled(p.coveredScope()));
        s.append("<br>").append(p.includesFormer() ? "Anciens titulaires inclus." : "Titulaires en fonction uniquement.");
        s.append("<br><br>").append(caption("Reste à couvrir")).append("<br>");
        if (p.gaps() != null && !p.gaps().isBlank()) {
            s.append("<font color='#92400E'>").append(esc(p.gaps())).append("</font>");
        } else {
            s.append(p.completeness() == Completeness.COMPLETE ? "Rien : la source donne tous les titulaires." : "<i>non renseigné</i>");
        }
        return s.toString();
    }

    private static String sourceHtml(Parser parser) {
        if (parser == null) {
            return caption("Source de données") + "<br><i>parser introuvable</i>";
        }
        StringBuilder s = new StringBuilder();
        s.append(caption("Type de source")).append("<br>").append(parser.getParserKind() == null ? "<i>non renseigné</i>" : kindOfSource(parser));
        s.append("<br>").append(caption("Mode de collecte")).append("<br>").append(parser.getDataSourceType() == null ? "—" : mode(parser));
        s.append("<br>").append(caption("Organisme / site")).append("<br>").append(orDash(parser.getWebsiteUrl()));
        s.append("<br>").append(caption("Adresse de la source")).append("<br>");
        s.append(parser.getSourceLocation() == null || parser.getSourceLocation().isBlank()
                ? "téléchargée par le plugin lui-même" : esc(parser.getSourceLocation()));
        String params = parser.getParameters();
        if (params != null && !params.isBlank()) {
            s.append("<br>").append(caption("Paramètres")).append("<br>")
                    .append(esc(params.length() > 500 ? params.substring(0, 500) + "…" : params));
        }
        return s.toString();
    }

    private static String statusHtml(CoveringParser p, Parser parser) {
        StringBuilder s = new StringBuilder();
        s.append(caption("État du parser")).append("<br>")
                .append(p.enabled() ? "<font color='#047857'><b>Actif</b></font>" : "<font color='#B91C1C'><b>Désactivé</b></font>");
        s.append("<br>").append(caption("Dernière exécution")).append("<br>").append(p.lastStatus() == null ? "jamais exécuté" : executionLabel(p.lastStatus()));
        s.append("<br>").append(caption("Dernière publication")).append("<br>").append(p.lastPublishedAt() == null ? "aucune" : DATE.format(p.lastPublishedAt()));
        s.append("<br>").append(caption("Personnes publiées")).append("<br><b>").append(p.personCount()).append("</b>");
        if (p.retainedCount() > 0) {
            s.append(" + ").append(p.retainedCount()).append(" gardées (rétention)");
        }
        s.append(" <font color='").append(GREY).append("'>(total du parser)</font>");
        if (parser != null) {
            s.append("<br>").append(caption("Mise à jour")).append("  ").append(parser.getRefreshHours() > 0 ? "toutes les " + parser.getRefreshHours() + " h" : "à la demande")
                    .append("  ·  rétention ").append(parser.getRetentionMonths()).append(" mois");
            s.append("<br>").append(caption("Plugin")).append("  ").append(orDash(parser.getPluginId()));
            s.append("<br>").append(caption("Licence")).append("  ").append(orDash(parser.getLicence()));
        }
        return s.toString();
    }

    private static String descriptionHtml(Parser parser) {
        if (parser == null) {
            return null;
        }
        StringBuilder s = new StringBuilder();
        s.append(caption("Ce que fait le parser")).append("<br>").append(orNotFilled(parser.getDescription()));
        if (parser.getNotes() != null && !parser.getNotes().isBlank()) {
            s.append("<br><br>").append(caption("Remarques sur la source")).append("<br>").append(esc(parser.getNotes()));
        }
        if (parser.getAttribution() != null && !parser.getAttribution().isBlank()) {
            s.append("<br><br>").append(caption("Attribution")).append("<br>").append(esc(parser.getAttribution()));
        }
        return s.toString();
    }

    private static String execBadge(CoveringParser p) {
        if (!p.enabled()) {
            return "DÉSACTIVÉ";
        }
        if (p.lastStatus() == null) {
            return "JAMAIS EXÉCUTÉ";
        }
        return switch (p.lastStatus()) {
            case RUNNING -> "EN COURS";
            case SUCCESS -> "PUBLIÉ";
            case UNCHANGED -> "À JOUR";
            case BLOCKED -> "BLOQUÉ";
            case FAILED -> "EN ÉCHEC";
        };
    }

    /** OK (publie), WARN (en cours, jamais exécuté), KO (désactivé, échec, bloqué). */
    private static String execKind(CoveringParser p) {
        if (!p.enabled() || p.lastStatus() == ParserExecutionStatus.FAILED || p.lastStatus() == ParserExecutionStatus.BLOCKED) {
            return "KO";
        }
        return p.lastStatus() == null || p.lastStatus() == ParserExecutionStatus.RUNNING || p.lastPublishedAt() == null ? "WARN" : "OK";
    }

    /** Fiche technique en Markdown, ramenée à du texte brut (le rapport n'interprète pas le Markdown). */
    static String technical(Parser parser) {
        String md = parser == null ? null : parser.getTechnicalDescription();
        if (md == null || md.isBlank()) {
            return null;
        }
        String text = md.replace("\r", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\*\\*|__|`", "")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
                .replaceAll("\\[([^\\]]+)]\\(([^)]+)\\)", "$1 ($2)")
                .replaceAll("\n{3,}", "\n\n");
        return text.strip();
    }

    private static String kindOfSource(Parser p) {
        return switch (p.getParserKind()) {
            case OPEN_DATA_FILE -> "fichier open data (CSV, XML, JSON)";
            case API -> "API";
            case SPARQL -> "point d'accès SPARQL";
            case OFFICIAL_GAZETTE -> "journal officiel";
            case WEB_PAGE -> "page web officielle";
            case PDF_DOCUMENT -> "document PDF";
            case DECLARATION_REGISTER -> "registre officiel ou de déclarations";
        };
    }

    private static String mode(Parser p) {
        return switch (p.getDataSourceType()) {
            case HTTP_FILE -> "fichier téléchargé par l'application";
            case LOCAL_FILE -> "fichier local sur le serveur";
            case PLUGIN_MANAGED -> "téléchargé par le plugin";
        };
    }

    private static String executionLabel(ParserExecutionStatus s) {
        return switch (s) {
            case RUNNING -> "en cours";
            case SUCCESS -> "réussie";
            case UNCHANGED -> "réussie, sans changement";
            case BLOCKED -> "bloquée par les contrôles qualité";
            case FAILED -> "en échec";
        };
    }

    private static String kind(PepCategoryKind k) {
        return switch (k) {
            case OFFICIAL -> "Liste officielle";
            case AMLR -> "AMLR 2027";
            case RISK_BASED -> "Approche par les risques";
            case EU -> "Fonction UE";
            case RCA -> "Famille et proches";
        };
    }

    private static String label(Status s) {
        return switch (s) {
            case COVERED -> "COUVERTE";
            case PARTIAL -> "PARTIELLE";
            case NONE -> "NON COUVERTE";
        };
    }

    /** Intitulé de rubrique, en petites capitales grises. */
    private static String caption(String text) {
        return "<font color='" + GREY + "'><b>" + esc(text.toUpperCase()) + "</b></font>";
    }

    private static String orDash(String v) {
        return v == null || v.isBlank() ? "—" : esc(v);
    }

    private static String orNotFilled(String v) {
        return v == null || v.isBlank() ? "<i>non renseigné</i>" : esc(v);
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }
}
