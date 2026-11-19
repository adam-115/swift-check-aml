package com.swiftcheck.aml.plugins.sanction.au;

import static com.swiftcheck.aml.plugins.sanction.common.XlsxReader.excelDate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.Dates;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.plugins.sanction.common.XlsxReader;

/**
 * Liste consolidée des sanctions de l'Australie (Australian Sanctions Office, DFAT), classeur
 * {@code Australian_Sanctions_Consolidated_List.xlsx}.
 *
 * <p>Une ligne par nom : la référence principale ({@code 1000}) porte le nom principal, les alias ont la même
 * référence suivie d'une lettre ({@code 1000a}). Les lignes sont regroupées par référence numérique, qui est la clé
 * stable de la fiche. Colonnes décrites dans le « Guide to Australia's Consolidated List » du DFAT.
 */
@Extension
public class AuDfatSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "au-dfat";

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "Australian Sanctions Office (DFAT)";
    }

    @Override
    protected List<SanctionRecord> read(Path input) throws Exception {
        Map<String, SanctionRecord> byReference = new LinkedHashMap<>();
        for (Map<String, String> row : XlsxReader.rows(XlsxReader.firstSheet(input), "Reference")) {
            String reference = row.get("Reference");
            String name = row.get("Name of Individual or Entity");
            if (reference == null || name == null) {
                continue;
            }
            String base = reference.trim().replaceFirst("\\.0+$", "").replaceAll("[^0-9]+$", "");
            if (base.isEmpty()) {
                base = reference.trim();
            }
            String type = row.getOrDefault("Type", "");
            String schema = type.equalsIgnoreCase("Individual") || type.equalsIgnoreCase("Person") ? "Person"
                    : type.equalsIgnoreCase("Vessel") ? "Vessel" : "Organization";
            String key = base;
            SanctionRecord r = byReference.computeIfAbsent(base, b -> new SanctionRecord(schema, key).sourceRef("Reference " + key));
            add(r, row, name);
        }
        return new ArrayList<>(byReference.values());
    }

    private static void add(SanctionRecord r, Map<String, String> row, String name) {
        String nameType = row.getOrDefault("Name Type", "").toLowerCase();
        String strength = row.getOrDefault("Alias Strength", "") == null ? "" : String.valueOf(row.get("Alias Strength")).toLowerCase();
        if (nameType.startsWith("primary") && r.first("name") == null) {
            r.add("name", name);
        } else if (strength.startsWith("weak")) {
            r.add("weakAlias", name);
        } else {
            r.add("alias", name);
        }
        boolean person = "Person".equals(r.schema());
        if (person) {
            for (String dob : split(row.get("Date of Birth"))) {
                r.addAll("birthDate", Dates.parse(excelDate(dob)));
            }
            split(row.get("Place of Birth")).forEach(v -> r.add("birthPlace", v));
            split(row.get("Citizenship")).forEach(v -> r.add("nationality", v));
        }
        if ("Vessel".equals(r.schema())) {
            r.add("imoNumber", row.get("IMO Number") == null ? null : row.get("IMO Number").replaceAll("\\D", ""));
        } else {
            split(row.get("Address")).forEach(v -> r.add("address", v));
        }
        r.add("notes", row.get("Additional Information"));

        r.sanction("program", row.get("Committees"))
                .sanction("reason", row.get("Listing Information"))
                .sanction("provisions", row.get("Instrument of Designation"))
                .sanction("modifiedAt", excelDate(row.get("Control Date")));
        List<String> measures = new ArrayList<>();
        for (String m : List.of("Targeted Financial Sanction", "Travel Ban", "Arms Embargo", "Maritime Restriction")) {
            if ("true".equalsIgnoreCase(String.valueOf(row.get(m)).trim()) || "1".equals(String.valueOf(row.get(m)).trim())) {
                measures.add(m);
            }
        }
        if (!measures.isEmpty()) {
            r.sanction("summary", String.join(", ", measures));
        }
        r.sanction("sourceUrl", "https://www.dfat.gov.au/international-relations/security/sanctions/consolidated-list");
    }

    private static List<String> split(String raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String v : raw.split(";|\n")) {
            if (!v.isBlank()) {
                out.add(v.trim());
            }
        }
        return out;
    }
}
