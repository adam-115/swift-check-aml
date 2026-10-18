package com.swiftcheck.aml.dto;

import java.util.List;

/**
 * Ce qu'un test va vérifier, affiché dans la fenêtre de confirmation avant de le lancer.
 *
 * @param publication dernière publication réussie (celle qui sera testée), {@code null} s'il n'y en a pas
 * @param sourceFiles fichier(s) source de cette publication, tels que téléchargés et archivés
 * @param lastTest    dernier test de ce parser, pour comparer, {@code null} s'il n'y en a pas
 * @param blocker     raison pour laquelle le test ne peut pas être lancé, {@code null} si tout est prêt
 */
public record ParserTestPreviewDTO(long parserId, String parserName, String datasetName, String pluginId,
        boolean pluginLoaded, ParserExecutionDTO publication, List<SourceFileDTO> sourceFiles,
        ParserTestDTO lastTest, String blocker) {
}
