package com.swiftcheck.aml.plugins.frpep.euinstitutions;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Les pages officielles lues et leur téléchargement. Chaque réponse est déposée dans {@code rawDir} sous un nom fixe
 * (les CV des membres du directoire de la BCE : {@code bce-cv-<nom>.html}), où le core la signe et l'archive.
 */
class EuSources {

    private static final Logger log = LoggerFactory.getLogger(EuSources.class);

    static final String CJ_FILE = "cjue-cour.html";
    static final String GC_FILE = "cjue-tribunal.html";
    static final String ECA_FILE = "eca-composition.pdf";
    static final String ECB_BOARD_FILE = "bce-directoire.html";
    static final String ECB_CV_PREFIX = "bce-cv-";
    static final String SSM_FILE = "bce-surveillance.html";
    static final String COUNCIL_SG_FILE = "conseil-sg.html";
    static final String COUNCIL_SG_BIO_FILE = "conseil-sg-biographie.html";

    /** Adresses par défaut, chacune remplaçable par un paramètre du même nom. */
    static final Map<String, String> DEFAULT_URLS = Map.of(
            "cjUrl", "https://curia.europa.eu/jcms/jcms/Jo2_7026/fr/",
            "gcUrl", "https://curia.europa.eu/jcms/jcms/Jo2_7035/fr/",
            "ecaUrl", "https://www.eca.europa.eu/ContentPagesDocuments/Organisation/COMPOSITION_EN.pdf",
            "ecbBoardUrl", "https://www.ecb.europa.eu/ecb/orga/decisions/eb/html/index.fr.html",
            "ssmUrl", "https://www.bankingsupervision.europa.eu/about/organisation/leadership/supervisoryboard/html/index.en.html",
            "councilSgUrl", "https://www.consilium.europa.eu/fr/general-secretariat/secretary-general/",
            "councilSgBioUrl", "https://www.consilium.europa.eu/fr/general-secretariat/secretary-general/biography/");

    /** Lien du CV d'un membre du directoire dans la page de la BCE : {@code /ecb/decisions/html/cvlagarde.fr.html}. */
    static final Pattern ECB_CV_LINK = Pattern.compile("href=\"([^\"]*/cv([a-z]+)\\.fr\\.html)\"");

    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - institutions de l'UE)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static String url(Map<String, String> parameters, String key) {
        String value = parameters.get(key);
        return value == null || value.isBlank() ? DEFAULT_URLS.get(key) : value.trim();
    }

    /** @return les sources non téléchargées (« fichier : raison ») ; les autres sont lues quand même */
    List<String> download(Map<String, String> p, long delayMs, Path rawDir) throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        List<String> failed = new ArrayList<>();
        fetch(url(p, "cjUrl"), rawDir.resolve(CJ_FILE), failed, 0);
        fetch(url(p, "gcUrl"), rawDir.resolve(GC_FILE), failed, delayMs);
        fetch(url(p, "ecaUrl"), rawDir.resolve(ECA_FILE), failed, delayMs);
        if (fetch(url(p, "ecbBoardUrl"), rawDir.resolve(ECB_BOARD_FILE), failed, delayMs)) {
            String board = Files.readString(rawDir.resolve(ECB_BOARD_FILE), StandardCharsets.UTF_8);
            for (String[] cv : ecbCvLinks(board, url(p, "ecbBoardUrl"))) {
                fetch(cv[1], rawDir.resolve(ECB_CV_PREFIX + cv[0] + ".html"), failed, delayMs);
            }
        }
        fetch(url(p, "ssmUrl"), rawDir.resolve(SSM_FILE), failed, delayMs);
        fetch(url(p, "councilSgUrl"), rawDir.resolve(COUNCIL_SG_FILE), failed, delayMs);
        fetch(url(p, "councilSgBioUrl"), rawDir.resolve(COUNCIL_SG_BIO_FILE), failed, delayMs);
        log.info("Pages des institutions de l'UE téléchargées, {} en échec", failed.size());
        return failed;
    }

    /** CV cités par la page du directoire : {nom court, adresse absolue}, sans doublon. */
    static List<String[]> ecbCvLinks(String html, String pageUrl) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> links = new ArrayList<>();
        Matcher m = ECB_CV_LINK.matcher(html);
        while (m.find()) {
            if (seen.add(m.group(2))) {
                links.add(new String[] { m.group(2), URI.create(pageUrl).resolve(m.group(1)).toString() });
            }
        }
        return links;
    }

    private boolean fetch(String url, Path target, List<String> failed, long delayMs) throws InterruptedException {
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                failed.add(target.getFileName() + " : HTTP " + response.statusCode());
                return false;
            }
            Files.write(target, response.body());
            return true;
        } catch (IOException e) {
            failed.add(target.getFileName() + " : " + e.getMessage());
            return false;
        }
    }
}
