package com.swiftcheck.aml.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.swiftcheck.aml.dto.CountryDTO;
import com.swiftcheck.aml.dto.CoverageDTO;
import com.swiftcheck.aml.dto.CoverageDTO.CategoryCoverage;
import com.swiftcheck.aml.dto.CoverageDTO.CoveringParser;
import com.swiftcheck.aml.dto.CoverageDTO.FunctionCoverage;
import com.swiftcheck.aml.dto.CoverageDTO.Status;
import com.swiftcheck.aml.entities.Country;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.PepCategory;
import com.swiftcheck.aml.entities.PepCategoryKind;
import com.swiftcheck.aml.entities.PepFunction;
import com.swiftcheck.aml.entities.Completeness;
import com.swiftcheck.aml.entities.PepFunctionParser;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;

/**
 * Page « Couverture PEP » : pour chaque fonction de la liste officielle d'un pays, existe-t-il un parser qui
 * la couvre, et combien de personnes a-t-il publiées à sa dernière publication ?
 *
 * <p>Une fonction est <b>couverte</b> si au moins un parser actif qui la déclare complète (table
 * pep_function_parser) a déjà publié (SUCCESS ou UNCHANGED) ; <b>partielle</b> si seuls des parsers partiels
 * l'ont fait. Une catégorie est couverte si toutes ses fonctions le sont, partielle si une partie l'est.
 */
@Service
public class CoverageService {

    private final PepReferenceService reference;
    private final ParserRepository parsers;
    private final ParserExecutionRepository executions;

    public CoverageService(PepReferenceService reference, ParserRepository parsers,
            ParserExecutionRepository executions) {
        this.reference = reference;
        this.parsers = parsers;
        this.executions = executions;
    }

    public CoverageDTO coverage(String countryIso) {
        Country country = reference.getCountry(countryIso);

        // Parsers qui couvrent chaque fonction, avec la complétude du lien ; dernière publication lue une fois par parser
        Map<Long, List<CoveringParser>> parsersByFunction = new HashMap<>();
        for (Parser parser : parsers.findAll()) {
            CoveringParser published = null;
            for (PepFunctionParser link : parser.getCoverage()) {
                if (published == null) {
                    published = toCoveringParser(parser);
                }
                parsersByFunction.computeIfAbsent(link.getFunction().getId(), id -> new ArrayList<>())
                        .add(withLink(published, link));
            }
        }

        // Fonctions du pays, rangées par catégorie
        Map<Long, List<PepFunction>> functionsByCategory = new HashMap<>();
        for (PepFunction function : reference.findFunctions(country.getIso())) {
            functionsByCategory.computeIfAbsent(function.getCategory().getId(), id -> new ArrayList<>()).add(function);
        }

        List<CategoryCoverage> categories = new ArrayList<>();
        int official = 0;
        int coveredOfficial = 0;
        for (PepCategory category : reference.findCategories(country.getIso())) {
            if (!category.isActive()) {
                continue;                                   // désactivée dans l'écran « Catégories PEP »
            }
            List<FunctionCoverage> functions = new ArrayList<>();
            for (PepFunction function : functionsByCategory.getOrDefault(category.getId(), List.of())) {
                List<CoveringParser> covering = parsersByFunction.getOrDefault(function.getId(), List.of());
                Status status = functionStatus(covering);
                functions.add(new FunctionCoverage(function.getId(), function.getLabel(), function.getInstitution(),
                        function.getExpectedHolders(), status, reason(status, covering), issues(covering), covering));
            }
            Status status = categoryStatus(functions);
            if (category.getKind() == PepCategoryKind.OFFICIAL) {
                official++;
                if (status == Status.COVERED) {
                    coveredOfficial++;
                }
            }
            categories.add(new CategoryCoverage(category.getCode(), category.getLabel(), category.getKind(),
                    category.getRiskClass(), category.getLegalReference(), status, functions));
        }

        // Personnes publiées pour ce pays : chaque parser compté une fois, même s'il couvre plusieurs fonctions
        Map<Long, CoveringParser> countryParsers = new HashMap<>();
        categories.stream().flatMap(c -> c.functions().stream()).flatMap(f -> f.parsers().stream())
                .filter(CoverageService::publishes).forEach(p -> countryParsers.putIfAbsent(p.id(), p));
        int persons = countryParsers.values().stream().mapToInt(CoveringParser::personCount).sum();

        return new CoverageDTO(CountryDTO.from(country), official, coveredOfficial, persons, categories);
    }

    /** Un parser couvre réellement une fonction s'il est actif et a déjà publié. */
    private static boolean publishes(CoveringParser parser) {
        return parser.enabled() && parser.lastPublishedAt() != null;
    }

    /** COVERED : un parser complet a publié ; PARTIAL : seulement des parsers partiels ; NONE sinon. */
    private static Status functionStatus(List<CoveringParser> covering) {
        if (covering.stream().anyMatch(p -> publishes(p) && p.completeness() == Completeness.COMPLETE)) {
            return Status.COVERED;
        }
        return covering.stream().anyMatch(CoverageService::publishes) ? Status.PARTIAL : Status.NONE;
    }

