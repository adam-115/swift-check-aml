package com.swiftcheck.aml.media;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Recherche de photos sur internet, <b>à la demande d'un analyste</b>, quand un PEP n'a pas de photo officielle.
 *
 * <p>SearXNG (image Docker, format JSON, catégorie « images ») renvoie des candidats. Chacun reçoit un <b>score de
 * contexte</b>, calculé sur le texte qui accompagne l'image (titre, description, adresse de la page) :
 * <ul>
 *   <li>tous les mots du nom présents : +50 (un candidat sans le nom est écarté) ;</li>
 *   <li>une institution de la personne citée (« Cour des comptes », « Sénat »…) : +30 ;</li>
 *   <li>site officiel (gouv.fr, public.lu, europa.eu, assemblee-nationale.fr, senat.fr…) : +20 ; Wikimedia : +10.</li>
 * </ul>
 * Ce score dit seulement que la page parle de quelqu'un qui porte ce nom et cette fonction : il ne prouve pas que le
 * visage est le sien. Rien n'est enregistré ici : une photo n'entre dans {@code pep_media} que si l'analyste la retient.
 */
@Service
public class PhotoSearchService {

    private static final Logger log = LoggerFactory.getLogger(PhotoSearchService.class);
    private static final int MAX_CANDIDATES = 8;
    private static final Pattern OFFICIAL = Pattern.compile(
            "(\\.gouv\\.fr|\\.public\\.lu|\\.gouvernement\\.lu|europa\\.eu|assemblee-nationale\\.fr|senat\\.fr"
                    + "|chd\\.lu|conseil-constitutionnel\\.fr|conseil-etat\\.fr|courdecassation\\.fr|ccomptes\\.fr"
                    + "|elysee\\.fr|banque-france\\.fr|bcl\\.lu|cssf\\.lu)$");

    @Value("${SEARXNG_URL:http://localhost:8088/search}")
    private String searxngUrl = "http://localhost:8088/search";

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper json = new ObjectMapper();
    /** Images proposées récemment (adresse → instant), pour le relais des vignettes. */
    private final Map<String, Long> shown = new ConcurrentHashMap<>();
    private static final long SHOWN_TTL_MS = 30 * 60 * 1000L;

    /** Une image candidate, avec ce qui permet à l'analyste de juger. */
    public record Candidate(String imageUrl, String thumbnailUrl, String pageUrl, String title, String domain,
            String engine, int score, List<String> reasons) {
    }

    public List<Candidate> search(String name, List<String> functions) {
        Set<String> institutions = Institutions.of(functions);
        String query = "\"" + name + "\"" + (functions.isEmpty() ? "" : " " + shortFunction(functions.get(0)));
        String url = UriComponentsBuilder.fromHttpUrl(searxngUrl).queryParam("q", query).queryParam("format", "json")
                .queryParam("categories", "images").queryParam("safesearch", "1").build().toUriString();
        List<Candidate> candidates = new ArrayList<>();
        try {
            JsonNode results = json.readTree(rest.getForObject(url, String.class)).path("results");
            for (JsonNode r : results) {
                Candidate c = score(name, institutions, r);
                if (c != null) {
                    candidates.add(c);
                }
            }
        } catch (Exception e) {
            log.warn("Recherche de photos SearXNG impossible ({}) : {}", searxngUrl, e.getMessage());
            throw new IllegalStateException("SearXNG ne répond pas : " + e.getMessage());
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed());
        List<Candidate> best = candidates.subList(0, Math.min(MAX_CANDIDATES, candidates.size()));
        best.forEach(c -> {
            shown.put(c.thumbnailUrl(), System.currentTimeMillis());
            shown.put(c.imageUrl(), System.currentTimeMillis());
        });
        return best;
    }

    /**
     * Vrai si l'adresse est celle d'une image proposée par une recherche récente : seules celles-là passent par
     * {@code /api/pep-media/thumbnail}, qui n'est donc pas un relais ouvert vers n'importe quelle adresse.
     */
    public boolean recentlyShown(String url) {
        long now = System.currentTimeMillis();
        shown.values().removeIf(t -> now - t > SHOWN_TTL_MS);
        return url != null && shown.containsKey(url);
    }

    /** Score de contexte d'un résultat ; {@code null} si le nom n'apparaît pas autour de l'image. */
    static Candidate score(String name, Set<String> institutions, JsonNode r) {
        String imageUrl = r.path("img_src").asText("");
        String pageUrl = r.path("url").asText("");
        if (imageUrl.isBlank() || !imageUrl.startsWith("http")) {
            return null;
        }
        //  …  : marques de surlignage des mots cherchés, ajoutées par SearXNG
        String context = Institutions.fold(r.path("title").asText("") + " " + r.path("content").asText("") + " "
                + pageUrl.replaceAll("[-_/.]", " ") + " " + imageUrl.replaceAll("[-_/.]", " ")).replaceAll("[\\ue000\\ue001]", "");
        for (String word : Institutions.fold(name).replaceAll("[^a-z0-9 ]+", " ").trim().split("\\s+")) {
            if (word.length() > 1 && !context.contains(word)) {
                return null;
            }
        }
        int score = 50;
        List<String> reasons = new ArrayList<>(List.of("nom présent"));
        Set<String> found = new LinkedHashSet<>();
        for (String key : institutions) {
            if (Institutions.PATTERNS.get(key).matcher(context).find()) {
                found.add(key);
            }
        }
        if (!found.isEmpty()) {
            score += 30;
            reasons.add("fonction citée (" + String.join(", ", found) + ")");
        }
        String domain = domain(pageUrl.isBlank() ? imageUrl : pageUrl);
        if (OFFICIAL.matcher(domain).find()) {
            score += 20;
            reasons.add("site officiel");
        } else if (domain.endsWith("wikimedia.org") || domain.endsWith("wikipedia.org")) {
            score += 10;
            reasons.add("Wikimedia");
        }
        String thumbnail = r.path("thumbnail_src").asText("");
        if (!thumbnail.startsWith("http")) {
            thumbnail = imageUrl;               // vignette absente ou relative au proxy de SearXNG
        }
        return new Candidate(imageUrl, thumbnail, pageUrl, r.path("title").asText(""),
                domain, r.path("engine").asText(""), score, reasons);
    }

    private static String shortFunction(String function) {
        String f = function.replaceAll("(?i)^ancien\\s*:\\s*", "");
        return f.length() > 60 ? f.substring(0, 60) : f;
    }

    private static String domain(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase().replaceFirst("^www\\.", "");
        } catch (RuntimeException e) {
            return "";
        }
    }
}
