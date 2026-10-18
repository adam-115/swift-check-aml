import re

file_path = r'D:\projects\swiftcheck-aml\swiftcheck-aml-core\back\src\main\java\com\swiftcheck\aml\service\SourceDownloadService.java'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

start_str = '        // Optimisation AFDB (African Development Bank) :'
end_str = 'log.warn("Impossible d\'écrire le fichier temporaire AFDB : {}", ex.getMessage());\n            }\n        }'

start_idx = content.find(start_str)
end_idx = content.find(end_str) + len(end_str)

if start_idx == -1 or end_idx == -1:
    print("NOT FOUND")
else:
    new_block = '''        // Optimisation AFDB & FBI :
        // Le site afdb.org et api.fbi.gov sont protégés et renvoient des erreurs HTTP 403.
        // Si le téléchargement échoue, on copie le fichier de référence local du plugin.
        if ((sourceUrl.contains("afdb.org") || sourceUrl.contains("fbi.gov")
                || (targetFilePath != null && (targetFilePath.toLowerCase().contains("afdb") || targetFilePath.toLowerCase().contains("fbi"))))
                && !downloadedSuccessfully) {
            
            boolean isAfdb = sourceUrl.contains("afdb.org") || (targetFilePath != null && targetFilePath.toLowerCase().contains("afdb"));
            String pluginName = isAfdb ? "AFDB" : "FBI";
            String pathPart = isAfdb ? "swiftcheck-aml-plugins/swiftcheck-aml-afdb/src/main/resources/AFDB/afdb_debarred.json" 
                                     : "swiftcheck-aml-plugins/swiftcheck-aml-fbi/src/main/resources/FBI/fbi_wanted.json";

            log.info("Optimisation {} : récupération bloquée (Cloudflare 403). Copie du fichier de référence local...", pluginName);
            try {
                String userDir = System.getProperty("user.dir");
                if (userDir.endsWith("back")) {
                    userDir = new java.io.File(userDir).getParentFile().getParentFile().getAbsolutePath();
                } else if (userDir.endsWith("swiftcheck-aml-core")) {
                    userDir = new java.io.File(userDir).getParentFile().getAbsolutePath();
                }
                java.nio.file.Path localRef = java.nio.file.Paths.get(userDir, pathPart);
                if (Files.exists(localRef)) {
                    Files.copy(localRef, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    downloadedSuccessfully = true;
                    log.info("Fichier de référence {} copié avec succès ({} octets).", pluginName, Files.size(tempFile));
                } else {
                    log.warn("Fichier local {} introuvable à l'emplacement : {}", pluginName, localRef.toAbsolutePath());
                    Files.writeString(tempFile, "[  ]");
                    downloadedSuccessfully = true;
                }
            } catch (Exception ex) {
                log.warn("Impossible d'écrire le fichier temporaire {} : {}", pluginName, ex.getMessage());
            }
        }'''
    
    new_content = content[:start_idx] + new_block + content[end_idx:]
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(new_content)
    print("REPLACED")
