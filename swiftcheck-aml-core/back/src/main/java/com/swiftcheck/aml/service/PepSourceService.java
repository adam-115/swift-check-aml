package com.swiftcheck.aml.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.swiftcheck.aml.dto.PepSourceDTO;
import com.swiftcheck.aml.entities.Completeness;
import com.swiftcheck.aml.entities.PepFunction;
import com.swiftcheck.aml.entities.PepFunctionSource;
import com.swiftcheck.aml.entities.PepSource;
import com.swiftcheck.aml.entities.PepSourceStatus;
import com.swiftcheck.aml.entities.SourceAccess;
import com.swiftcheck.aml.repositories.ParserRepository;
import com.swiftcheck.aml.repositories.PepFunctionSourceRepository;
import com.swiftcheck.aml.repositories.PepSourceRepository;

/**
 * Sources PEP : où récupérer les titulaires de chaque fonction, et lesquelles sont déjà lues par un parser.
 * C'est le plan de travail des prochains parsers.
 */
@Service
public class PepSourceService {

    private final PepSourceRepository sources;
    private final PepFunctionSourceRepository links;
    private final ParserRepository parsers;
    private final PepReferenceService reference;

    public PepSourceService(PepSourceRepository sources, PepFunctionSourceRepository links,
            ParserRepository parsers, PepReferenceService reference) {
        this.sources = sources;
        this.links = links;
        this.parsers = parsers;
        this.reference = reference;
    }

    /** Sources d'un pays, ou de tous les pays si {@code countryIso} est vide, avec leurs fonctions et parsers. */
    public List<PepSourceDTO> findAll(String countryIso) {
        List<PepSource> found = countryIso == null || countryIso.isBlank()
                ? sources.findAllByOrderByCountryIsoAscNameAsc()
                : sources.findByCountryIsoOrderByNameAsc(countryIso.trim().toUpperCase());
        return found.stream().map(this::toDto).toList();
    }

    public PepSourceDTO get(long id) {
        return toDto(find(id));
    }

    public PepSource find(long id) {
        return sources.findById(id).orElseThrow(() -> new IllegalArgumentException("Source introuvable : " + id));
    }

    /**
     * Crée ({@code id == 0}) ou modifie une source. Les fonctions couvertes sont remplacées par celles de
     * {@code dto.functions}.
     */
    @Transactional
    public PepSourceDTO save(long id, PepSourceDTO dto) {
        PepSource source = id == 0 ? new PepSource() : find(id);
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new IllegalArgumentException("Nom de la source obligatoire.");
        }
        if (dto.getUrl() != null && !dto.getUrl().isBlank() && !dto.getUrl().trim().matches("(?i)https?://.+")) {
            throw new IllegalArgumentException("L'adresse de la source doit commencer par http:// ou https://.");
        }
        source.setCountry(reference.getCountry(dto.getCountryIso()));
        source.setName(dto.getName().trim());
        source.setUrl(blankToNull(dto.getUrl()));
        source.setParserKind(dto.getParserKind());
        source.setLicence(blankToNull(dto.getLicence()));
        source.setAccess(dto.getAccess() == null ? SourceAccess.OPEN : dto.getAccess());
        source.setStatus(dto.getStatus() == null ? PepSourceStatus.CANDIDATE : dto.getStatus());
        source.setNotes(blankToNull(dto.getNotes()));
        PepSource saved = sources.save(source);

        // Fonctions couvertes : remplacées en entier, chacune une seule fois
        links.deleteBySourceId(saved.getId());
        Set<Long> seen = new HashSet<>();
        for (PepSourceDTO.FunctionLink link : dto.getFunctions()) {
            if (!seen.add(link.functionId())) {
                continue;
            }
            PepFunction function = reference.findFunctionsByIds(List.of(link.functionId())).iterator().next();
            PepFunctionSource entity = new PepFunctionSource();
            entity.setSource(saved);
            entity.setFunction(function);
            entity.setCompleteness(link.completeness() == null ? Completeness.PARTIAL : link.completeness());
            entity.setIncludesFormer(link.includesFormer());
            links.save(entity);
        }
        return toDto(saved);
    }

    /** Supprime une source et ses liens ; refusé si un parser la lit encore. */
    @Transactional
    public void delete(long id) {
        if (!parsers.findBySourceId(id).isEmpty()) {
            throw new IllegalStateException("Un parser lit encore cette source : changez sa source d'abord.");
        }
        links.deleteBySourceId(id);
        sources.deleteById(id);
    }

    private PepSourceDTO toDto(PepSource source) {
        return PepSourceDTO.from(source,
                links.findBySourceIdOrderByFunctionCategorySortOrderAscFunctionIdAsc(source.getId()),
                parsers.findBySourceId(source.getId()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
