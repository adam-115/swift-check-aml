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
            List<FunctionCoverage> functions = new ArrayList<>();
            for (PepFunction function : functionsByCategory.getOrDefault(category.getId(), List.of())) {
                List<CoveringParser> covering = parsersByFunction.getOrDefault(function.getId(), List.of());
                functions.add(new FunctionCoverage(function.getId(), function.getLabel(), function.getInstitution(),
                        function.getExpectedHolders(), functionStatus(covering), covering));
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
                Completeness.COMPLETE, false,
                last.map(ParserExecution::getStatus).orElse(null),
                published.map(ParserExecution::getStartedAt).orElse(null),
                published.map(ParserExecution::getPersonCount).orElse(0),
                published.map(ParserExecution::getRetainedCount).orElse(0));
    }

    /** Même parser, avec la complétude déclarée pour une fonction donnée. */
    private static CoveringParser withLink(CoveringParser p, PepFunctionParser link) {
        return new CoveringParser(p.id(), p.name(), p.datasetName(), p.enabled(), link.getCompleteness(),
                link.isIncludesFormer(), p.lastStatus(), p.lastPublishedAt(), p.personCount(), p.retainedCount());
    }
}
