package com.swiftcheck.aml.controllers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swiftcheck.aml.entities.PepMedia;
import com.swiftcheck.aml.media.ImageFetcher;
import com.swiftcheck.aml.media.PepMediaService;
import com.swiftcheck.aml.media.PhotoSearchService;
import com.swiftcheck.aml.service.ApplicationConfigService;

/**
 * Photos des PEP pour l'écran de criblage : photo de l'entité dans son dataset (officielle, sinon retenue par un analyste),
 * recherche à la demande sur internet, et enregistrement d'une photo retenue par l'analyste.
 */
@RestController
@RequestMapping("/api/pep-media")
public class PepMediaController {

    private static final Logger log = LoggerFactory.getLogger(PepMediaController.class);

    private final PepMediaService media;
    private final PhotoSearchService search;
    private final ApplicationConfigService config;
    private final ImageFetcher fetcher;

    public PepMediaController(PepMediaService media, PhotoSearchService search, ApplicationConfigService config,
            ImageFetcher fetcher) {
        this.media = media;
        this.search = search;
        this.config = config;
        this.fetcher = fetcher;
    }

    /** Photo affichée : origine, dataset qui l'a publiée, mention et page source. */
    public record PhotoDTO(long id, String origin, String dataset, String licence, String pageUrl, String validatedBy,
            Instant fetchedAt) {

        static PhotoDTO of(PepMedia m) {
            return new PhotoDTO(m.getId(), m.getOrigin(), m.getDatasetName(), m.getLicence(), m.getPageUrl(),
                    m.getValidatedBy(), m.getFetchedAt());
        }
    }

    public record WebSearchRequest(String name, List<String> functions) {
    }

    /** Photo retenue pour l'entité {@code entityId} du dataset {@code dataset} (celui de la correspondance). */
    public record ValidateRequest(String entityId, String dataset, String imageUrl, String pageUrl) {
    }

    /** Photo d'une entité ; 204 s'il n'y en a pas (l'écran propose alors la recherche sur internet). */
    @GetMapping("/lookup")
    public ResponseEntity<PhotoDTO> lookup(@RequestParam String entityId) {
        return media.lookup(entityId).map(m -> ResponseEntity.ok(PhotoDTO.of(m)))
                .orElse(ResponseEntity.noContent().build());
    }

    /** Le fichier de la photo, servi par SwiftCheck : le navigateur de l'analyste n'appelle aucun site extérieur. */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable long id) throws IOException {
        PepMedia m = media.get(id).orElse(null);
        if (m == null || m.getArchivePath() == null || !Files.exists(Path.of(m.getArchivePath()))) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(m.getContentType()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePrivate())
                .body(Files.readAllBytes(Path.of(m.getArchivePath())));
    }

    /**
     * Vignette d'un candidat, relayée par SwiftCheck : le navigateur de l'analyste n'appelle pas le site de l'image.
     * Seules les images proposées par une recherche récente sont relayées (pas de relais ouvert).
     */
    @GetMapping("/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@RequestParam String url) throws InterruptedException {
        if (!search.recentlyShown(url)) {
            return ResponseEntity.notFound().build();
        }
        try {
            ImageFetcher.Image image = fetcher.download(url);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                    .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(30)).cachePrivate())
                    .body(image.bytes());
        } catch (IOException e) {
            return ResponseEntity.status(502).build();
        }
    }

    /** Recherche à la demande : candidats non vérifiés, triés par score de contexte. Rien n'est enregistré. */
    @PostMapping("/web-search")
    public ResponseEntity<List<PhotoSearchService.Candidate>> webSearch(@RequestBody WebSearchRequest request,
            Principal user) {
        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        log.info("Recherche de photos sur internet par {} : « {} »", user == null ? "?" : user.getName(), request.name());
        return ResponseEntity.ok(search.search(request.name(),
                request.functions() == null ? List.of() : request.functions()));
    }

    /** Enregistre la photo retenue par l'analyste (téléchargée, signée, archivée), avec son nom. */
    @PostMapping("/validate")
    public ResponseEntity<PhotoDTO> validate(@RequestBody ValidateRequest request, Principal user)
            throws IOException, InterruptedException {
        if (isBlank(request.entityId()) || isBlank(request.imageUrl()) || isBlank(request.dataset())
                || !request.dataset().matches("[A-Za-z0-9_.-]+")) {     // le dataset nomme aussi le dossier d'archive
            return ResponseEntity.badRequest().build();
        }
        String analyst = user == null ? "inconnu" : user.getName();
        PepMedia saved = media.validateWebPhoto(request.entityId(), request.dataset(), request.imageUrl(),
                request.pageUrl(), analyst, config.current());
        log.info("Photo trouvée sur internet retenue par {} pour {} ({}) : {}", analyst, request.entityId(),
                request.dataset(), request.imageUrl());
        return ResponseEntity.ok(PhotoDTO.of(saved));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
