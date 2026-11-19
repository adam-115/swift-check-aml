package com.swiftcheck.aml.plugins.sanction.un;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.sdk.XmlElement;
import com.swiftcheck.aml.sdk.XmlRecordReader;

/**
 * Liste récapitulative des sanctions du Conseil de sécurité de l'ONU (fichier XML
 * {@code scsanctions.un.org/resources/xml/en/consolidated.xml}).
 *
 * <p>Une fiche {@code <INDIVIDUAL>} donne une {@code Person}, une fiche {@code <ENTITY>} une {@code Organization},
 * chacune avec sa {@code Sanction} (régime, référence ONU, date d'inscription).
 */
@Extension
public class UnScSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "un-sc";

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "United Nations Security Council";
    }

    @Override
    protected List<SanctionRecord> read(Path input) throws Exception {
        try (InputStream in = Files.newInputStream(input)) {
            return read(in);
        }
    }

    List<SanctionRecord> read(InputStream in) throws Exception {
        List<SanctionRecord> records = new ArrayList<>();
        XmlRecordReader.readEach(in, Set.of("INDIVIDUAL", "ENTITY"), fiche -> {
            String dataId = fiche.childText("DATAID");
            if (dataId != null) {
                records.add(convert(fiche, dataId, "INDIVIDUAL".equals(fiche.name())));
            }
        });
        return records;
    }

    private static SanctionRecord convert(XmlElement fiche, String dataId, boolean person) {
        SanctionRecord r = new SanctionRecord(person ? "Person" : "Organization", dataId)
                .sourceRef("DATAID=" + dataId + ", " + fiche.childText("REFERENCE_NUMBER"));
        r.add("name", joinNonBlank(" ", fiche.childText("FIRST_NAME"), fiche.childText("SECOND_NAME"),
                fiche.childText("THIRD_NAME"), fiche.childText("FOURTH_NAME")));
        r.add("alias", fiche.childText("NAME_ORIGINAL_SCRIPT"));
        for (XmlElement alias : fiche.children(person ? "INDIVIDUAL_ALIAS" : "ENTITY_ALIAS")) {
            String quality = alias.childText("QUALITY");
            String property = "Low".equalsIgnoreCase(quality) ? "weakAlias"
                    : "f.k.a.".equalsIgnoreCase(quality) ? "previousName" : "alias";
            r.add(property, alias.childText("ALIAS_NAME"));
        }
        r.add("notes", fiche.childText("COMMENTS1"));
        r.add("sourceUrl", fiche.childText("INTERPOL_LINK"));

        if (person) {
            r.add("firstName", fiche.childText("FIRST_NAME"));
            r.add("gender", toFtmGender(fiche.childText("GENDER")));
            values(fiche, "TITLE").forEach(v -> r.add("title", v));
            values(fiche, "DESIGNATION").forEach(v -> r.add("position", v));
            values(fiche, "NATIONALITY").forEach(v -> r.add("nationality", v));
            for (XmlElement dob : fiche.children("INDIVIDUAL_DATE_OF_BIRTH")) {
                r.add("birthDate", dob.childText("DATE"));
                r.add("birthDate", dob.childText("YEAR"));
                r.add("birthDate", dob.childText("FROM_YEAR"));
                r.add("birthDate", dob.childText("TO_YEAR"));
            }
            for (XmlElement pob : fiche.children("INDIVIDUAL_PLACE_OF_BIRTH")) {
                r.add("birthPlace", joinNonBlank(", ", pob.childText("CITY"), pob.childText("STATE_PROVINCE"),
                        pob.childText("COUNTRY")));
                r.add("birthCountry", pob.childText("COUNTRY"));
            }
            for (XmlElement doc : fiche.children("INDIVIDUAL_DOCUMENT")) {
                String type = doc.childText("TYPE_OF_DOCUMENT");
                String number = doc.childText("NUMBER");
                r.add(type != null && type.toLowerCase().contains("passport") ? "passportNumber" : "idNumber", number);
                if (number != null) {
                    r.add("notes", joinNonBlank(" ", type, doc.childText("TYPE_OF_DOCUMENT2"), number,
                            doc.childText("ISSUING_COUNTRY"), doc.childText("NOTE")));
                }
            }
        }
        for (XmlElement address : fiche.children(person ? "INDIVIDUAL_ADDRESS" : "ENTITY_ADDRESS")) {
            r.add("address", joinNonBlank(", ", address.childText("STREET"), address.childText("CITY"),
                    address.childText("STATE_PROVINCE"), address.childText("ZIP_CODE"), address.childText("COUNTRY")));
            r.add("country", address.childText("COUNTRY"));
        }

        r.sanction("program", fiche.childText("UN_LIST_TYPE"))
                .sanction("programId", fiche.childText("UN_LIST_TYPE"))
                .sanction("authorityId", fiche.childText("REFERENCE_NUMBER"))
                .sanction("unscId", fiche.childText("REFERENCE_NUMBER"))
                .sanction("listingDate", fiche.childText("LISTED_ON"))
                .sanction("reason", fiche.childText("COMMENTS1"))
                .sanction("sourceUrl", "https://main.un.org/securitycouncil/en/content/un-sc-consolidated-list");
        values(fiche, "LAST_DAY_UPDATED").forEach(v -> r.sanction("modifiedAt", v));
        return r;
    }

    /** Valeurs {@code <VALUE>} d'un élément répété (NATIONALITY, DESIGNATION…). */
    private static List<String> values(XmlElement fiche, String name) {
        List<String> out = new ArrayList<>();
        for (XmlElement e : fiche.children(name)) {
            for (XmlElement v : e.children("VALUE")) {
                if (v.text() != null) {
                    out.add(v.text());
                }
            }
        }
        return out;
    }
}
