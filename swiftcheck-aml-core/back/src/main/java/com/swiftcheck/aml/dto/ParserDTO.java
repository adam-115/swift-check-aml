package com.swiftcheck.aml.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.swiftcheck.aml.entities.Completeness;
import com.swiftcheck.aml.entities.DataSourceType;
import com.swiftcheck.aml.entities.ListType;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserKind;
import com.swiftcheck.aml.entities.ParserTest;
import com.swiftcheck.aml.entities.ParserTestStatus;
import com.swiftcheck.aml.entities.ParserExecutionStatus;

import lombok.Data;

/**
 * Parser tel qu'échangé avec l'interface.
 * Les champs {@code last*} sont en lecture seule : ils résument la dernière exécution.
 */
@Data
public class ParserDTO {
    private long id;
    private String name;
    private String description;
    private String datasetName;
    /** PEP, AML (sanctions), MEDIA ou CRIMINAL. */
    private ListType listType = ListType.PEP;
    /** Site de l'organisme qui publie la liste. */
    private String websiteUrl;
    private String pluginId;
    /** Chemin du JAR du plugin sur le serveur (facultatif). */
    private String pluginUrl;
    private DataSourceType dataSourceType;
    private String sourceLocation;
    private String parameters;
    private int refreshHours;
    private ParserKind parserKind;
    private int retentionMonths;
    private String licence;
    private String attribution;
    /** Remarques sur la source. */
    private String notes;
    /** Fonctions PEP couvertes, avec leur complétude ; vide pour une liste de sanctions. */
    private List<CoverageItem> coverage = new ArrayList<>();
    /** Pays des fonctions couvertes (lecture seule) ; vide pour une liste de sanctions. */
    private String countryIso;
    private boolean enabled = true;
    private Instant createdAt;
    private Instant updatedAt;

    private ParserExecutionStatus lastStatus;
    private Instant lastRunAt;
    private String lastMessage;
    /** Personnes et entités gardées par la rétention à la dernière exécution (lecture seule). */
    private int lastPersonCount;
    private int lastRetainedCount;

    /** Dernier test (lecture seule) : chaque élément de la source cherché dans Yente. */
    private Long lastTestId;
    private ParserTestStatus lastTestStatus;
    private Instant lastTestAt;
    private Double lastTestFoundPercent;

    /** Une fonction couverte par le parser (ligne de pep_function_parser). */
    public record CoverageItem(long functionId, Completeness completeness, boolean includesFormer) {
    }

    /** Ajoute le résumé du dernier test ({@code null} : jamais testé). */
    public ParserDTO withLastTest(ParserTest test) {
        if (test != null) {
            lastTestId = test.getId();
            lastTestStatus = test.getStatus();
            lastTestAt = test.getStartedAt();
            lastTestFoundPercent = test.getSourceCount() == 0 ? 0.0
                    : Math.round(test.getFoundCount() * 1000.0 / test.getSourceCount()) / 10.0;
        }
        return this;
    }

    /** @param lastExecution dernière exécution du parser, ou {@code null} s'il n'a jamais tourné */
    public static ParserDTO from(Parser entity, ParserExecution lastExecution) {
        ParserDTO dto = new ParserDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setDatasetName(entity.getDatasetName());
        dto.setListType(entity.getListType());
        dto.setWebsiteUrl(entity.getWebsiteUrl());
        dto.setPluginId(entity.getPluginId());
        dto.setPluginUrl(entity.getPluginUrl());
        dto.setDataSourceType(entity.getDataSourceType());
        dto.setSourceLocation(entity.getSourceLocation());
        dto.setParameters(entity.getParameters());
        dto.setRefreshHours(entity.getRefreshHours());
        dto.setParserKind(entity.getParserKind());
        dto.setRetentionMonths(entity.getRetentionMonths());
        dto.setLicence(entity.getLicence());
        dto.setAttribution(entity.getAttribution());
        dto.setNotes(entity.getNotes());
        dto.setCoverage(entity.getCoverage().stream()
                .map(l -> new CoverageItem(l.getFunction().getId(), l.getCompleteness(), l.isIncludesFormer()))
                .sorted(Comparator.comparingLong(CoverageItem::functionId))
                .toList());
        dto.setCountryIso(entity.getCoverage().stream()
                .map(l -> l.getFunction().getCategory().getCountry().getIso())
                .findFirst().orElse(null));
        dto.setEnabled(entity.isEnabled());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        if (lastExecution != null) {
            dto.setLastStatus(lastExecution.getStatus());
            dto.setLastRunAt(lastExecution.getStartedAt());
            dto.setLastMessage(lastExecution.getMessage());
            dto.setLastPersonCount(lastExecution.getPersonCount());
            dto.setLastRetainedCount(lastExecution.getRetainedCount());
        }
        return dto;
    }

    /**
     * Nouvelle entité avec les champs modifiables. Les fonctions couvertes sont résolues par le contrôleur
     * à partir de {@code coverage}.
     */
    public Parser toEntity() {
        Parser entity = new Parser();
        entity.setName(name);
        entity.setDescription(description);
        entity.setDatasetName(datasetName == null ? null : datasetName.trim());
        entity.setListType(listType == null ? ListType.PEP : listType);
        entity.setWebsiteUrl(websiteUrl == null || websiteUrl.isBlank() ? null : websiteUrl.trim());
        entity.setPluginId(pluginId == null ? null : pluginId.trim());
        entity.setPluginUrl(pluginUrl == null || pluginUrl.isBlank() ? null : pluginUrl.trim());
        entity.setDataSourceType(dataSourceType);
        entity.setSourceLocation(sourceLocation == null ? null : sourceLocation.trim());
        entity.setParameters(parameters);
        entity.setRefreshHours(refreshHours);
        entity.setParserKind(parserKind);
        entity.setRetentionMonths(retentionMonths);
        entity.setLicence(licence == null || licence.isBlank() ? null : licence.trim());
        entity.setAttribution(attribution == null || attribution.isBlank() ? null : attribution.trim());
        entity.setNotes(notes == null || notes.isBlank() ? null : notes.trim());
        entity.setEnabled(enabled);
        return entity;
    }
}
