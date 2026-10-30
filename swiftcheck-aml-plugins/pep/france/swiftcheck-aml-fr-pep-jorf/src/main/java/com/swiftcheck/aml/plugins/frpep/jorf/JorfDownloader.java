package com.swiftcheck.aml.plugins.frpep.jorf;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Récupère les articles utiles du Journal officiel dans l'open data de la DILA et les dépose dans {@code rawDir}.
 *
 * <p>La DILA publie une archive complète ({@code Freemium_jorf_global_<date>.tar.gz}, environ 1,7 Go) et un fichier
 * par publication depuis cette date ({@code JORF_<date>-<heure>.tar.gz}, 2 à 10 Mo). Tout relire à chaque
 * exécution représenterait plusieurs gigaoctets : chaque fichier est donc téléchargé <b>une seule fois</b> dans
 * {@code cacheDir}, réduit aux articles gardés par {@link JorfArticles#keep} (un extrait de quelques Ko), puis
 * supprimé. Les exécutions suivantes ne téléchargent que les nouveaux fichiers.
 *
 * <p>Le cache ne contient que des copies réduites de fichiers publics, jamais d'état de conversion : le supprimer
 * fait seulement tout retélécharger. Les extraits portent la version de la sélection
 * ({@link JorfArticles#SELECTION_VERSION}).
 */
class JorfDownloader {

    private static final Logger log = LoggerFactory.getLogger(JorfDownloader.class);

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - nominations au Journal officiel)";
    private static final Pattern FILE = Pattern.compile(
            "(Freemium_jorf_global_|JORF_)(\\d{4})(\\d{2})(\\d{2})-\\d{6}\\.tar\\.gz");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Un fichier de la DILA : nom, date de publication, archive complète ou non. */
    record DilaFile(String name, LocalDate date, boolean global) {
    }

    /** Bilan du téléchargement, pour le message de l'exécution. */
    record Download(int files, int downloaded, int articles) {
    }

    /**
     * @param indexUrl adresse de la liste des fichiers (se termine par « / »)
     * @param since    date des plus anciennes publications voulues
     * @param delayMs  pause entre deux téléchargements
     */
    Download download(String indexUrl, Path cacheDir, LocalDate since, long delayMs, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(cacheDir);
        Files.createDirectories(rawDir);
        List<DilaFile> files = select(listIndex(indexUrl), since);
        if (files.isEmpty()) {
            throw new IOException("Aucun fichier du JO à lire dans " + indexUrl + " depuis le " + since);
        }

        SortedMap<String, String> articles = new TreeMap<>();
        StringBuilder sources = new StringBuilder("fichier;articles\n");
        int downloaded = 0;
        for (DilaFile file : files) {
            Path extract = cacheDir.resolve(file.name() + ".s" + JorfArticles.SELECTION_VERSION + ".zip");
            if (!Files.exists(extract)) {
                Path archive = cacheDir.resolve(file.name());
                if (!Files.exists(archive)) {
                    if (downloaded > 0 && delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                    fetch(indexUrl + file.name(), archive);
                    downloaded++;
                }
                JorfArticles.writeZip(extract, extractArticles(archive));
                Files.delete(archive);
            }
            SortedMap<String, String> kept = JorfArticles.readZip(extract);
            // Fichiers lus dans l'ordre des dates : une republication plus récente du même article l'emporte
            articles.putAll(kept);
            sources.append(file.name()).append(';').append(kept.size()).append('\n');
        }
        JorfArticles.writeZip(rawDir.resolve(JorfArticles.ARTICLES_FILE), articles);
        Files.writeString(rawDir.resolve(JorfArticles.SOURCES_FILE), sources, StandardCharsets.UTF_8);
        log.info("JO : {} fichiers de la DILA ({} téléchargés), {} articles gardés", files.size(), downloaded,
                articles.size());
        return new Download(files.size(), downloaded, articles.size());
    }

    /**
     * Fichiers à lire : la plus récente archive complète si elle couvre des publications depuis {@code since}, puis
     * les fichiers quotidiens publiés après elle (ou depuis {@code since}).
     */
    static List<DilaFile> select(List<DilaFile> available, LocalDate since) {
        DilaFile global = available.stream().filter(DilaFile::global)
                .max((a, b) -> a.date().compareTo(b.date())).orElse(null);
        List<DilaFile> selected = new ArrayList<>();
        LocalDate dailiesFrom = since;
        if (global != null && global.date().isAfter(since)) {
            selected.add(global);
            dailiesFrom = global.date();
        }
        final LocalDate from = dailiesFrom;
        available.stream().filter(f -> !f.global() && !f.date().isBefore(from))
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(selected::add);
        return selected;
    }

    /** Noms des archives cités dans la page d'index de la DILA. */
    List<DilaFile> listIndex(String indexUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(indexUrl)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("La DILA a répondu HTTP " + response.statusCode() + " (" + indexUrl + ")");
        }
        return parseIndex(response.body());
    }

    static List<DilaFile> parseIndex(String html) {
        TreeSet<String> names = new TreeSet<>();
        Matcher m = FILE.matcher(html);
        while (m.find()) {
            names.add(m.group());
        }
        List<DilaFile> files = new ArrayList<>();
        for (String name : names) {
            Matcher f = FILE.matcher(name);
            f.matches();
            files.add(new DilaFile(name, LocalDate.of(Integer.parseInt(f.group(2)), Integer.parseInt(f.group(3)),
                    Integer.parseInt(f.group(4))), f.group(1).startsWith("Freemium")));
        }
        return files;
    }

    /** Lit une archive {@code .tar.gz} en flux et garde les articles retenus : identifiant → XML. */
    static SortedMap<String, String> extractArticles(Path archive) throws IOException {
        SortedMap<String, String> kept = new TreeMap<>();
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive), 1 << 16),
                1 << 16)) {
            TarReader.forEachFile(in, (path, size, content) -> {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (name.startsWith("JORFARTI") && name.endsWith(".xml") && path.contains("/article/")) {
                    String xml = new String(content.readAllBytes(), StandardCharsets.UTF_8);
                    if (JorfArticles.keep(xml)) {
                        kept.put(name.substring(0, name.length() - 4), xml);
                    }
                }
            });
        }
        log.info("JO : {} → {} articles gardés", archive.getFileName(), kept.size());
        return kept;
    }

    /** Téléchargement vers un fichier temporaire, renommé seulement une fois complet. */
    private void fetch(String url, Path target) throws IOException, InterruptedException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofHours(2))
                .header("User-Agent", USER_AGENT).GET().build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(part));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(part);
            throw new IOException("La DILA a répondu HTTP " + response.statusCode() + " (" + url + ")");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
