package com.swiftcheck.aml.plugins.sanction.ofac;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.Dates;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.sdk.XmlElement;
import com.swiftcheck.aml.sdk.XmlRecordReader;

/**
 * Listes de l'OFAC (Office of Foreign Assets Control, Trésor américain) au format XML classique
 * ({@code <sdnList><sdnEntry>}) : la liste SDN ({@code SDN.XML}) et la liste consolidée des sanctions non-SDN
 * ({@code CONSOLIDATED.XML} : SSI, NS-MBS, CMIC, FSE, PLC, HKAA…), qui ont le même format.
 *
 * <p>Un seul plugin pour les deux listes : chaque parser configuré donne son préfixe d'ID dans le paramètre
 * {@code idPrefix} ({@code us-ofac-sdn} par défaut, {@code us-ofac-cons} pour la liste consolidée).
 */
@Extension
public class OfacSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "us-ofac-sdn";

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "Office of Foreign Assets Control (US Treasury)";
    }

    @Override
    protected List<SanctionRecord> read(Path input) throws Exception {
        try (InputStream in = Files.newInputStream(input)) {
            return read(in);
        }
    }

    List<SanctionRecord> read(InputStream in) throws Exception {
        List<SanctionRecord> records = new ArrayList<>();
        XmlRecordReader.readEach(in, "sdnEntry", entry -> {
            SanctionRecord r = convert(entry);
            if (r != null) {
                records.add(r);
            }
        });
        return records;
    }

    private static SanctionRecord convert(XmlElement entry) {
        String uid = entry.childText("uid");
        if (uid == null) {
            return null;
        }
        String type = entry.childText("sdnType");
        String schema = switch (type == null ? "" : type) {
            case "Individual" -> "Person";
            case "Vessel" -> "Vessel";
            case "Aircraft" -> "Airplane";
            default -> "Organization";
        };
        boolean person = "Person".equals(schema);
        SanctionRecord r = new SanctionRecord(schema, uid).sourceRef("uid=" + uid);
        r.add("name", joinNonBlank(" ", entry.childText("firstName"), entry.childText("lastName")));
        if (person) {
            r.add("firstName", entry.childText("firstName"));
            r.add("lastName", entry.childText("lastName"));
            r.add("position", entry.childText("title"));
        } else {
            r.add("notes", entry.childText("title"));
        }
        r.add("notes", entry.childText("remarks"));

        for (XmlElement aka : entry.child("akaList").children("aka")) {
            String name = joinNonBlank(" ", aka.childText("firstName"), aka.childText("lastName"));
            String property = "f.k.a.".equalsIgnoreCase(aka.childText("type")) ? "previousName"
                    : "weak".equalsIgnoreCase(aka.childText("category")) ? "weakAlias" : "alias";
            r.add(property, name);
        }
        for (XmlElement address : entry.child("addressList").children("address")) {
            r.add("address", joinNonBlank(", ", address.childText("address1"), address.childText("address2"),
                    address.childText("address3"), address.childText("city"), address.childText("stateOrProvince"),
                    address.childText("postalCode"), address.childText("country")));
            r.add("country", address.childText("country"));
        }
        if (person) {
            for (XmlElement dob : entry.child("dateOfBirthList").children("dateOfBirthItem")) {
                r.addAll("birthDate", Dates.parse(dob.childText("dateOfBirth")));
            }
            for (XmlElement pob : entry.child("placeOfBirthList").children("placeOfBirthItem")) {
                r.add("birthPlace", pob.childText("placeOfBirth"));
            }
            for (XmlElement n : entry.child("nationalityList").children("nationality")) {
                r.add("nationality", n.childText("country"));
            }
            for (XmlElement c : entry.child("citizenshipList").children("citizenship")) {
                r.add("citizenship", c.childText("country"));
            }
        }
        XmlElement vessel = entry.child("vesselInfo");
        if ("Vessel".equals(schema)) {
            r.add("callSign", vessel.childText("callSign"));
            r.add("type", vessel.childText("vesselType"));
            r.add("flag", vessel.childText("vesselFlag"));
            r.add("tonnage", digits(vessel.childText("tonnage")));
            r.add("grossRegisteredTonnage", digits(vessel.childText("grossRegisteredTonnage")));
            if (vessel.childText("vesselOwner") != null) {
                r.add("notes", "Owner: " + vessel.childText("vesselOwner"));
            }
        }
        for (XmlElement id : entry.child("idList").children("id")) {
            addIdentifier(r, schema, id);
        }

        for (XmlElement program : entry.child("programList").children("program")) {
            r.sanction("program", program.text()).sanction("programId", program.text());
        }
        r.sanction("authorityId", uid)
                .sanction("sourceUrl", "https://sanctionssearch.ofac.treas.gov/Details.aspx?id=" + uid);
        return r;
    }

    /** {@code <id>} de l'OFAC : documents, identifiants, mais aussi informations sur la sanction. */
    private static void addIdentifier(SanctionRecord r, String schema, XmlElement id) {
        String type = id.childText("idType");
        String number = id.childText("idNumber");
        if (type == null || number == null) {
            return;
        }
        boolean person = "Person".equals(schema);
        boolean organization = "Organization".equals(schema);
        String note = joinNonBlank(" ", type + ":", number, id.childText("idCountry"),
                id.childText("issueDate") != null ? "issued " + id.childText("issueDate") : null,
                id.childText("expirationDate") != null ? "expires " + id.childText("expirationDate") : null);
        String t = type.toLowerCase();
        String property = null;
        if (t.equals("gender") && person) {
            r.add("gender", toFtmGender(number));
            return;
        } else if (t.equals("passport") && person) {
            property = "passportNumber";
        } else if (t.equals("website") && !"Vessel".equals(schema) && !"Airplane".equals(schema)) {
            property = "website";
        } else if (t.equals("email address") && (person || organization)) {
            property = "email";
        } else if (t.equals("phone number") && (person || organization)) {
            property = "phone";
        } else if (t.equals("swift/bic") && organization) {
            property = "swiftBic";
        } else if (t.equals("d-u-n-s number") && organization) {
            property = "dunsCode";
        } else if (t.equals("legal entity number") && organization) {
            property = "leiCode";
        } else if (t.equals("organization established date") && organization) {
            r.addAll("incorporationDate", Dates.parse(number));
            return;
        } else if (t.equals("vessel registration identification") && "Vessel".equals(schema)) {
            r.add("imoNumber", number.replaceFirst("(?i)^IMO\\s*", ""));
            return;
        } else if (t.equals("mmsi") && "Vessel".equals(schema)) {
            property = "mmsi";
        } else if (t.equals("former vessel flag") && "Vessel".equals(schema)) {
            property = "pastFlags";
        } else if (t.equals("aircraft model") && "Airplane".equals(schema)) {
            property = "model";
        } else if (t.startsWith("aircraft manufacturer's serial number") && "Airplane".equals(schema)) {
            property = "serialNumber";
        } else if (t.equals("aircraft manufacture date") && "Airplane".equals(schema)) {
            r.addAll("buildDate", Dates.parse(number));
            return;
        } else if (t.equals("aircraft tail number") && "Airplane".equals(schema)) {
            property = "registrationNumber";
        } else if (t.startsWith("secondary sanctions risk") || t.startsWith("additional sanctions information")
                || t.startsWith("transactions prohibited") || t.startsWith("executive order")
                || t.startsWith("caatsa") || t.contains("directive") || t.startsWith("listing date")
                || t.startsWith("effective date")) {
            r.sanction("provisions", type.replaceFirst(":$", "") + " " + number);
            return;
        } else if (person || organization) {
            if (t.contains("tax") || t.equals("r.f.c.") || t.startsWith("nit") || t.startsWith("ruc")
                    || t.startsWith("rif") || t.startsWith("inn") || t.equals("v.a.t. number")) {
                property = "taxNumber";
            } else if (organization && (t.contains("registration") || t.contains("registry") || t.contains("company number")
                    || t.contains("mercantil") || t.startsWith("c.r. no") || t.contains("trade license")
                    || t.contains("chamber of comm") || t.startsWith("ogrn"))) {
                property = "registrationNumber";
            } else if (t.contains("id") || t.contains("cedula") || t.contains("c.u.r.p") || t.contains("d.n.i")
                    || t.contains("license") || t.contains("number") || t.contains("no.")) {
                property = "idNumber";
            }
        }
        if (property != null) {
            r.add(property, number);
        }
        r.add("notes", note); // type et détails toujours conservés (adresses de cryptomonnaies comprises)
    }

    private static String digits(String raw) {
        return raw == null ? null : raw.replaceAll("[^0-9.]", "");
    }
}
