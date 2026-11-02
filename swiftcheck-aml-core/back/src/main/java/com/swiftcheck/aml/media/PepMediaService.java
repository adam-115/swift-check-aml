package com.swiftcheck.aml.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.entities.PepMedia;
import com.swiftcheck.aml.ingestion.DatasetFile;
import com.swiftcheck.aml.repositories.PepMediaRepository;

/**
 * Photos des PEP. Chaque photo appartient au dataset qui l'a publiée : une personne présente dans trois datasets a
 * trois photos, une par dataset. Aucun rapprochement entre datasets, pour qu'une publication, une correction ou la
 * suppression d'un parser ne touche que ses propres photos.
 *
 * <ol>
 *   <li><b>Photos officielles</b> ({@link #publishOfficial}) : après une publication réussie, les photos déclarées par le
 *       plugin ({@code <dataset>.media.ndjson}) sont téléchargées, signées (SHA-256), archivées et rangées dans
 *       {@code pep_media}. Elles remplacent celles de la publication précédente du dataset ; une photo déjà
 *       téléchargée (même personne, même adresse) n'est pas retéléchargée.</li>
 *   <li><b>Photo d'une personne</b> ({@link #lookup}) : celle de son entité, officielle d'abord.</li>
 *   <li><b>Photo trouvée sur internet</b> ({@link #validateWebPhoto}) : enregistrée seulement quand un analyste la
 *       retient, avec son nom, dans le dataset de la correspondance ; une publication ne la remplace pas.</li>
 * </ol>
 */
@Service
public class PepMediaService {

    private static final Logger log = LoggerFactory.getLogger(PepMediaService.class);
    private static final long DOWNLOAD_DELAY_MS = 200;

    private final PepMediaRepository repository;
    private final ImageFetcher fetcher;
    private final ObjectMapper json = new ObjectMapper();

    public PepMediaService(PepMediaRepository repository, ImageFetcher fetcher) {
        this.repository = repository;
        this.fetcher = fetcher;
    }

    /**
     * Télécharge et range les photos officielles d'un dataset qui vient d'être publié.
     *
     * @return un bilan court pour le message de l'exécution (« Photos : 574 publiées, 2 en échec. »), vide sans photo
     */
    @Transactional
    public String publishOfficial(String dataset, Path mediaFile, DatasetFile published, ApplicationConfigEntity config) {
        if (!Files.exists(mediaFile)) {
            return "";
        }
        List<PepMedia> previous = repository.findByDatasetNameAndOrigin(dataset, PepMedia.OFFICIAL);
        Map<String, PepMedia> byKey = new HashMap<>();
        previous.forEach(m -> byKey.put(m.getEntityId() + "|" + m.getImageUrl(), m));
        Path folder = Path.of(config.getArchiveFolder(), "media", dataset);

        List<PepMedia> kept = new ArrayList<>();
        Set<String> done = new HashSet<>();
        List<String> failed = new ArrayList<>();
        int unknown = 0;
        try {
            for (String line : Files.readAllLines(mediaFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode m = json.readTree(line);
                String entityId = m.path("entity").asText();
                String url = m.path("url").asText();
                DatasetFile.Entity entity = published.entities().get(entityId);
                if (entity == null) {
                    unknown++;          // entité absente du fichier publié : photo ignorée
                    continue;
                }
                if (done.contains(entityId)) {
                    continue;           // une seule photo officielle par personne et par dataset : la première
                }
                PepMedia media = byKey.get(entityId + "|" + url);
                if (media == null || media.getArchivePath() == null || !Files.exists(Path.of(media.getArchivePath()))) {
                    try {
                        Thread.sleep(DOWNLOAD_DELAY_MS);    // ne pas solliciter les sites officiels en rafale
                        media = download(url, folder);
                        String licence = m.path("licence").asText("");
                        media.setLicence(licence.isBlank() ? fetcher.commonsLicence(url) : licence);
                    } catch (IOException e) {
                        failed.add(entity.caption() + " (" + e.getMessage() + ")");
                        continue;
                    }
                }
                media.setEntityId(entityId);
                media.setDatasetName(dataset);
                media.setOrigin(PepMedia.OFFICIAL);
                media.setPageUrl(m.path("page").asText(null));
                kept.add(media);
                done.add(entityId);
            }
        } catch (IOException e) {
            return " Photos : fichier illisible (" + e.getMessage() + ").";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return " Photos : téléchargement interrompu.";
        }
        // Les photos de la publication précédente qui ne sont plus déclarées disparaissent
        Set<Long> keptIds = new LinkedHashSet<>();
        kept.forEach(m -> keptIds.add(m.getId()));
        previous.stream().filter(p -> !keptIds.contains(p.getId())).forEach(repository::delete);
        repository.flush();
        repository.saveAll(kept);

        String note = String.format(" Photos : %d publiées", kept.size());
        if (!failed.isEmpty()) {
            note += String.format(", %d en échec (%s%s)", failed.size(),
                    String.join(" ; ", failed.subList(0, Math.min(5, failed.size()))), failed.size() > 5 ? "…" : "");
        }
        if (unknown > 0) {
            note += String.format(", %d pour des entités absentes", unknown);
        }
        log.info("{} : {}", dataset, note.trim());
        return note + ".";
    }

    /**
     * Photo de l'entité : l'officielle d'abord, sinon celle retenue par un analyste. Une photo dont le fichier
     * d'archive manque passe après les autres : si l'analyste a retenu une photo internet parce que l'officielle
     * était indisponible, c'est la sienne qui s'affiche.
     */
    public Optional<PepMedia> lookup(String entityId) {
        List<PepMedia> own = repository.findByEntityId(entityId);
        return own.stream()
                .sorted(java.util.Comparator.comparing((PepMedia m) -> !archived(m))
                        .thenComparing(m -> !PepMedia.OFFICIAL.equals(m.getOrigin())))
                .findFirst();
    }

    private static boolean archived(PepMedia m) {
        return m.getArchivePath() != null && Files.exists(Path.of(m.getArchivePath()));
    }

    /**
     * Enregistre une photo trouvée sur internet et retenue par un analyste, dans le dataset de la correspondance. Elle
     * remplace celle qu'un analyste aurait déjà retenue pour la même entité.
     */
    @Transactional
    public PepMedia validateWebPhoto(String entityId, String dataset, String imageUrl, String pageUrl, String analyst,
            ApplicationConfigEntity config) throws IOException, InterruptedException {
        PepMedia media = download(imageUrl, Path.of(config.getArchiveFolder(), "media", dataset));
        repository.findByDatasetNameAndEntityIdAndOrigin(dataset, entityId, PepMedia.WEB_VALIDATED)
                .ifPresent(old -> {
                    repository.delete(old);
                    repository.flush();
                });
        media.setEntityId(entityId);
        media.setDatasetName(dataset);
        media.setOrigin(PepMedia.WEB_VALIDATED);
        media.setPageUrl(pageUrl);
        media.setLicence("Recherche sur internet, retenue par " + analyst + " : droits non vérifiés");
        media.setValidatedBy(analyst);
        return repository.save(media);
    }

    public Optional<PepMedia> get(long id) {
        return repository.findById(id);
    }

    private PepMedia download(String url, Path folder) throws IOException, InterruptedException {
        ImageFetcher.Fetched f = fetcher.fetch(url, folder);
        PepMedia media = new PepMedia();
        media.setImageUrl(url);
        media.setArchivePath(f.file().toAbsolutePath().toString());
        media.setSha256(f.sha256());
        media.setContentType(f.contentType());
        media.setSizeBytes(f.size());
        media.setFetchedAt(Instant.now());
        return media;
    }
}
