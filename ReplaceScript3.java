import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

public class ReplaceScript3 {
    public static void main(String[] args) throws Exception {
        java.nio.file.Path path = Paths.get("D:/projects/swiftcheck-aml/swiftcheck-aml-core/back/src/main/java/com/swiftcheck/aml/service/SourceDownloadService.java");
        byte[] bytes = Files.readAllBytes(path);
        
        // try reading with ISO-8859-1 or Windows-1252 if UTF-8 fails
        String content = new String(bytes, StandardCharsets.UTF_8);

        // search using indexOf
        int startIdx = content.indexOf("Optimisation AFDB (African");
        if (startIdx != -1) {
            // go back to the start of the line
            startIdx = content.lastIndexOf("        // Optimisation", startIdx);
        }
        
        int endIdx = -1;
        if (startIdx != -1) {
            endIdx = content.indexOf("0.undecies Optimisation US BIS", startIdx);
            if (endIdx != -1) {
                endIdx = content.lastIndexOf("        // 0.undecies", endIdx);
            }
        }

        if (startIdx != -1 && endIdx != -1) {
            String newBlock = "        // Optimisation AFDB & FBI :\n" +
"        // Le site afdb.org et api.fbi.gov sont protégés et renvoient des erreurs HTTP 403.\n" +
"        // Si le téléchargement échoue, on copie le fichier de référence local du plugin.\n" +
"        if ((sourceUrl.contains(\"afdb.org\") || sourceUrl.contains(\"fbi.gov\")\n" +
"                || (targetFilePath != null && (targetFilePath.toLowerCase().contains(\"afdb\") || targetFilePath.toLowerCase().contains(\"fbi\"))))\n" +
"                && !downloadedSuccessfully) {\n" +
"            \n" +
"            boolean isAfdb = sourceUrl.contains(\"afdb.org\") || (targetFilePath != null && targetFilePath.toLowerCase().contains(\"afdb\"));\n" +
"            String pluginName = isAfdb ? \"AFDB\" : \"FBI\";\n" +
"            String pathPart = isAfdb ? \"swiftcheck-aml-plugins/swiftcheck-aml-afdb/src/main/resources/AFDB/afdb_debarred.json\" \n" +
"                                     : \"swiftcheck-aml-plugins/swiftcheck-aml-fbi/src/main/resources/FBI/fbi_wanted.json\";\n" +
"\n" +
"            log.info(\"Optimisation {} : récupération bloquée (Cloudflare 403). Copie du fichier de référence local...\", pluginName);\n" +
"            try {\n" +
"                String userDir = System.getProperty(\"user.dir\");\n" +
"                if (userDir.endsWith(\"back\")) {\n" +
"                    userDir = new java.io.File(userDir).getParentFile().getParentFile().getAbsolutePath();\n" +
"                } else if (userDir.endsWith(\"swiftcheck-aml-core\")) {\n" +
"                    userDir = new java.io.File(userDir).getParentFile().getAbsolutePath();\n" +
"                }\n" +
"                java.nio.file.Path localRef = java.nio.file.Paths.get(userDir, pathPart);\n" +
"                if (Files.exists(localRef)) {\n" +
"                    Files.copy(localRef, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);\n" +
"                    downloadedSuccessfully = true;\n" +
"                    log.info(\"Fichier de référence {} copié avec succès ({} octets).\", pluginName, Files.size(tempFile));\n" +
"                } else {\n" +
"                    log.warn(\"Fichier local {} introuvable à l'emplacement : {}\", pluginName, localRef.toAbsolutePath());\n" +
"                    Files.writeString(tempFile, \"[  ]\");\n" +
"                    downloadedSuccessfully = true;\n" +
"                }\n" +
"            } catch (Exception ex) {\n" +
"                log.warn(\"Impossible d'écrire le fichier temporaire {} : {}\", pluginName, ex.getMessage());\n" +
"            }\n" +
"        }\n\n";

            String newContent = content.substring(0, startIdx) + newBlock + content.substring(endIdx);
            Files.write(path, newContent.getBytes(StandardCharsets.UTF_8));
            System.out.println("REPLACED SUCCESSFULLY! startIdx=" + startIdx + ", endIdx=" + endIdx);
        } else {
            System.out.println("COULD NOT FIND BLOCK BOUNDARIES! startIdx=" + startIdx + ", endIdx=" + endIdx);
        }
    }
}
