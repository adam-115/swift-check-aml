package com.swiftcheck.aml.dto;

import com.swiftcheck.aml.entities.ApplicationConfigEntity;

import lombok.Data;

/** Configuration globale, telle qu'échangée avec l'écran « Configuration ». */
@Data
public class ApplicationConfigDTO {
    private String yenteUrl;
    private String yenteUpdateToken;
    private String yenteDataFolder;
    private String yenteContainerDataPath;
    private String yenteManifestPath;
    private String archiveFolder;
    private String workFolder;

    public static ApplicationConfigDTO from(ApplicationConfigEntity e) {
        ApplicationConfigDTO dto = new ApplicationConfigDTO();
        dto.setYenteUrl(e.getYenteUrl());
        dto.setYenteUpdateToken(e.getYenteUpdateToken());
        dto.setYenteDataFolder(e.getYenteDataFolder());
        dto.setYenteContainerDataPath(e.getYenteContainerDataPath());
        dto.setYenteManifestPath(e.getYenteManifestPath());
        dto.setArchiveFolder(e.getArchiveFolder());
        dto.setWorkFolder(e.getWorkFolder());
        return dto;
    }

    public ApplicationConfigEntity toEntity() {
        ApplicationConfigEntity e = new ApplicationConfigEntity();
        e.setYenteUrl(yenteUrl);
        e.setYenteUpdateToken(yenteUpdateToken);
        e.setYenteDataFolder(yenteDataFolder);
        e.setYenteContainerDataPath(yenteContainerDataPath);
        e.setYenteManifestPath(yenteManifestPath);
        e.setArchiveFolder(archiveFolder);
        e.setWorkFolder(workFolder);
        return e;
    }
}
