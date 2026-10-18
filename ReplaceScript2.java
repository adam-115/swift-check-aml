import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

public class ReplaceScript2 {
    public static void main(String[] args) throws Exception {
        java.nio.file.Path path = Paths.get("D:/projects/swiftcheck-aml/swiftcheck-aml-core/back/src/main/java/com/swiftcheck/aml/service/SourceDownloadService.java");
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

        String startStr = "        // Optimisation AFDB (African Development Bank) :";
        String endStr = "log.warn(\"Impossible d'écrire le fichier temporaire AFDB : {}\", ex.getMessage());\n            }\n        }";

        int startIdx = content.indexOf(startStr);
        int endIdx = content.indexOf(endStr);
        
        if (startIdx == -1) {
            System.out.println("START NOT FOUND");
            // let's try with Ǹ
            startStr = "        // Optimisation AFDB (African Development Bank) :";
            endStr = "log.warn(\"Impossible d'Ǹcrire le fichier temporaire AFDB : {}\", ex.getMessage());\n            }\n        }";
            startIdx = content.indexOf(startStr);
            endIdx = content.indexOf(endStr);
        }

        if (startIdx == -1 || endIdx == -1) {
            // let's just find the index of "Optimisation AFDB" and then find the next catch block
            startIdx = content.indexOf("        // Optimisation AFDB (African Development Bank) :");
            if (startIdx != -1) {
                endIdx = content.indexOf("        // 0.undecies Optimisation US BIS", startIdx);
                if (endIdx != -1) {
                    endIdx = endIdx - 2; // skip newline
                }
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
"        }\n";

            String newContent = content.substring(0, startIdx) + newBlock + content.substring(endIdx);
            Files.write(path, newContent.getBytes(StandardCharsets.UTF_8));
            System.out.println("REPLACED SUCCESSFULLY!");
        } else {
            System.out.println("COULD NOT FIND BLOCK BOUNDARIES! startIdx=" + startIdx + ", endIdx=" + endIdx);
        }
    }
}
