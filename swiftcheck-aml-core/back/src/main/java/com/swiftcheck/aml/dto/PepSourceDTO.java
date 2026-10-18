package com.swiftcheck.aml.dto;

import java.util.ArrayList;
import java.util.List;

import com.swiftcheck.aml.entities.Completeness;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserKind;
import com.swiftcheck.aml.entities.PepFunctionSource;
import com.swiftcheck.aml.entities.PepSource;
import com.swiftcheck.aml.entities.PepSourceStatus;
import com.swiftcheck.aml.entities.SourceAccess;

import lombok.Data;

/**
 * Source PEP, telle qu'échangée avec l'interface. En entrée, {@code functions} donne les fonctions couvertes
 * ({@code functionId}, {@code completeness}, {@code includesFormer}) ; {@code parsers} est en lecture seule.
 */
@Data
public class PepSourceDTO {
    private long id;
    private String countryIso;
    private String name;
    private String url;
    private ParserKind parserKind;
    private String licence;
    private SourceAccess access;
    private PepSourceStatus status;
    private String notes;
    private List<FunctionLink> functions = new ArrayList<>();
    /** Parsers qui lisent cette source : vide = source pas encore implémentée. */
    private List<ParserRef> parsers = new ArrayList<>();

    /** Une fonction couverte par la source. */
    public record FunctionLink(long functionId, String categoryCode, String label, Completeness completeness,
            boolean includesFormer) {

        public static FunctionLink from(PepFunctionSource link) {
            return new FunctionLink(link.getFunction().getId(), link.getFunction().getCategory().getCode(),
                    link.getFunction().getLabel(), link.getCompleteness(), link.isIncludesFormer());
        }
    }

    /** Un parser qui lit la source. */
    public record ParserRef(long id, String datasetName, boolean enabled) {

        public static ParserRef from(Parser parser) {
            return new ParserRef(parser.getId(), parser.getDatasetName(), parser.isEnabled());
        }
    }

    public static PepSourceDTO from(PepSource entity, List<PepFunctionSource> links, List<Parser> parsers) {
        PepSourceDTO dto = new PepSourceDTO();
        dto.setId(entity.getId());
        dto.setCountryIso(entity.getCountry().getIso());
        dto.setName(entity.getName());
        dto.setUrl(entity.getUrl());
        dto.setParserKind(entity.getParserKind());
        dto.setLicence(entity.getLicence());
        dto.setAccess(entity.getAccess());
        dto.setStatus(entity.getStatus());
        dto.setNotes(entity.getNotes());
        dto.setFunctions(links.stream().map(FunctionLink::from).toList());
        dto.setParsers(parsers.stream().map(ParserRef::from).toList());
        return dto;
    }
}
