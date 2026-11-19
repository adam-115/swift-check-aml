package com.swiftcheck.aml.plugins.sanction.pl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;

/**
 * Liste polonaise des personnes et entités sanctionnées (ministre de l'Intérieur et de l'Administration, MSWiA),
 * tenue en application de la loi du 13 avril 2022 sur les mesures contre le soutien à l'agression contre l'Ukraine.
 * Publiée comme page HTML sur gov.pl : un tableau des personnes (« Nazwisko i imię ») et un tableau des entités
 * (« Nazwa podmiotu »). Une ligne avec une date de radiation (« Data wykreślenia z listy ») n'est pas reprise.
 *
 * <p>Pas de numéro de fiche : la clé est le nom tel qu'écrit dans la liste.
 */
@Extension
public class PlMswiaSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "pl-mswia";
    static final String PAGE = "https://www.gov.pl/web/mswia/lista-osob-i-podmiotow-objetych-sankcjami";

    private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("stycznia", 1), Map.entry("lutego", 2),
            Map.entry("marca", 3), Map.entry("kwietnia", 4), Map.entry("maja", 5), Map.entry("czerwca", 6),
            Map.entry("lipca", 7), Map.entry("sierpnia", 8), Map.entry("września", 9), Map.entry("października", 10),
            Map.entry("listopada", 11), Map.entry("grudnia", 12));
    private static final Pattern BIRTH_WORDS = Pattern.compile("(\\d{1,2})\\s+([a-ząćęłńóśźż]+)\\s+(\\d{4})");
    private static final Pattern BIRTH_DOTS = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");
    private static final Pattern KRS = Pattern.compile("KRS:?\\s*(\\d{6,10})");
    private static final Pattern NIP = Pattern.compile("NIP:?\\s*([\\d-]{10,13})");
    private static final Pattern PESEL = Pattern.compile("PESEL:?\\s*(\\d{11})");

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "Minister of the Interior and Administration of Poland (MSWiA)";
    }

    @Override
    protected List<SanctionRecord> read(Path input) throws Exception {
        Document doc = Jsoup.parse(input.toFile(), null, PAGE);
        List<SanctionRecord> records = new ArrayList<>();
        for (Element table : doc.select("table")) {
            Element header = table.selectFirst("tr");
            if (header == null) {
                continue;
            }
            String first = header.select("td, th").isEmpty() ? "" : header.select("td, th").get(0).text();
            boolean persons = first.startsWith("Nazwisko");
            boolean entities = first.startsWith("Nazwa podmiotu");
            if (!persons && !entities) {
                continue;
            }
            for (Element tr : table.select("tr")) {
                List<String> cells = tr.select("td").eachText();
                if (cells.size() < 5 || cells.get(0).isBlank() || cells.get(0).equals(first)) {
                    continue;
                }
                if (cells.size() > 5 && !cells.get(5).isBlank()) {
                    continue; // radiée
                }
                records.add(convert(cells, persons));
            }
        }
        return records;
    }

    private static SanctionRecord convert(List<String> cells, boolean person) {
        String rawName = cells.get(0).trim();
        String identity = cells.get(1);
        // Tableau dans la clé : un même nom peut figurer parmi les personnes et parmi les entités
        SanctionRecord r = new SanctionRecord(person ? "Person" : "Organization", person ? "p" : "e", rawName)
                .sourceRef(rawName);
        // « BELOV Alexey (BELOV Alexy) » : la parenthèse est une autre graphie
        Matcher alias = Pattern.compile("\\(([^)]+)\\)").matcher(rawName);
        String name = rawName.replaceAll("\\s*\\([^)]*\\)", "").trim();
        r.add("name", name);
        while (alias.find()) {
            r.add("alias", alias.group(1));
        }
        if (person) {
            r.add("birthDate", birthDate(identity));
            Matcher pesel = PESEL.matcher(identity);
            if (pesel.find()) {
                r.add("idNumber", pesel.group(1));
            }
        } else {
            Matcher krs = KRS.matcher(identity);
            if (krs.find()) {
                r.add("registrationNumber", krs.group(1));
            }
            Matcher nip = NIP.matcher(identity);
            if (nip.find()) {
                r.add("taxNumber", nip.group(1));
            }
            r.add("address", identity.replaceFirst("(?i)^siedziba:\\s*", ""));
        }
        r.add("notes", identity);
        r.sanction("program", "Ustawa z dnia 13 kwietnia 2022 r. (agresja na Ukrainę)")
                .sanction("reason", cells.get(2))
                .sanction("provisions", cells.get(3))
                .sanction("listingDate", cells.get(4))
                .sanction("sourceUrl", PAGE);
        return r;
    }

    /** « urodzony 5 października 1973 r. » → 1973-10-05 ; « urodzona 16.10.1975 r. » → 1975-10-16. */
    static String birthDate(String text) {
        if (text == null) {
            return null;
        }
        Matcher w = BIRTH_WORDS.matcher(text);
        if (w.find() && MONTHS.containsKey(w.group(2))) {
            return String.format("%s-%02d-%02d", w.group(3), MONTHS.get(w.group(2)), Integer.parseInt(w.group(1)));
        }
        Matcher d = BIRTH_DOTS.matcher(text);
        if (d.find()) {
            return String.format("%s-%02d-%02d", d.group(3), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(1)));
        }
        return null;
    }
}
