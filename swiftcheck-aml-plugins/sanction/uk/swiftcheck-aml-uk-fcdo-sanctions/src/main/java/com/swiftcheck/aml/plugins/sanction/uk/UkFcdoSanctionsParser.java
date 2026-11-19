package com.swiftcheck.aml.plugins.sanction.uk;

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
 * UK Sanctions List du Foreign, Commonwealth &amp; Development Office (fichier XML
 * {@code sanctionslist.fcdo.gov.uk/docs/UK-Sanctions-List.xml}). Depuis le 28 janvier 2026 c'est la seule liste
 * officielle du Royaume-Uni (la liste consolidée de l'OFSI a été fermée).
 *
 * <p>Une fiche {@code <Designation>} donne une {@code Person} (Individual), une {@code Organization} (Entity) ou
 * un {@code Vessel} (Ship), avec sa {@code Sanction} (régime, mesures imposées, motifs britanniques).
 */
@Extension
public class UkFcdoSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "uk-fcdo";

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "United Kingdom (FCDO / HM Treasury)";
    }

    @Override
    protected List<SanctionRecord> read(Path input) throws Exception {
        try (InputStream in = Files.newInputStream(input)) {
            return read(in);
        }
    }

    List<SanctionRecord> read(InputStream in) throws Exception {
        List<SanctionRecord> records = new ArrayList<>();
        XmlRecordReader.readEach(in, "Designation", d -> {
            String id = d.childText("UniqueID");
            if (id != null) {
                records.add(convert(d, id));
            }
        });
        return records;
    }

    private static SanctionRecord convert(XmlElement d, String id) {
        String kind = d.childText("IndividualEntityShip");
        String schema = "Individual".equals(kind) ? "Person" : "Ship".equals(kind) ? "Vessel" : "Organization";
        boolean person = "Person".equals(schema);
        SanctionRecord r = new SanctionRecord(schema, id).sourceRef("UniqueID=" + id);

        for (XmlElement name : d.child("Names").children("Name")) {
            String full = joinNonBlank(" ", name.childText("Name1"), name.childText("Name2"), name.childText("Name3"),
                    name.childText("Name4"), name.childText("Name5"), name.childText("Name6"));
            String type = name.childText("NameType");
            String strength = name.childText("AliasStrength");
            if ("Primary Name".equalsIgnoreCase(type)) { // « Primary Name » ou « Primary name » selon les fiches
                r.add("name", full);
                if (person) {
                    r.add("firstName", name.childText("Name1"));
                    r.add("lastName", name.childText("Name6"));
                }
            } else if (strength != null && strength.toLowerCase().startsWith("low")) {
                r.add("weakAlias", full);
            } else {
                r.add("alias", full);
            }
        }
        for (XmlElement nonLatin : d.child("NonLatinNames").children("NonLatinName")) {
            r.add("alias", nonLatin.childText("NameNonLatinScript"));
        }
        r.add("notes", d.childText("OtherInformation"));

        for (XmlElement address : d.child("Addresses").children("Address")) {
            r.add("address", joinNonBlank(", ", address.childText("AddressLine1"), address.childText("AddressLine2"),
                    address.childText("AddressLine3"), address.childText("AddressLine4"),
                    address.childText("AddressLine5"), address.childText("AddressLine6"),
                    address.childText("AddressPostalCode"), address.childText("AddressCountry")));
            r.add("country", address.childText("AddressCountry"));
        }
        if (!"Vessel".equals(schema)) {
            texts(d.child("PhoneNumbers"), "PhoneNumber").forEach(v -> r.add("phone", v));
            texts(d.child("EmailAddresses"), "EmailAddress").forEach(v -> r.add("email", v));
            texts(d.child("Websites"), "Website").forEach(v -> r.add("website", v));
        }

        if (person) {
            XmlElement ind = d.child("IndividualDetails").child("Individual");
            texts(d.child("Titles"), "Title").forEach(v -> r.add("title", v));
            for (String dob : texts(ind.child("DOBs"), "DOB")) {
                r.addAll("birthDate", Dates.parse(partialDate(dob)));
            }
            texts(ind.child("Nationalities"), "Nationality").forEach(v -> r.add("nationality", v));
            texts(ind.child("Positions"), "Position").forEach(v -> r.add("position", v));
            texts(ind.child("Genders"), "Gender").forEach(v -> r.add("gender", toFtmGender(v)));
            for (XmlElement location : ind.child("BirthDetails").children("Location")) {
                r.add("birthPlace", joinNonBlank(", ", location.childText("TownOfBirth"), location.childText("CountryOfBirth")));
                r.add("birthCountry", location.childText("CountryOfBirth"));
            }
            for (XmlElement passport : ind.child("PassportDetails").children("Passport")) {
                r.add("passportNumber", passport.childText("PassportNumber"));
                note(r, "Passport", passport.childText("PassportNumber"), passport.childText("PassportAdditionalInformation"));
            }
            for (XmlElement nid : ind.child("NationalIdentifierDetails").children("NationalIdentifier")) {
                r.add("idNumber", nid.childText("NationalIdentifierNumber"));
                note(r, "National identifier", nid.childText("NationalIdentifierNumber"),
                        nid.childText("NationalIdentifierAdditionalInformation"));
            }
        } else if ("Organization".equals(schema)) {
            XmlElement ent = d.child("EntityDetails").child("Entity");
            texts(ent.child("TypeOfEntities"), "TypeOfEntity").forEach(v -> r.add("legalForm", v));
            texts(ent.child("BusinessRegistrationNumbers"), "BusinessRegistrationNumber")
                    .forEach(v -> r.add("registrationNumber", v));
            texts(ent.child("ParentCompanies"), "ParentCompany").forEach(v -> r.add("notes", "Parent company: " + v));
            texts(ent.child("Subsidiaries"), "Subsidiary").forEach(v -> r.add("notes", "Subsidiary: " + v));
        } else {
            XmlElement ship = d.child("ShipDetails").child("Ship");
            texts(ship.child("IMONumbers"), "IMONumber").forEach(v -> r.add("imoNumber", v.replaceFirst("(?i)^IMO\\s*", "")));
            texts(ship.child("CurrentBelievedFlagOfShips"), "CurrentBelievedFlagOfShip").forEach(v -> r.add("flag", v));
            texts(ship.child("PreviousFlags"), "PreviousFlag").forEach(v -> r.add("pastFlags", v));
            texts(ship.child("TypeOfShipDetails"), "TypeOfShip").forEach(v -> r.add("type", v));
            texts(ship.child("TonnageOfShipDetails"), "TonnageOfShip").forEach(v -> r.add("tonnage", v.replaceAll("[^0-9.]", "")));
            texts(ship.child("YearsBuilt"), "YearBuilt").forEach(v -> r.add("buildDate", v));
            texts(ship.child("CurrentOwnerOperators"), "CurrentOwnerOperator").forEach(v -> r.add("notes", "Owner/operator: " + v));
            texts(ship.child("HullIdentificationNumbers"), "HullIdentificationNumber").forEach(v -> r.add("registrationNumber", v));
        }

        r.sanction("program", d.childText("RegimeName"))
                .sanction("authorityId", id)
                .sanction("unscId", d.childText("UNReferenceNumber"))
                .sanction("listingDate", d.childText("DateDesignated"))
                .sanction("modifiedAt", d.childText("LastUpdated"))
                .sanction("provisions", d.childText("SanctionsImposed") == null ? null
                        : d.childText("SanctionsImposed").replace("|", ", "))
                .sanction("reason", d.childText("UKStatementofReasons"))
                .sanction("summary", d.childText("DesignationSource") == null ? null
                        : "Designation source: " + d.childText("DesignationSource"))
                .sanction("sourceUrl", "https://www.gov.uk/government/publications/the-uk-sanctions-list");
        return r;
    }

    /** « dd/mm/1971 » → « 1971 », « dd/12/1960 » → « 12/1960 » (jour ou mois inconnus). */
    static String partialDate(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replaceFirst("(?i)^dd/mm/", "").replaceFirst("(?i)^dd/", "");
        return s.matches("(?i)\\d{2}/\\d{2}/\\d{2}yy") ? null : s;
    }

    private static void note(SanctionRecord r, String type, String number, String details) {
        if (number != null || details != null) {
            r.add("notes", joinNonBlank(" ", type + ":", number, details));
        }
    }

    private static List<String> texts(XmlElement parent, String childName) {
        List<String> out = new ArrayList<>();
        for (XmlElement e : parent.children(childName)) {
            if (e.text() != null) {
                out.add(e.text());
            }
        }
        return out;
    }
}
