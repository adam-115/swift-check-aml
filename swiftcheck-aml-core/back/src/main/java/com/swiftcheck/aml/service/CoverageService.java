package com.swiftcheck.aml.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.swiftcheck.aml.dto.CountryDTO;
import com.swiftcheck.aml.dto.CoverageDTO;
import com.swiftcheck.aml.dto.CoverageDTO.CategoryCoverage;
import com.swiftcheck.aml.dto.CoverageDTO.CoveringParser;
import com.swiftcheck.aml.dto.CoverageDTO.FunctionCoverage;
import com.swiftcheck.aml.dto.CoverageDTO.SourceRef;
import com.swiftcheck.aml.dto.CoverageDTO.Status;
import com.swiftcheck.aml.entities.Country;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.PepCategory;
import com.swiftcheck.aml.entities.PepCategoryKind;
import com.swiftcheck.aml.entities.PepFunction;
import com.swiftcheck.aml.entities.PepFunctionSource;
import com.swiftcheck.aml.entities.PepSource;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.PepFunctionSourceRepository;
import com.swiftcheck.aml.repositories.ParserRepository;

/**
 * Page « Couverture PEP » : pour chaque fonction de la liste officielle d'un pays, existe-t-il un parser qui
 * la couvre, et combien de personnes a-t-il publiées à sa dernière publication ?
 *
 * <p>Pour chaque fonction, les sources identifiées sont aussi listées : une fonction non couverte avec une
 * source non implémentée est le prochain parser à écrire.
 *
 * <p>Une fonction est <b>couverte</b> si au moins un parser actif qui la déclare a déjà publié (SUCCESS ou
 * UNCHANGED). Une catégorie est couverte si toutes ses fonctions le sont, partielle si une partie l'est.
 */
@Service
public class CoverageService {

    private final PepReferenceService reference;
    private final ParserRepository parsers;
    private final ParserExecutionRepository executions;
    private final PepFunctionSourceRepository functionSources;

    public CoverageService(PepReferenceService reference, ParserRepository parsers,
            ParserExecutionRepository executions, PepFunctionSourceRepository functionSources) {
        this.reference = reference;
        this.parsers = parsers;
        this.executions = executions;
        this.functionSources = functionSources;
    }

    public CoverageDTO coverage(String countryIso) {
        Country country = reference.getCountry(countryIso);

        // Parsers qui couvrent chaque fonction, avec leur dernière publication (lue une fois par parser)
        Map<Long, List<CoveringParser>> parsersByFunction = new HashMap<>();
        List<Parser> allParsers = parsers.findAll();
        for (Parser parser : allParsers) {
            CoveringParser covering = null;
            for (PepFunction function : parser.getCoveredFunctions()) {
                if (covering == null) {
                    covering = toCoveringParser(parser);
                }
                parsersByFunction.computeIfAbsent(function.getId(), id -> new ArrayList<>()).add(covering);
            }
        }

        // Sources identifiées pour chaque fonction ; une source est implémentée si un parser la lit
        Set<Long> implementedSources = new HashSet<>();
        for (Parser parser : allParsers) {
            if (parser.getSource() != null) {
                implementedSources.add(parser.getSource().getId());
            }
        }
        Map<Long, List<SourceRef>> sourcesByFunction = new HashMap<>();
        for (PepFunctionSource link : functionSources.findBySourceCountryIso(country.getIso())) {
            PepSource s = link.getSource();
            sourcesByFunction.computeIfAbsent(link.getFunction().getId(), id -> new ArrayList<>())
                    .add(new SourceRef(s.getId(), s.getName(), s.getUrl(), s.getParserKind(), s.getAccess(),
                            s.getStatus(), link.getCompleteness(), link.isIncludesFormer(),
                            implementedSources.contains(s.getId())));
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
                Status status = covering.stream().anyMatch(CoverageService::publishes) ? Status.COVERED : Status.NONE;
                functions.add(new FunctionCoverage(function.getId(), function.getLabel(), function.getInstitution(),
                        function.getExpectedHolders(), status, covering,
                        sourcesByFunction.getOrDefault(function.getId(), List.of())));
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
        int persons = parsersByFunction.values().stream().flatMap(List::stream).distinct()
                .filter(p -> coversCountry(p, categories))
                .filter(CoverageService::publishes)
                .mapToInt(CoveringParser::personCount).sum();

        return new CoverageDTO(CountryDTO.from(country), official, coveredOfficial, persons, categories);
    }

    /** Un parser couvre réellement une fonction s'il est actif et a déjà publié. */
    private static boolean publishes(CoveringParser parser) {
        return parser.enabled() && parser.lastPublishedAt() != null;
    }

    private static Status categoryStatus(List<FunctionCoverage> functions) {
        long covered = functions.stream().filter(f -> f.status() == Status.COVERED).count();
        if (covered == 0) {
            return Status.NONE;
        }
        return covered == functions.size() ? Status.COVERED : Status.PARTIAL;
    }

    /** Vrai si le parser apparaît dans au moins une fonction de ce pays. */
    private static boolean coversCountry(CoveringParser parser, List<CategoryCoverage> categories) {
        return categories.stream().flatMap(c -> c.functions().stream())
                .anyMatch(f -> f.parsers().contains(parser));
    }

    private CoveringParser toCoveringParser(Parser parser) {
        Optional<ParserExecution> last = executions.findFirstByParserIdOrderByStartedAtDesc(parser.getId());
        Optional<ParserExecution> published = executions
                .findFirstByParserIdAndStatusInOrderByStartedAtDesc(parser.getId(), AlertService.PUBLISHED);
        return new CoveringParser(parser.getId(), parser.getName(), parser.getDatasetName(), parser.isEnabled(),
                last.map(ParserExecution::getStatus).orElse(null),
                published.map(ParserExecution::getStartedAt).orElse(null),
                published.map(ParserExecution::getPersonCount).orElse(0),
                published.map(ParserExecution::getRetainedCount).orElse(0));
    }
}
