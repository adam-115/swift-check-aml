package com.swiftcheck.aml.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.entities.PepMedia;
import com.swiftcheck.aml.ingestion.DatasetFile;
import com.swiftcheck.aml.repositories.PepMediaRepository;

class PepMediaServiceTest {

    @TempDir
    Path dir;

    private final List<PepMedia> rows = new ArrayList<>();
    private PepMediaRepository repository;
    private ImageFetcher fetcher;
    private PepMediaService service;

    @BeforeEach
    void setUp() {
        repository = mock(PepMediaRepository.class);
        fetcher = mock(ImageFetcher.class);
        when(repository.findByEntityId(anyString())).thenAnswer(c -> rows.stream()
                .filter(m -> m.getEntityId().equals(c.getArgument(0))).toList());
        when(repository.findByDatasetNameAndOrigin(anyString(), anyString())).thenAnswer(c -> rows.stream()
                .filter(m -> m.getDatasetName().equals(c.getArgument(0)) && m.getOrigin().equals(c.getArgument(1)))
                .toList());
        when(repository.findByDatasetNameAndEntityIdAndOrigin(anyString(), anyString(), anyString()))
                .thenAnswer(c -> rows.stream().filter(m -> m.getDatasetName().equals(c.getArgument(0))
                        && m.getEntityId().equals(c.getArgument(1)) && m.getOrigin().equals(c.getArgument(2)))
                        .findFirst());
        when(repository.save(any())).thenAnswer(c -> c.getArgument(0));
        service = new PepMediaService(repository, fetcher);
    }

    private PepMedia row(String entity, String dataset, String origin) {
        PepMedia m = new PepMedia();
        m.setId((long) rows.size() + 1);
        m.setEntityId(entity);
        m.setDatasetName(dataset);
        m.setOrigin(origin);
        rows.add(m);
        return m;
    }

    private ApplicationConfigEntity config(Path archive) {
        ApplicationConfigEntity config = new ApplicationConfigEntity();
        config.setArchiveFolder(archive.toString());
        return config;
    }

    @Test
    void officialPhotoComesBeforeTheOneRetainedByAnAnalyst() {
        row("fr-an-1", "fr_pep_national_assembly", PepMedia.WEB_VALIDATED);
        PepMedia official = row("fr-an-1", "fr_pep_national_assembly", PepMedia.OFFICIAL);
        assertEquals(official, service.lookup("fr-an-1").orElseThrow());
    }

    @Test
    void retainedPhotoWinsWhenTheOfficialFileIsMissing(@TempDir Path archive) throws Exception {
        PepMedia broken = row("fr-an-2", "fr_pep_national_assembly", PepMedia.OFFICIAL);
        broken.setArchivePath(archive.resolve("absent.jpg").toString());
        PepMedia retained = row("fr-an-2", "fr_pep_national_assembly", PepMedia.WEB_VALIDATED);
        Path file = archive.resolve("w.jpg");
        Files.write(file, new byte[] { 1 });
        retained.setArchivePath(file.toString());
        assertEquals(retained, service.lookup("fr-an-2").orElseThrow());
    }

    @Test
    void photosAreNeverSharedBetweenDatasets() {
        row("fr-an-1", "fr_pep_national_assembly", PepMedia.OFFICIAL);
        assertTrue(service.lookup("fr-jorf-1").isEmpty());
    }

    @Test
    void retainedWebPhotoBelongsToTheMatchDatasetAndReplacesThePreviousOne(@TempDir Path archive) throws Exception {
        PepMedia old = row("fr-jorf-1", "fr_pep_jorf", PepMedia.WEB_VALIDATED);
        Path file = archive.resolve("w.jpg");
        Files.write(file, new byte[] { 1 });
        when(fetcher.fetch(eq("https://example.org/w.jpg"), eq(archive.resolve("media").resolve("fr_pep_jorf"))))
                .thenReturn(new ImageFetcher.Fetched(file, "abc", "image/jpeg", 1));

        PepMedia saved = service.validateWebPhoto("fr-jorf-1", "fr_pep_jorf", "https://example.org/w.jpg",
                "https://example.org/page", "analyste1", config(archive));

        verify(repository).delete(old);
        assertEquals("fr_pep_jorf", saved.getDatasetName());
        assertEquals(PepMedia.WEB_VALIDATED, saved.getOrigin());
        assertEquals("analyste1", saved.getValidatedBy());
    }

    @Test
    void publishesDeclaredPhotosAndDropsTheOnesNoLongerDeclared(@TempDir Path archive) throws Exception {
        PepMedia stale = row("fr-an-old", "fr_pep_national_assembly", PepMedia.OFFICIAL);
        PepMedia retained = row("fr-an-1", "fr_pep_national_assembly", PepMedia.WEB_VALIDATED);
        Path entities = dir.resolve("fr_pep_national_assembly.ndjson");
        Files.writeString(entities, "{\"id\":\"fr-an-1\",\"schema\":\"Person\",\"caption\":\"Jean Dupont\","
                + "\"properties\":{\"name\":[\"Jean Dupont\"]}}\n");
        Path media = dir.resolve("fr_pep_national_assembly.media.ndjson");
        Files.writeString(media, "{\"entity\":\"fr-an-1\",\"url\":\"https://example.org/1.jpg\",\"page\":\"https://example.org/1\","
                + "\"licence\":\"© Assemblée nationale\"}\n"
                + "{\"entity\":\"fr-an-1\",\"url\":\"https://example.org/1bis.jpg\"}\n"
                + "{\"entity\":\"absent\",\"url\":\"https://example.org/2.jpg\"}\n");
        Path file = archive.resolve("a.jpg");
        Files.write(file, new byte[] { 1 });
        when(fetcher.fetch(eq("https://example.org/1.jpg"), any()))
                .thenReturn(new ImageFetcher.Fetched(file, "abc", "image/jpeg", 1));

        String note = service.publishOfficial("fr_pep_national_assembly", media, DatasetFile.read(entities),
                config(archive));

        assertEquals(" Photos : 1 publiées, 1 pour des entités absentes.", note);
        verify(repository).delete(stale);
        verify(repository, never()).delete(retained);
        verify(fetcher, never()).fetch(eq("https://example.org/1bis.jpg"), any());
        verify(repository).saveAll(org.mockito.ArgumentMatchers.argThat((List<PepMedia> saved) -> saved.size() == 1
                && saved.get(0).getDatasetName().equals("fr_pep_national_assembly")
                && saved.get(0).getLicence().equals("© Assemblée nationale")));
    }

    @Test
    void failedDownloadIsReportedWithoutStoppingTheOthers(@TempDir Path archive) throws Exception {
        Path entities = dir.resolve("d.ndjson");
        Files.writeString(entities, "{\"id\":\"p1\",\"schema\":\"Person\",\"properties\":{\"name\":[\"Jean Dupont\"]}}\n");
        Path media = dir.resolve("d.media.ndjson");
        Files.writeString(media, "{\"entity\":\"p1\",\"url\":\"https://example.org/403.jpg\"}\n");
        when(fetcher.fetch(anyString(), any())).thenThrow(new IOException("HTTP 403"));

        String note = service.publishOfficial("d", media, DatasetFile.read(entities), config(archive));

        assertEquals(" Photos : 0 publiées, 1 en échec (Jean Dupont (HTTP 403)).", note);
        verify(repository, never()).delete(any());
    }

    @Test
    void noMediaFileMeansNothingToDo() {
        assertEquals("", service.publishOfficial("d", dir.resolve("none.media.ndjson"), null, null));
    }
}
