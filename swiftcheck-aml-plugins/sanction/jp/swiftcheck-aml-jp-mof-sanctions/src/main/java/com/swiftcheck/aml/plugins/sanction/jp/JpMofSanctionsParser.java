package com.swiftcheck.aml.plugins.sanction.jp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.Http;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.sdk.CsvRecordReader;
import com.swiftcheck.aml.sdk.CsvRow;

/**
 * Liste consolidée des personnes et entités visées par le gel des avoirs au Japon (ministère des Finances, loi sur
 * les changes et le commerce extérieur) : fichier CSV {@code shisantouketsuAAAAMMJJ.csv} publié sur la page
 * « 経済制裁措置及び対象者リスト ».
 *
 * <p>Source {@code PLUGIN_MANAGED} : le nom du fichier change à chaque mise à jour ; le plugin lit la page
 * (paramètre {@code pageUrl}), y trouve le lien du CSV le plus récent et l'enregistre dans {@code rawDir}.
 * Le numéro de fiche ({@code 番号}, ex. {@code 002-000158}) est la clé stable.
 */
@Extension
public class JpMofSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "jp-mof";
    static final String DEFAULT_PAGE =
            "https://www.mof.go.jp/policy/international_policy/gaitame_kawase/gaitame/economic_sanctions/list.html";
    static final String RAW_FILE = "shisantouketsu.csv";
    private static final Pattern CSV_LINK = Pattern.compile("href=\"([^\"]*shisantouketsu\\d+\\.csv)\"");
    private static final Pattern JP_YEAR = Pattern.compile("(\\d{4})年");

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "Ministry of Finance of Japan";
    }

    @Override
    protected void download(Map<String, String> parameters, Path rawDir) throws Exception {
        String page = parameters.getOrDefault("pageUrl", DEFAULT_PAGE);
        String html = Http.getString(page);
        Files.createDirectories(rawDir);
        Files.writeString(rawDir.resolve("list.html"), html);
        String link = csvLink(html);
        if (link == null) {
            throw new IOException("Lien shisantouketsu*.csv introuvable sur " + page);
        }
        Http.save(java.net.URI.create(page).resolve(link).toString(), rawDir, RAW_FILE);
    }

    /** Lien du CSV le plus récent (date la plus grande dans le nom). */
    static String csvLink(String html) {
        String best = null;
        Matcher m = CSV_LINK.matcher(html);
        while (m.find()) {
            if (best == null || m.group(1).replaceAll("\\D", "").compareTo(best.replaceAll("\\D", "")) > 0) {
                best = m.group(1);
            }
        }
        return best;
    }

    @Override
    protected List<SanctionRecord> readRaw(Map<String, String> parameters, Path rawDir) throws Exception {
        return read(rawDir.resolve(RAW_FILE));
    }

    @Override
    protected List<SanctionRecord> read(Path csv) throws Exception {
        List<SanctionRecord> records = new ArrayList<>();
        CsvRecordReader.readEach(csv, row -> {
            String number = row.get("番号");
            String name = row.get("氏名（英語）");
            if (number != null && (name != null || row.get("氏名（日本語）") != null)) {
                records.add(convert(row, number));
            }
        });
        return records;
    }

    private static SanctionRecord convert(CsvRow row, String number) {
        boolean person = "個人".equals(row.get("個人・団体"));
        SanctionRecord r = new SanctionRecord(person ? "Person" : "Organization", number).sourceRef("番号 " + number);
        r.add("name", row.get("氏名（英語）"));
        r.add("name", row.get("氏名（日本語）")); // nom principal si l'anglais manque, sinon alias
        split(row.get("氏名（日本語）")).forEach(v -> r.add("alias", v));
        split(row.get("別名・別称（英語）")).forEach(v -> r.add("alias", v));
        split(row.get("別名・別称（日本語）")).forEach(v -> r.add("alias", v));
        split(row.get("旧称（英語）")).forEach(v -> r.add("previousName", v));
        split(row.get("旧称（日本語）")).forEach(v -> r.add("previousName", v));
        split(row.get("確定に十分でない別名（英語）")).forEach(v -> r.add("weakAlias", v));
        split(row.get("確定に十分でない別名（日本語）")).forEach(v -> r.add("weakAlias", v));
        if (person) {
            split(row.get("称号（英語）")).forEach(v -> r.add("title", v));
            split(row.get("役職（英語）")).forEach(v -> r.add("position", v));
            split(row.get("役職（日本語）")).forEach(v -> r.add("position", v));
            split(row.get("生年月日")).forEach(v -> r.add("birthDate", birthDate(v)));
            split(row.get("出生地（英語）")).forEach(v -> r.add("birthPlace", v));
            split(row.get("国籍（英語）")).forEach(v -> r.add("nationality", v));
            split(row.get("旅券番号")).forEach(v -> r.add("passportNumber", v));
            split(row.get("身分証番号")).forEach(v -> r.add("idNumber", v));
        } else {
            split(row.get("役職（英語）")).forEach(v -> r.add("notes", v));
        }
        List<String> countries = split(row.get("住所・所在地（国）（英語）"));
        countries.forEach(v -> r.add("country", v));
        List<String> details = split(row.get("住所・所在地（都市その他の情報）（英語）"));
        for (int i = 0; i < Math.max(countries.size(), details.size()); i++) {
            String detail = i < details.size() ? details.get(i) : null;
            String country = i < countries.size() ? countries.get(i) : null;
            r.add("address", detail == null ? country : country == null ? detail : detail + ", " + country);
        }
        r.add("notes", row.get("その他の情報"));

        String dates = row.get("告示日付");
        r.sanction("program", "Japan MOF category " + row.get("区分"))
                .sanction("authorityId", number)
                .sanction("unscId", row.get("国連参照番号"))
                .sanction("listingDate", dates == null ? null : dates.split("\\s+")[0].replace('.', '-'))
                .sanction("provisions", row.get("告示番号") == null ? null : "Notice No. " + row.get("告示番号"))
                .sanction("summary", row.get("外務省告示情報"))
                .sanction("sourceUrl", DEFAULT_PAGE);
        return r;
    }

    /** « 1941/8/20 » → 1941-08-20 ; « 1970年 », « 1970年頃 » → 1970 ; « 1955年から1958年の間 » → 1955. */
    static String birthDate(String raw) {
        String s = raw.trim();
        Matcher ymd = Pattern.compile("(\\d{4})/(\\d{1,2})/(\\d{1,2})").matcher(s);
        if (ymd.find()) {
            return String.format("%s-%02d-%02d", ymd.group(1), Integer.parseInt(ymd.group(2)), Integer.parseInt(ymd.group(3)));
        }
        Matcher y = JP_YEAR.matcher(s);
        if (y.find()) {
            return y.group(1);
        }
        return s.matches("\\d{4}") ? s : s; // illisible : gardé en note par la conversion commune
    }

    private static List<String> split(String raw) {
        if (raw == null) {
            return List.of();
        }
        return Arrays.stream(raw.split("[;；]")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
