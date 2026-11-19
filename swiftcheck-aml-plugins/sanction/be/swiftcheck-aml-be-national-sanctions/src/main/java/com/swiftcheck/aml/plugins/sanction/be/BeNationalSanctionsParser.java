package com.swiftcheck.aml.plugins.sanction.be;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.Dates;
import com.swiftcheck.aml.plugins.sanction.common.Http;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.plugins.sanction.common.XlsxReader;
import com.swiftcheck.aml.sdk.StealthBrowserFetcher;

/**
 * Liste nationale belge des personnes et entités soupçonnées de terrorisme dont les avoirs sont gelés (arrêté royal
 * du 28 décembre 2006, établie par le Conseil national de sécurité), publiée par le SPF Finances (Trésorerie) en XLSX.
 *
 * <p>Source {@code PLUGIN_MANAGED} : le nom du fichier change à chaque arrêté royal ; le plugin lit la page
 * (paramètre {@code pageUrl}) et télécharge le classeur « Liste nationale ». Le site est protégé par un défi
 * anti-robot (F5) : si la réponse directe n'est pas la page ou le classeur attendus, le service navigateur du serveur
 * ({@link StealthBrowserFetcher}) est utilisé. Clé stable : numéro de registre national.
 */
@Extension
public class BeNationalSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "be-national";
    static final String DEFAULT_PAGE =
            "https://finances.belgium.be/fr/tresorerie/sanctions-financieres/sanctions-financi%C3%A8res-nationales";
    static final String RAW_FILE = "liste-nationale.xlsx";
    private static final Pattern XLSX_LINK = Pattern.compile("href=\"([^\"]*Liste(?:%20|\\s|_|-)nationale[^\"]*\\.xlsx)\"",
            Pattern.CASE_INSENSITIVE);

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "SPF Finances - Trésorerie (Belgique)";
    }

    @Override
    protected String[] topics() {
        return new String[] { "sanction", "crime.terror" };
    }

    @Override
    protected void download(Map<String, String> parameters, Path rawDir) throws Exception {
        String page = parameters.getOrDefault("pageUrl", DEFAULT_PAGE);
        Files.createDirectories(rawDir);
        String html = new String(Http.get(page), StandardCharsets.UTF_8);
        // Défi anti-robot : le service navigateur renvoie parfois encore la page du défi au premier essai.
        for (int attempt = 1; xlsxLink(html) == null && attempt <= 3; attempt++) {
            html = StealthBrowserFetcher.fetchHtml(page);
        }
        Files.writeString(rawDir.resolve("page.html"), html);
        String link = xlsxLink(html);
        if (link == null) {
            throw new IOException("Lien « Liste nationale » (.xlsx) introuvable sur " + page);
        }
        String url = URI.create(page).resolve(link.replace(" ", "%20")).toString();
        byte[] xlsx = Http.get(url);
        if (!isZip(xlsx)) {
            xlsx = StealthBrowserFetcher.downloadBytes(url);
        }
        if (!isZip(xlsx)) {
            throw new IOException("Le fichier téléchargé n'est pas un classeur .xlsx : " + url);
        }
        Files.write(rawDir.resolve(RAW_FILE), xlsx);
    }

    static String xlsxLink(String html) {
        Matcher m = XLSX_LINK.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isZip(byte[] bytes) {
        return bytes != null && bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    @Override
    protected List<SanctionRecord> readRaw(Map<String, String> parameters, Path rawDir) throws Exception {
        return read(rawDir.resolve(RAW_FILE));
    }

    @Override
    protected List<SanctionRecord> read(Path xlsx) throws Exception {
        List<SanctionRecord> records = new ArrayList<>();
        for (Map<String, String> row : XlsxReader.rows(XlsxReader.firstSheet(xlsx), "Nom")) {
            String lastName = row.get("Nom");
            if (lastName == null) {
                continue; // ligne vide (personne retirée de la liste, numéro conservé)
            }
            String firstName = row.get("Prénom(s)");
            String registry = row.entrySet().stream().filter(e -> e.getKey().startsWith("N° Registre"))
                    .map(Map.Entry::getValue).filter(v -> v != null).findFirst().orElse(null);
            String birth = Dates.one(XlsxReader.excelDate(row.get("Date de naissance")));
            String key = registry != null ? registry.replaceAll("\\D", "") : joinNonBlank("-", lastName, firstName, birth);
            SanctionRecord r = new SanctionRecord("Person", key).sourceRef(joinNonBlank(" ", lastName, firstName));
            r.add("name", joinNonBlank(" ", firstName, lastName));
            r.add("firstName", firstName);
            r.add("lastName", lastName);
            r.add("birthDate", birth);
            r.add("idNumber", registry); // numéro de registre national belge
            r.sanction("program", "Arrêté royal du 28 décembre 2006 (financement du terrorisme)")
                    .sanction("listingDate", Dates.one(XlsxReader.excelDate(row.get("Date AR"))))
                    .sanction("startDate", Dates.one(XlsxReader.excelDate(row.get("Entrée en vigueur"))))
                    .sanction("modifiedAt", Dates.one(XlsxReader.excelDate(row.get("Dernière confirmation"))))
                    .sanction("sourceUrl", row.get("lien vers la publication"));
            records.add(r);
        }
        return records;
    }
}
