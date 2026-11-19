package com.swiftcheck.aml.plugins.sanction.ustrade;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.Dates;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.sdk.CsvRecordReader;
import com.swiftcheck.aml.sdk.CsvRow;

/**
 * Consolidated Screening List (CSL) de l'International Trade Administration : onze listes américaines de
 * contrôle des exportations et de sanctions en un seul fichier CSV
 * ({@code data.trade.gov/downloadable_consolidated_screening_list/v1/consolidated.csv}).
 *
 * <ul>
 * <li>Bureau of Industry and Security : Entity List, Denied Persons List, Unverified List, Military End User ;</li>
 * <li>State Department : ITAR Debarred (DTC), Nonproliferation Sanctions (ACN) ;</li>
 * <li>Trésor (OFAC) : SDN, SSI, NS-MBS, CMIC, PLC, CAPTA.</li>
 * </ul>
 *
 * <p>Les listes du Trésor sont déjà lues par le plugin OFAC : elles sont exclues par défaut (paramètre
 * {@code excludeSources}, liste de mots séparés par « ; » recherchés dans la colonne {@code source} ;
 * valeur vide pour tout garder).
 */
@Extension
public class UsCslSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "us-csl";
    static final String DEFAULT_EXCLUDE = "Treasury Department";

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "US Government (Consolidated Screening List)";
    }

    /** Catégorie de risque propre à chaque liste : ajoutée fiche par fiche. */
    @Override
    protected String[] topics() {
        return new String[0];
    }

    @Override
    protected List<SanctionRecord> read(Path input, Map<String, String> parameters) throws Exception {
        String exclude = parameters.getOrDefault("excludeSources", DEFAULT_EXCLUDE);
        return read(input, exclude);
    }

    List<SanctionRecord> read(Path input, String excludeSources) throws Exception {
        List<String> excluded = Arrays.stream((excludeSources == null ? "" : excludeSources).split(";"))
                .map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT)).toList();
        List<SanctionRecord> records = new ArrayList<>();
        CsvRecordReader.readEach(input, row -> {
            String source = row.get("source");
            String name = row.get("name");
            if (source == null || name == null
                    || excluded.stream().anyMatch(e -> source.toLowerCase(Locale.ROOT).contains(e))) {
                return;
            }
            records.add(convert(row, source, name));
        });
        return records;
    }

    private static SanctionRecord convert(CsvRow row, String source, String name) {
        String code = sourceCode(source);
        String type = row.get("type");
        String schema = type == null ? "LegalEntity" : switch (type) {
            case "Individual" -> "Person";
            case "Vessel" -> "Vessel";
            case "Aircraft" -> "Airplane";
            default -> "Organization";
        };
        // Clé : liste + numéro officiel s'il existe (Trésor), sinon le nom (les autres listes n'ont pas de numéro
        // stable : la colonne _id est une empreinte du contenu, qui change à chaque correction).
        String number = row.get("entity_number");
        SanctionRecord r = new SanctionRecord(schema, code, number != null ? number : name)
                .sourceRef(source + (number != null ? ", n° " + number : ""));
        r.add("name", name.trim());
        for (String alias : split(row.get("alt_names"))) {
            r.add("alias", alias);
        }
        r.add("topics", topic(code));
        r.add("notes", row.get("remarks"));
        boolean person = "Person".equals(schema);
        if (person) {
            r.add("position", row.get("title"));
            split(row.get("dates_of_birth")).forEach(d -> r.addAll("birthDate", Dates.parse(d)));
            split(row.get("places_of_birth")).forEach(p -> r.add("birthPlace", p));
            split(row.get("nationalities")).forEach(n -> r.add("nationality", n));
            split(row.get("citizenships")).forEach(c -> r.add("citizenship", c));
        } else {
            r.add("notes", row.get("title"));
        }
        if ("Vessel".equals(schema)) {
            r.add("callSign", row.get("call_sign"));
            r.add("type", row.get("vessel_type"));
            r.add("flag", row.get("vessel_flag"));
            if (row.get("vessel_owner") != null) {
                r.add("notes", "Owner: " + row.get("vessel_owner"));
            }
        }
        for (String address : split(row.get("addresses"))) {
            r.add("address", address);
            String[] parts = address.split(",");
            String last = parts[parts.length - 1].trim();
            if (last.matches("[A-Z]{2}")) {
                r.add("country", last);
            }
        }
        for (String id : split(row.get("ids"))) {
            r.add("notes", id);
        }

        split(row.get("programs")).forEach(p -> r.sanction("program", p));
        r.sanction("program", source);
        r.sanction("authority", authorityOf(source));
        r.sanction("authorityId", number);
        r.sanction("startDate", row.get("start_date"));
        r.sanction("endDate", row.get("end_date"));
        r.sanction("provisions", joinNonBlank(" ; ", row.get("federal_register_notice"), row.get("license_requirement"),
                row.get("license_policy")));
        r.sanction("sourceUrl", row.get("source_list_url"));
        return r;
    }

    /** Sigle de la liste, entre parenthèses dans la colonne source : « Entity List (EL) - … » → EL. */
    static String sourceCode(String source) {
        int open = source.indexOf('(');
        int close = source.indexOf(')', open + 1);
        if (open >= 0 && close > open) {
            return source.substring(open + 1, close).replace(" List", "").trim();
        }
        return source.replaceAll("[^A-Za-z]", "").substring(0, Math.min(10, source.replaceAll("[^A-Za-z]", "").length()));
    }

    private static String authorityOf(String source) {
        int dash = source.lastIndexOf(" - ");
        return dash > 0 ? "US " + source.substring(dash + 3).trim() : "US Government";
    }

    /** Contrôle des exportations (BIS, ITAR) ou sanction (Trésor, non-prolifération). */
    private static String topic(String code) {
        return switch (code) {
            case "EL", "DPL", "UVL", "MEU", "DTC" -> "export.control";
            default -> "sanction";
        };
    }

    private static List<String> split(String raw) {
        if (raw == null) {
            return List.of();
        }
        return Arrays.stream(raw.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
