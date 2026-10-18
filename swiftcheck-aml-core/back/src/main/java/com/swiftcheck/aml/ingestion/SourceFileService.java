package com.swiftcheck.aml.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.entities.DataSourceType;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.SourceFile;
import com.swiftcheck.aml.repositories.SourceFileRepository;

/**
 * Récupère les données sources, les signe et les archive. Chaque fichier traité donne une ligne
 * {@link SourceFile} en base : c'est la preuve de ce qui a été utilisé lors d'une exécution.
 *
 * <h2>Signature</h2>
 * La « signature » est l'empreinte SHA-256 du contenu : deux fichiers identiques ont la même empreinte,
 * et la moindre modification la change complètement. Elle sert à :
 * <ul>
 *   <li>savoir si la source a changé depuis la dernière publication (sinon : UNCHANGED, rien à faire) ;</li>
 *   <li>prouver plus tard qu'une archive n'a pas été modifiée (on recalcule l'empreinte et on compare).</li>
 * </ul>
 *
 * <h2>Archive</h2>
 * Les fichiers sont rangés sous {@code <archiveFolder>/<dataset>/<sha256>.<extension>}. Comme le nom est
 * l'empreinte, un fichier identique à une version déjà archivée n'est pas recopié : l'archive ne grossit
 * que lorsque la source change réellement.
 *
 * <p>Service sans état : tout ce dont il a besoin est passé en paramètre.
 */
@Service
public class SourceFileService {

    private final SourceFileRepository sourceFiles;
    private final HttpDownloader downloader;

    public SourceFileService(SourceFileRepository sourceFiles, HttpDownloader downloader) {
        this.sourceFiles = sourceFiles;
        this.downloader = downloader;
    }

    /**
     * Récupère le fichier d'un parser HTTP_FILE ou LOCAL_FILE, puis le signe et l'archive.
     *
     * @param workDir dossier de travail de l'exécution (pour le fichier temporaire)
     * @return la trace enregistrée ; {@link SourceFile#getArchivePath()} est le fichier à donner au plugin
     */
    public SourceFile fetch(Parser parser, long executionId, Path workDir, ApplicationConfigEntity config)
            throws IOException, InterruptedException {
        String location = parser.getSourceLocation();
        Path temporary = workDir.resolve("source.download");

        if (parser.getDataSourceType() == DataSourceType.HTTP_FILE) {
            downloader.download(location, temporary);
        } else if (parser.getDataSourceType() == DataSourceType.LOCAL_FILE) {
            Path local = Path.of(location);
            if (!Files.isRegularFile(local)) {
                throw new IOException("Fichier source introuvable sur le serveur : " + local);
            }
            Files.copy(local, temporary, StandardCopyOption.REPLACE_EXISTING);
        } else {
            throw new IllegalArgumentException("Le core ne récupère pas les données d'une source "
                    + parser.getDataSourceType());
        }
        return archive(parser, executionId, location, temporary, config);
    }

    /**
     * Pour un parser PLUGIN_MANAGED : signe et archive chaque fichier que le plugin a déposé dans
     * {@code rawDir}, dans l'ordre alphabétique des noms.
     *
     * @return les traces enregistrées ; liste vide si le plugin n'a rien déposé
     */
    public List<SourceFile> archiveRawFiles(Parser parser, long executionId, Path rawDir,
            ApplicationConfigEntity config) throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(rawDir)) {
            files = listing.filter(Files::isRegularFile).sorted().toList();
        }
        List<SourceFile> result = new ArrayList<>();
        for (Path file : files) {
            result.add(archive(parser, executionId, file.getFileName().toString(), file, config));
        }
        return result;
    }

    /**
     * Empreinte globale des données d'une exécution : celle du fichier s'il n'y en a qu'un, sinon l'empreinte
     * de la liste des empreintes. Comparée à celle de la dernière publication pour détecter « rien n'a changé ».
     */
    public static String combinedSha256(List<SourceFile> files) {
        if (files.size() == 1) {
            return files.get(0).getSha256();
        }
        StringBuilder all = new StringBuilder();
        for (SourceFile file : files) {
            all.append(file.getSha256()).append('\n');
        }
        return hex(digest().digest(all.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Calcule l'empreinte SHA-256 d'un fichier, en le lisant par morceaux (pas de limite de taille). */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());   // on lit seulement pour calculer l'empreinte
        }
        return hex(digest.digest());
    }

    /** Signe {@code file}, le copie dans l'archive (si cette version n'y est pas déjà) et enregistre la trace. */
    private SourceFile archive(Parser parser, long executionId, String origin, Path file,
            ApplicationConfigEntity config) throws IOException {
        String sha = sha256(file);
        Path folder = Path.of(config.getArchiveFolder(), parser.getDatasetName());
        Files.createDirectories(folder);
        Path archived = folder.resolve(sha + extensionOf(origin));
        if (!Files.exists(archived)) {
            Files.copy(file, archived);
        }

        SourceFile trace = new SourceFile();
        trace.setExecutionId(executionId);
        trace.setOrigin(origin);
        trace.setSha256(sha);
        trace.setSizeBytes(Files.size(archived));
        trace.setArchivePath(archived.toAbsolutePath().toString());
        trace.setFetchedAt(Instant.now());
        return sourceFiles.save(trace);
    }

    /**
     * Extension du fichier d'après son origine, pour que l'archive reste lisible :
     * {@code https://.../list.xml} → {@code .xml}. Sans extension reconnaissable : {@code .dat}.
     */
    static String extensionOf(String origin) {
        String path = origin.split("[?#]")[0];
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > 6) {
            return ".dat";
        }
        return name.substring(dot).toLowerCase();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);       // SHA-256 est fourni par toutes les JVM
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
