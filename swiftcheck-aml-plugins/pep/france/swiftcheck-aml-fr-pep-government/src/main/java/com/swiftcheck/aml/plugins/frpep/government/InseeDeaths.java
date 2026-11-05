package com.swiftcheck.aml.plugins.frpep.government;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contrôle de décès d'après le fichier des personnes décédées de l'INSEE (data.gouv.fr, Licence Ouverte), interrogé
 * par le service public de recherche {@code deces.matchid.io}.
 *
 * <p>Pourquoi : les listes de titulaires (HATVP, Parlement européen) ne disent pas qui est mort. Or une personne décédée
 * n'est plus une personne à surveiller : l'historique au-delà de 12 mois ne peut être publié que pour des personnes
 * dont on a vérifié qu'elles ne figurent pas au fichier des décès.
 *
 * <p>Une personne est reconnue si la date de naissance est identique et le nom concorde (score de nom d'au moins
 * 0,9). Sans date de naissance, la vérification est impossible : l'appelant n'élargit pas l'historique pour elle.
 */
final class InseeDeaths {

    static final String DEFAULT_URL = "https://deces.matchid.io/deces/api/v1/search";
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP France - controle des deces INSEE)";
    private static final DateTimeFormatter DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Vérifie un décès ; remplacé dans les tests. */
    interface Checker {
        /**
         * @return vrai si la personne figure au fichier des décès, faux sinon
         * @throws IOException si le service ne répond pas : la personne n'est alors pas publiée
         */
        boolean isDeceased(String firstName, String lastName, LocalDate birthDate) throws IOException, InterruptedException;
    }

    private InseeDeaths() {
    }

    /**
     * @param token jeton du service (en-tête {@code Authorization: Bearer}), à placer dans la configuration du parser,
     *              jamais dans le dépôt ; sans jeton, le service limite l'usage anonyme (HTTP 422 une fois le quota épuisé)
     */
    static Checker http(String baseUrl, long delayMs, String token) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        return (firstName, lastName, birthDate) -> {
            String url = baseUrl + "?firstName=" + URLEncoder.encode(firstName, StandardCharsets.UTF_8)
                    + "&lastName=" + URLEncoder.encode(lastName, StandardCharsets.UTF_8)
                    + "&birthDate=" + URLEncoder.encode(birthDate.format(DAY_MONTH_YEAR), StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", USER_AGENT).header("Accept", "application/json");
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token.trim());
            }
            HttpRequest request = builder.GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            Thread.sleep(delayMs);
            if (response.statusCode() != 200) {
                throw new IOException("Contrôle des décès INSEE : HTTP " + response.statusCode()
                        + (response.statusCode() == 422 ? " (quota anonyme épuisé : renseigner deathCheckToken)" : ""));
            }
            return deceased(JSON.readTree(response.body()));
        };
    }

    /** Vrai si la réponse contient une personne de même naissance et de même nom, avec une date de décès. */
    static boolean deceased(JsonNode response) {
        for (JsonNode person : response.path("response").path("persons")) {
            JsonNode scores = person.path("scores");
            boolean sameBirth = scores.path("birthDate").asDouble(0) >= 1.0;
            boolean sameName = scores.path("name").path("score").asDouble(0) >= 0.9;
            if (sameBirth && sameName && !person.path("death").path("date").asText("").isBlank()) {
                return true;
            }
        }
        return false;
    }
}
