package com.swiftcheck.aml.plugins.sanction.nz;

import static com.swiftcheck.aml.plugins.sanction.common.XlsxReader.excelDate;
import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.Dates;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.plugins.sanction.common.XlsxReader;

/**
 * Russia Sanctions Register du ministère des Affaires étrangères et du Commerce de Nouvelle-Zélande (MFAT),
 * classeur Excel {@code Russia-Sanctions-Register.xlsx} (Russia Sanctions Act 2022).
 *
 * <p>Feuilles lues : « Russia Sanctions Register » (personnes, entités, avoirs) et « Ships ». Les feuilles des
 * mesures commerciales (dont une de 180 Mo) ne sont pas décompressées. Une fiche dont le statut n'est pas
 * « Sanctioned » ou marquée supprimée n'est pas reprise. Clé stable : « Unique Identifier » (IND-1, ENT-12, SHP-3).
 */
@Extension
public class NzMfatSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "nz-mfat";

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "Ministry of Foreign Affairs and Trade of New Zealand";
    }

    @Override
    protected List<SanctionRecord> read(Path input) throws Exception {
        List<SanctionRecord> records = new ArrayList<>();
        for (Map<String, String> row : XlsxReader.rows(XlsxReader.sheet(input, 1), "Unique Identifier")) {
            SanctionRecord r = register(row);
            if (r != null) {
                records.add(r);
            }
        }
        for (Map<String, String> row : XlsxReader.rows(XlsxReader.sheet(input, 2), "Unique Identifier")) {
            SanctionRecord r = ship(row);
            if (r != null) {
                records.add(r);
            }
        }
        return records;
    }

    private static boolean active(Map<String, String> row) {
        String status = row.get("Sanction Status");
        String deleted = row.get("Record Deleted Flag");
        return (status == null || status.trim().equalsIgnoreCase("Sanctioned"))
                && (deleted == null || !deleted.trim().equalsIgnoreCase("Yes"));
    }

    private static SanctionRecord register(Map<String, String> row) {
        String id = row.get("Unique Identifier");
        if (id == null || !active(row)) {
            return null;
        }
        String type = row.getOrDefault("Type", "");
        boolean person = type.equalsIgnoreCase("Individual");
        String schema = person ? "Person" : type.equalsIgnoreCase("Entity") ? "Organization" : "LegalEntity";
        SanctionRecord r = new SanctionRecord(schema, id).sourceRef(id);
        if (person) {
            r.add("name", joinNonBlank(" ", row.get("First name"), row.get("Middle name(s)"), row.get("Last name")));
            r.add("firstName", row.get("First name"));
            r.add("middleName", row.get("Middle name(s)"));
            r.add("lastName", row.get("Last name"));
            r.addAll("birthDate", Dates.parse(excelDate(row.get("DOB"))));
            r.add("position", row.get("Title"));
            r.add("birthPlace", row.get("Place of Birth"));
            r.add("nationality", row.get("Citizenship"));
            r.add("nationality", row.get("Citizenship 2"));
            r.add("nationality", row.get("Citizenship 3"));
            r.add("passportNumber", row.get("Passport Number"));
        } else {
            r.add("name", joinNonBlank(" ", row.get("Last name"), row.get("Name of Asset"), row.get("Name of Service")));
            r.add("notes", row.get("Title"));
        }
        aliases(r, row.get("Alias/Alternate Spellings"));
        r.add("address", row.get("Address"));
        r.add("notes", row.get("Associates/Relatives") == null ? null : "Associates/relatives: " + row.get("Associates/Relatives"));
        sanction(r, row, id);
        r.sanction("reason", row.get("General Rationale for Sanction"));
        return r;
    }

    private static SanctionRecord ship(Map<String, String> row) {
        String id = row.get("Unique Identifier");
        if (id == null || !active(row)) {
            return null;
        }
        SanctionRecord r = new SanctionRecord("Vessel", id).sourceRef(id);
        r.add("name", row.get("Name of Ship as of Date of Sanction"));
        r.add("imoNumber", row.get("IMO Number"));
        aliases(r, row.get("Alias/Alternate Names"));
        sanction(r, row, id);
        return r;
    }

    private static void aliases(SanctionRecord r, String raw) {
        if (raw != null) {
            for (String a : raw.split("[;\n]|,\\s+(?=[A-ZА-Я])")) {
                r.add("alias", a);
            }
        }
    }

    private static void sanction(SanctionRecord r, Map<String, String> row, String id) {
        List<String> measures = new ArrayList<>();
        for (String m : List.of("Travel Ban", "Asset Freeze", "Aircraft Ban", "Ship Ban", "Service Prohibition",
                "Dealing with Securities")) {
            if ("Yes".equalsIgnoreCase(String.valueOf(row.get(m)).trim())) {
                measures.add(m);
            }
        }
        r.sanction("program", "Russia Sanctions Act 2022")
                .sanction("authorityId", id)
                .sanction("listingDate", excelDate(row.get("Date of Sanction")))
                .sanction("modifiedAt", excelDate(row.get("Date of Additional Sanction")))
                .sanction("provisions", measures.isEmpty() ? null : String.join(", ", measures))
                .sanction("sourceUrl", "https://www.mfat.govt.nz/en/countries-and-regions/europe/ukraine/russian-invasion-of-ukraine/sanctions/russia-sanctions-register");
    }
}
