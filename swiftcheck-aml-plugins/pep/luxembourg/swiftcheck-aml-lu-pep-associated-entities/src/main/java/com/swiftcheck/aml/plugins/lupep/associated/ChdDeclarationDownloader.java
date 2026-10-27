package com.swiftcheck.aml.plugins.lupep.associated;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Récupère sur chd.lu la dernière déclaration d'intérêts financiers de chaque député et la dépose dans
 * {@code rawDir}, où le core la signe et l'archive :
 * <ol>
 *   <li>la page des députés donne les liens {@code /fr/deputy/<id>} ;</li>
 *   <li>la page de chaque député donne son nom et ses déclarations {@code <id>[_AAAA-AAAA]_DIF_rev<n>.pdf} ;</li>
 *   <li>la plus récente est enregistrée sous {@code <id>.pdf}, et la liste des députés dans {@value #DEPUTIES_FILE}.</li>
 * </ol>
 * Les pages sont lues l'une après l'autre, avec une pause entre deux appels, pour ménager le site.
 */
class ChdDeclarationDownloader {

    private static final Logger log = LoggerFactory.getLogger(ChdDeclarationDownloader.class);

    static final String DEPUTIES_FILE = "deputies.json";

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Luxembourg - declarations des deputes)";
    private static final Pattern DEPUTY_LINK = Pattern.compile("/fr/deputy/(\\d+)");
    private static final Pattern TITLE = Pattern.compile("<title>\\s*([^<|]+?)\\s*(?:\\||</title>)");
    /** Déclaration d'intérêts financiers : {@code 957_2023-2028_DIF_rev3.pdf} ou, plus ancienne, {@code 957_DIF_rev1.pdf}. */
    private static final Pattern DECLARATION = Pattern.compile(
            "https?://wdocs-pub\\.chd\\.lu/declarations/(\\d+)(?:_(\\d{4})-(\\d{4}))?_DIF_rev(\\d+)\\.pdf");

    /** Lecture d'une adresse ; remplacée dans les tests. */
    interface Fetcher {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    /** Un député, tel qu'écrit dans {@value #DEPUTIES_FILE}. {@code file} est null s'il n'a pas de déclaration. */
    record Deputy(String id, String name, String pageUrl, String declarationUrl, String file) {
    }

    private final Fetcher fetcher;
    private final long delayMillis;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    ChdDeclarationDownloader(long delayMillis) {
        this(httpFetcher(), delayMillis);
    }

    ChdDeclarationDownloader(Fetcher fetcher, long delayMillis) {
        this.fetcher = fetcher;
        this.delayMillis = delayMillis;
    }

    List<Deputy> download(String deputiesUrl, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        URI base = URI.create(deputiesUrl);
        Set<String> ids = deputyIds(text(fetcher.get(deputiesUrl)));
        if (ids.isEmpty()) {
            throw new IOException("Aucun député trouvé sur " + deputiesUrl + " : la page a peut-être changé.");
        }

        List<Deputy> deputies = new ArrayList<>();
        for (String id : ids) {
            pause();
            String pageUrl = base.resolve("/fr/deputy/" + id).toString();
            String page = text(fetcher.get(pageUrl));
            String name = name(page).orElse(null);
            Optional<String> declaration = latestDeclaration(page, id);
            String file = null;
            if (declaration.isPresent()) {
                pause();
                file = id + ".pdf";
                Files.write(rawDir.resolve(file), fetcher.get(declaration.get()));
            }
            deputies.add(new Deputy(id, name, pageUrl, declaration.orElse(null), file));
        }
        json.writeValue(rawDir.resolve(DEPUTIES_FILE).toFile(), deputies);
        log.info("chd.lu : {} députés, {} déclarations téléchargées", deputies.size(),
                deputies.stream().filter(d -> d.file() != null).count());
        return deputies;
    }

    /** Identifiants des députés, dans l'ordre de la page, sans doublon. */
    static Set<String> deputyIds(String html) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = DEPUTY_LINK.matcher(html);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /** Nom du député, d'après le titre de sa page (« Sven Clement | Chambre des Députés… »). */
    static Optional<String> name(String html) {
        Matcher m = TITLE.matcher(html);
        return m.find() ? Optional.of(unescape(m.group(1)).trim()).filter(s -> !s.isBlank()) : Optional.empty();
    }

    /**
     * Déclaration la plus récente du député : la législature la plus récente d'abord (une déclaration sans
     * législature est plus ancienne), puis la révision la plus haute. Les déclarations d'autres députés
     * éventuellement citées sur la page sont ignorées.
     */
    static Optional<String> latestDeclaration(String html, String deputyId) {
        record Candidate(String url, int endYear, int rev) {
        }
        List<Candidate> candidates = new ArrayList<>();
        Matcher m = DECLARATION.matcher(html);
        while (m.find()) {
            if (m.group(1).equals(deputyId)) {
                candidates.add(new Candidate(m.group(), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(4))));
            }
        }
        return candidates.stream()
                .max(Comparator.comparingInt(Candidate::endYear).thenComparingInt(Candidate::rev))
                .map(Candidate::url);
    }

    private void pause() throws InterruptedException {
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
    }

    private static String text(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&#039;", "'").replace("&apos;", "'").replace("&quot;", "\"");
    }

    private static Fetcher httpFetcher() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(60))
                    .GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " sur " + url);
            }
            return response.body();
        };
    }
}
