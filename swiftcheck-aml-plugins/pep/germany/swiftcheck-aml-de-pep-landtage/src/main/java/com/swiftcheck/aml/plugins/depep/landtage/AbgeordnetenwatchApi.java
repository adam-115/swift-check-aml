package com.swiftcheck.aml.plugins.depep.landtage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lecture de l'API v2 d'abgeordnetenwatch.de (licence CC0) : parlements, législatures, mandats et années de naissance.
 * Les méthodes de lecture sont pures (elles prennent le JSON en texte) pour être testées sur des réponses réelles.
 */
final class AbgeordnetenwatchApi {

    static final String DEFAULT_ENDPOINT = "https://www.abgeordnetenwatch.de/api/v2";
    static final int PAGE_SIZE = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    private AbgeordnetenwatchApi() {
    }

    /** Accès HTTP, remplaçable dans les tests. */
    interface Fetcher {
        String get(String url) throws IOException;
    }

    record Parliament(long id, String label, String longLabel, long currentPeriodId) {
    }

    record Period(long id, String label, String type, LocalDate start, LocalDate end) {
    }

    record Mandate(long id, long politicianId, String name, String profileUrl, String parliamentKey, long periodId,
                   String periodLabel, LocalDate start, LocalDate end, LocalDate periodEnd, String party,
                   String constituency) {
    }

    record Page(List<JsonNode> items, int total) {
    }

    static List<Parliament> parliaments(String json) throws IOException {
        List<Parliament> list = new ArrayList<>();
        for (JsonNode n : data(json)) {
            list.add(new Parliament(n.path("id").asLong(), n.path("label").asText(), n.path("label_external_long").asText(n.path("label").asText()),
                    n.path("current_project").path("id").asLong(0)));
        }
        return list;
    }

    static List<Period> periods(String json) throws IOException {
        List<Period> list = new ArrayList<>();
        for (JsonNode n : data(json)) {
            list.add(new Period(n.path("id").asLong(), n.path("label").asText(), n.path("type").asText(),
                    date(n.path("start_date_period")), date(n.path("end_date_period"))));
        }
        return list;
    }

    /**
     * Législatures à lire : celles qui ont commencé et qui sont en cours ou terminées depuis moins de
     * {@code monthsAfterEnd} mois (un député sorti à la fin de la législature reste PEP pendant ce délai).
     */
    static List<Period> activePeriods(List<Period> periods, LocalDate today, int monthsAfterEnd) {
        LocalDate cutoff = today.minusMonths(monthsAfterEnd);
        List<Period> list = new ArrayList<>();
        for (Period p : periods) {
            if (!"legislature".equals(p.type()) || p.start() == null || p.start().isAfter(today)) {
                continue;
            }
            if (p.end() == null || !p.end().isBefore(cutoff)) {
                list.add(p);
            }
        }
        return list;
    }

    static Page mandatePage(String json) throws IOException {
        JsonNode root = JSON.readTree(json);
        List<JsonNode> items = new ArrayList<>();
        root.path("data").forEach(items::add);
        return new Page(items, root.path("meta").path("result").path("total").asInt(items.size()));
    }

    static Mandate mandate(JsonNode n, String parliamentKey, Period period) {
        String party = null;
        LocalDate bestFrom = null;
        for (JsonNode f : n.path("fraction_membership")) {
            LocalDate from = date(f.path("valid_from"));
            boolean open = f.path("valid_until").isNull() || f.path("valid_until").isMissingNode();
            if (party == null || open || (bestFrom != null && from != null && from.isAfter(bestFrom))) {
                party = f.path("fraction").path("label").asText(null);
                bestFrom = from;
                if (open) {
                    break;
                }
            }
        }
        if (party != null) {
            party = party.replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
        }
        String constituency = n.path("electoral_data").path("constituency").path("label").asText(null);
        if (constituency != null) {
            constituency = constituency.replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
        }
        return new Mandate(n.path("id").asLong(), n.path("politician").path("id").asLong(),
                n.path("politician").path("label").asText(), n.path("politician").path("abgeordnetenwatch_url").asText(null),
                parliamentKey, period.id(), period.label(), date(n.path("start_date")), date(n.path("end_date")),
                period.end(), party, constituency);
    }

    static Integer birthYear(String json) throws IOException {
        JsonNode y = JSON.readTree(json).path("data").path("year_of_birth");
        return y.isInt() && y.asInt() > 1900 ? y.asInt() : null;
    }

    private static JsonNode data(String json) throws IOException {
        JsonNode root = JSON.readTree(json);
        if (!"ok".equals(root.path("meta").path("status").asText("ok"))) {
            throw new IOException("Réponse en erreur de l'API : " + root.path("meta").path("status_message").asText());
        }
        return root.path("data");
    }

    private static LocalDate date(JsonNode n) {
        return n.isTextual() && !n.asText().isBlank() ? LocalDate.parse(n.asText().substring(0, 10)) : null;
    }

    /** Client HTTP poli : User-Agent générique, pause entre deux appels, trois essais sur 429 et 5xx. */
    static Fetcher http(String userAgent, long delayMs) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return url -> {
            IOException last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    Thread.sleep(delayMs * attempt);
                    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                            .header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
                    HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (resp.statusCode() == 200) {
                        return resp.body();
                    }
                    last = new IOException("HTTP " + resp.statusCode() + " pour " + url);
                    if (resp.statusCode() < 429 && resp.statusCode() != 408) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrompu", e);
                } catch (IOException e) {
                    last = e;
                }
            }
            throw last;
        };
    }
}
