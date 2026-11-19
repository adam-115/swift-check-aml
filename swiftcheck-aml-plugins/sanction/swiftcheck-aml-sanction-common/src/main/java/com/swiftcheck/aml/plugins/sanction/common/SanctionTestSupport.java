package com.swiftcheck.aml.plugins.sanction.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * Aides pour les tests unitaires des parsers de sanctions (sans dépendance à JUnit).
 *
 * <p>Contrôle sur le fichier complet : si la variable d'environnement {@code SANCTION_SAMPLES} désigne un dossier
 * contenant le fichier réel de la source, le test le convertit en entier et vérifie qu'aucune valeur n'est
 * rejetée ; sinon ce contrôle est sauté.
 */
public final class SanctionTestSupport {

    private SanctionTestSupport() {
    }

    /** Fichier complet de la source pour le contrôle facultatif, ou {@code null} s'il n'est pas disponible. */
    public static Path fullSample(String fileName) {
        String dir = System.getenv("SANCTION_SAMPLES");
        if (dir == null || dir.isBlank()) {
            return null;
        }
        Path p = Path.of(dir, fileName);
        return Files.isRegularFile(p) ? p : null;
    }

    /** Sujet criblable dont le nom (ou un alias) est {@code name}. */
    public static FtmEntity subject(FtmDatasetBuilder dataset, String name) {
        return dataset.entities().stream()
                .filter(e -> FtmDatasetBuilder.SCREENABLE_SCHEMAS.contains(e.getSchema()))
                .filter(e -> e.getValues("name").contains(name) || e.getValues("alias").contains(name))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Aucun sujet nommé « " + name + " »"));
    }

    /** Entité Sanction reliée au sujet. */
    public static FtmEntity sanctionOf(FtmDatasetBuilder dataset, FtmEntity subject) {
        return dataset.entities().stream()
                .filter(e -> "Sanction".equals(e.getSchema()) && e.getValues("entity").contains(subject.getId()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Pas de sanction pour " + subject.getId()));
    }

    /** Résumé lisible des rejets, pour les messages d'échec. */
    public static String rejects(FtmDatasetBuilder dataset) {
        return dataset.qualityReport().getRejectedByProperty() + " " + dataset.qualityReport().getRejectedSamples();
    }

    public static long count(FtmDatasetBuilder dataset, String schema) {
        return dataset.entities().stream().filter(e -> schema.equals(e.getSchema())).count();
    }

    public static List<String> names(List<SanctionRecord> records) {
        return records.stream().map(SanctionRecord::name).toList();
    }

    /**
     * Écrit un petit classeur .xlsx (texte en ligne, une feuille par élément) : permet de tester un parser avec
     * quelques lignes recopiées d'un classeur officiel trop gros pour les ressources de test.
     */
    public static Path writeXlsx(Path target, List<List<List<String>>> sheets) throws java.io.IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(target))) {
            for (int s = 0; s < sheets.size(); s++) {
                StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
                for (List<String> row : sheets.get(s)) {
                    xml.append("<row>");
                    for (String cell : row) {
                        if (cell == null) {
                            xml.append("<c/>");
                        } else {
                            String esc = cell.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                            xml.append("<c t=\"inlineStr\"><is><t>").append(esc).append("</t></is></c>");
                        }
                    }
                    xml.append("</row>");
                }
                xml.append("</sheetData></worksheet>");
                zip.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet" + (s + 1) + ".xml"));
                zip.write(xml.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return target;
    }
}