    /**
     * Pourquoi la fonction a son état, en une phrase : quel parser la couvre à 100 %, ou pourquoi elle ne l'est
     * que partiellement (seulement des parsers partiels, parser complet qui n'a pas publié), ou pas du tout.
     * Le détail de ce qui manque est dans {@link CoveringParser#gaps()} et {@link #issues}.
     */
    static String reason(Status status, List<CoveringParser> covering) {
        List<String> complete = datasets(covering, p -> publishes(p) && p.completeness() == Completeness.COMPLETE);
        List<String> partial = datasets(covering, p -> publishes(p) && p.completeness() == Completeness.PARTIAL);
        List<String> idle = datasets(covering, p -> !publishes(p));
        return switch (status) {
            case COVERED -> "Couverte à 100 % : " + String.join(", ", complete) + (complete.size() > 1 ? " donnent" : " donne")
                    + " tous les titulaires de la fonction" + (partial.isEmpty() ? "." : ", complété par " + String.join(", ", partial) + ".");
            case PARTIAL -> "Partielle : " + String.join(", ", partial) + (partial.size() > 1 ? " ne donnent" : " ne donne")
                    + " qu'une partie des titulaires"
                    + (datasets(covering, p -> !publishes(p) && p.completeness() == Completeness.COMPLETE).isEmpty()
                            ? ", et aucun parser complet n'existe pour cette fonction."
                            : " ; le parser complet n'a pas encore publié.");
            case NONE -> covering.isEmpty() ? "Non couverte : aucun parser n'est déclaré pour cette fonction."
                    : "Non couverte : " + String.join(", ", idle) + (idle.size() > 1 ? " sont déclarés" : " est déclaré")
                            + " mais n'" + (idle.size() > 1 ? "ont" : "a") + " encore rien publié.";
        };
    }

    /** Problèmes de fonctionnement : parser désactivé, jamais publié, dernière exécution en échec ou bloquée. */
    static List<String> issues(List<CoveringParser> covering) {
        List<String> issues = new ArrayList<>();
        for (CoveringParser p : covering) {
            if (!p.enabled()) {
                issues.add(p.datasetName() + " est désactivé : ses personnes ne sont plus publiées.");
            } else if (p.lastPublishedAt() == null) {
                issues.add(p.datasetName() + (p.lastStatus() == null ? " n'a jamais été exécuté."
                        : " n'a encore rien publié (dernière exécution " + statusLabel(p.lastStatus()) + ")."));
            } else if (p.lastStatus() != null && !AlertService.PUBLISHED.contains(p.lastStatus())
                    && p.lastStatus() != ParserExecutionStatus.RUNNING) {
                issues.add(p.datasetName() + " : la dernière exécution est " + statusLabel(p.lastStatus())
                        + " ; la publication précédente reste en ligne.");
            }
        }
        return issues;
    }

    private static String statusLabel(ParserExecutionStatus status) {
        return switch (status) {
            case RUNNING -> "en cours";
            case SUCCESS -> "réussie";
            case UNCHANGED -> "sans changement";
            case BLOCKED -> "bloquée par les contrôles qualité";
            case FAILED -> "en échec";
        };
    }

    private static List<String> datasets(List<CoveringParser> covering, java.util.function.Predicate<CoveringParser> filter) {
        return covering.stream().filter(filter).map(CoveringParser::datasetName).distinct().toList();
    }

    private static Status categoryStatus(List<FunctionCoverage> functions) {
        long covered = functions.stream().filter(f -> f.status() == Status.COVERED).count();
        if (covered == functions.size() && covered > 0) {
            return Status.COVERED;
        }
        return functions.stream().anyMatch(f -> f.status() != Status.NONE) ? Status.PARTIAL : Status.NONE;
    }

    private CoveringParser toCoveringParser(Parser parser) {
        Optional<ParserExecution> last = executions.findFirstByParserIdOrderByStartedAtDesc(parser.getId());
        Optional<ParserExecution> published = executions
                .findFirstByParserIdAndStatusInOrderByStartedAtDesc(parser.getId(), AlertService.PUBLISHED);
        return new CoveringParser(parser.getId(), parser.getName(), parser.getDatasetName(), parser.isEnabled(),
                Completeness.COMPLETE, false, null, null,
                last.map(ParserExecution::getStatus).orElse(null),
                published.map(ParserExecution::getStartedAt).orElse(null),
                published.map(ParserExecution::getPersonCount).orElse(0),
                published.map(ParserExecution::getRetainedCount).orElse(0));
    }

    /** Même parser, avec la complétude déclarée pour une fonction donnée. */
    private static CoveringParser withLink(CoveringParser p, PepFunctionParser link) {
        return new CoveringParser(p.id(), p.name(), p.datasetName(), p.enabled(), link.getCompleteness(),
                link.isIncludesFormer(), link.getCoveredScope(), link.getGaps(), p.lastStatus(), p.lastPublishedAt(), p.personCount(), p.retainedCount());
    }
}
