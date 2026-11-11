package com.swiftcheck.aml.plugins.bepep.localexecutives;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Mandats locaux déclarés à la Cour des comptes (Regimand, {@code https://public.regimand.be}) : bourgmestres,
 * gouverneurs, échevins, présidents de CPAS et députés provinciaux des communes et provinces retenues.
 *
 * <p>Regimand ne se télécharge pas en bloc : l'API {@code Statement/PublicSearch} répond à une recherche par nom
 * d'institution. On interroge donc chaque collectivité par son nom, puis on garde les fonctions assujetties
 * ({@code isLiable}) exercées dans cette collectivité.
 */
final class RegimandLocal {

    static final String DEFAULT_URL = "https://public.regimand.be";
    static final String SOURCE_URL = "https://public.regimand.be";
    private static final String USER_AGENT = "SwiftCheck-AML/1.0 (liste PEP Belgique - mandats locaux Regimand)";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Fonctions locales retenues (nom français ou néerlandais, sans accent, en minuscules). */
    private static final Pattern ROLE = Pattern.compile(
            "^(bourgmestre|burgemeester|gouverneur|echevin|echevine|schepen|president de cpas|presidente de cpas|voorzitter van het bijzonder comit[eé]|voorzitter van het ocmw|voorzitter van het vast bureau|depute provincial|deputee provinciale|gedeputeerde|president du college provincial|president du conseil provincial|voorzitter van de provincieraad|voorzitter van het provinciecollege)"
                    + "(?! de district)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Un mandat local déclaré. */
    record Mandate(String profileId, String firstName, String lastName, String role, String institution,
            String start, String end) {
        String fullName() {
            return (firstName + " " + lastName).trim();
        }
    }

    private RegimandLocal() {
    }

    /** Lecture d'une API Regimand ; remplacée dans les tests. */
    interface Client {
        /** Déclarations de l'exercice dont l'institution contient {@code institutionName}. */
        String search(String institutionName) throws IOException, InterruptedException;
    }

    /** Client HTTP : jeton invité, dernier exercice publié, puis une recherche par institution. */
    static Client httpClient(String baseUrl) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        String token = send(http, HttpRequest.newBuilder(URI.create(baseUrl + "/api/Token/guest")).GET()).replace("\"", "")
                .trim();
        int year = latestYear(http, baseUrl, token);
        return name -> {
            String body = JSON.writeValueAsString(Map.of("year", year, "institutionName", name));
            return send(http, HttpRequest.newBuilder(URI.create(baseUrl + "/api/Statement/PublicSearch"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
        };
    }

    private static int latestYear(HttpClient http, String baseUrl, String token) throws IOException, InterruptedException {
        String years = send(http, HttpRequest.newBuilder(URI.create(baseUrl + "/api/TenureMandatary/Years"))
                .header("Authorization", "Bearer " + token).GET());
        int max = 0;
        for (JsonNode y : JSON.readTree(years)) {
            if (y.isInt()) {
                max = Math.max(max, y.asInt());
            }
        }
        if (max == 0) {
            throw new IOException("Regimand : aucun exercice publié");
        }
        return max;
    }

    private static String send(HttpClient http, HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request.timeout(Duration.ofSeconds(90))
                .header("User-Agent", USER_AGENT).header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Regimand HTTP " + response.statusCode() + " : " + response.request().uri().getPath());
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /** Les mandats locaux d'une collectivité dans une réponse Regimand (tableau de déclarations). */
    static List<Mandate> select(JsonNode statements, boolean province, List<String> labels, LocalDate since) {
        Map<String, Mandate> found = new LinkedHashMap<>();
        for (JsonNode statement : statements) {
            JsonNode profile = statement.path("profile");
            String profileId = profile.path("id").asText("");
            for (JsonNode tenure : statement.path("tenures")) {
                if (!tenure.path("isLiable").asBoolean(false)) {
                    continue;
                }
                String role = name(tenure.path("functionType").path("translations"));
                String institution = name(tenure.path("institution").path("translations"));
                if (!ROLE.matcher(fold(role)).find() || !matches(fold(institution), province, labels)) {
                    continue;
                }
                String start = date(tenure.path("startDate").asText(null));
                String end = date(tenure.path("endDate").asText(null));
                if (end != null && LocalDate.parse(end).isBefore(since)) {
                    continue;
                }
                Mandate m = new Mandate(profileId, profile.path("firstName").asText("").trim(),
                        profile.path("lastName").asText("").trim(),
                        role.replaceAll("(?i)\\s*\\((titulaire ou f\\.f\\.|titelvoerend of wnd\\.?|titularis of wnd\\.?)\\)", ""),
                        institution.trim(), start, end);
                if (m.fullName().isBlank() || profileId.isBlank()) {
                    continue;
                }
                // Une même fonction peut figurer dans plusieurs exercices : on garde la plus récente
                String key = profileId + "|" + fold(m.role()) + "|" + fold(institution);
                Mandate previous = found.get(key);
                if (previous == null || later(m, previous)) {
                    found.put(key, m);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    private static boolean later(Mandate a, Mandate b) {
        String x = a.end() == null ? "9999-12-31" : a.end();
        String y = b.end() == null ? "9999-12-31" : b.end();
        return x.compareTo(y) > 0;
    }

    /** Institution « Charleroi (ville de) », « Anderlecht (commune d') » ou « Province de Liège ». */
    static boolean matches(String institution, boolean province, List<String> labels) {
        String inst = institution.trim();
        for (String label : labels) {
            String key = fold(label);
            if (key.isBlank()) {
                continue;
            }
            if (province) {
                String bare = key.replaceFirst("^(province|provincie)\\s+(de |du |des |d'|van )?", "");
                if (inst.matches("^(province|provincie)\\b.*") && inst.contains(bare)) {
                    return true;
                }
            } else if (inst.matches("^" + Pattern.quote(key) + "\\s*\\((commune|ville|gemeente|stad)\\b.*")
                    || inst.matches("^(commune|ville|gemeente|stad)\\s*(de |d'|van )?\\b" + Pattern.quote(key) + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private static String name(JsonNode translations) {
        String fr = null;
        String nl = null;
        String any = null;
        for (JsonNode t : translations) {
            String n = t.path("name").asText("");
            if (n.isBlank()) {
                continue;
            }
            String lang = t.path("languageCode").asText("");
            if ("FR".equalsIgnoreCase(lang)) {
                fr = n;
            } else if ("NL".equalsIgnoreCase(lang)) {
                nl = n;
            }
            if (any == null) {
                any = n;
            }
        }
        return fr != null ? fr : nl != null ? nl : any != null ? any : "";
    }

    private static String date(String raw) {
        return raw == null || raw.length() < 10 || "null".equals(raw) ? null : raw.substring(0, 10);
    }

    static String fold(String s) {
        return java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").trim();
    }
}
