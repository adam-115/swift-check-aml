package com.swiftcheck.aml.plugins.sanction.ch;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;
import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;
import com.swiftcheck.aml.sdk.XmlElement;
import com.swiftcheck.aml.sdk.XmlRecordReader;

/**
 * Liste des sanctions de la Suisse (SECO, fichier XML « Gesamtliste » de SESAM).
 *
 * <p>Particularités du format :
 * <ul>
 * <li>le fichier contient tout l'historique : une cible dont la dernière modification est {@code de-listed} est
 * radiée et n'est pas reprise ;</li>
 * <li>lieux de naissance et adresses renvoient à des {@code <place>} décrits en fin de fichier : le fichier est
 * donc lu deux fois (lieux et programmes, puis cibles) ;</li>
 * <li>un nom est découpé en parties ordonnées (prénom, patronyme, nom…) avec leurs variantes d'écriture.</li>
 * </ul>
 */
@Extension
public class ChSecoSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "ch-seco";
    private static final Pattern IMO = Pattern.compile("IMO(?: Number)?[: ]*(\\d{7})", Pattern.CASE_INSENSITIVE);

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "State Secretariat for Economic Affairs (SECO), Switzerland";
    }

    private record Place(String text, String country) {
    }

    @Override
    protected List<SanctionRecord> read(Path input) throws Exception {
        Map<String, Place> places = new HashMap<>();
        Map<String, String[]> sets = new HashMap<>(); // ssid du sanctions-set → {programme, mesures}
        try (InputStream in = Files.newInputStream(input)) {
            XmlRecordReader.readEach(in, Set.of("place", "sanctions-program"), e -> {
                if ("place".equals(e.name())) {
                    String country = e.child("country").attribute("iso-code");
                    places.put(e.attribute("ssid"), new Place(joinNonBlank(", ", e.childText("location"),
                            e.childText("area"), e.childText("country")), country));
                } else {
                    String program = english(e, "program-key");
                    for (XmlElement set : e.children("sanctions-set")) {
                        if ("eng".equals(set.attribute("lang"))) {
                            sets.put(set.attribute("ssid"), new String[] { program, set.text(), english(e, "program-name") });
                        }
                    }
                }
            });
        }
        List<SanctionRecord> records = new ArrayList<>();
        try (InputStream in = Files.newInputStream(input)) {
            XmlRecordReader.readEach(in, "target", t -> {
                SanctionRecord r = convert(t, places, sets);
                if (r != null) {
                    records.add(r);
                }
            });
        }
        return records;
    }

    private static SanctionRecord convert(XmlElement target, Map<String, Place> places, Map<String, String[]> sets) {
        String ssid = target.attribute("ssid");
        XmlElement latest = target.children("modification").stream()
                .max(Comparator.comparing(m -> m.attribute("effective-date") == null ? "" : m.attribute("effective-date")))
                .orElse(XmlElement.EMPTY);
        if (ssid == null || "de-listed".equals(latest.attribute("modification-type"))) {
            return null; // radiée
        }
        XmlElement subject;
        String schema;
        if (!target.child("individual").isEmpty()) {
            subject = target.child("individual");
            schema = "Person";
        } else if (!target.child("entity").isEmpty()) {
            subject = target.child("entity");
            schema = "Organization";
        } else {
            subject = target.child("object");
            schema = "vessel".equals(subject.attribute("object-type")) ? "Vessel" : "LegalEntity";
        }
        boolean person = "Person".equals(schema);
        SanctionRecord r = new SanctionRecord(schema, ssid).sourceRef("target ssid=" + ssid);
        if (person) {
            r.add("gender", toFtmGender(subject.attribute("sex")));
        }
        List<XmlElement> identities = new ArrayList<>(subject.children("identity"));
        identities.sort(Comparator.comparing(i -> !"true".equals(i.attribute("main")))); // identité principale d'abord
        for (XmlElement identity : identities) {
            for (XmlElement name : identity.children("name")) {
                String full = fullName(name);
                String type = name.attribute("name-type");
                String property = "primary-name".equals(type) && r.first("name") == null ? "name"
                        : "formerly-known-as".equals(type) ? "previousName"
                        : "low".equals(name.attribute("quality")) ? "weakAlias" : "alias";
                r.add(property, full);
                for (XmlElement part : name.children("name-part")) {
                    for (XmlElement variant : part.children("spelling-variant")) {
                        if (part.children("name-part").isEmpty() && name.children("name-part").size() == 1) {
                            r.add("alias", variant.text()); // nom en une partie : la variante est un nom complet
                        }
                    }
                }
                if (name.children("name-part").size() > 1) {
                    r.add("alias", variantName(name));
                }
                if (person && "name".equals(property)) {
                    for (XmlElement part : name.children("name-part")) {
                        String pt = part.attribute("name-part-type");
                        if ("given-name".equals(pt)) {
                            r.add("firstName", part.childText("value"));
                        } else if ("family-name".equals(pt)) {
                            r.add("lastName", part.childText("value"));
                        } else if ("father-name".equals(pt)) {
                            r.add("fatherName", part.childText("value"));
                        } else if ("title".equals(pt)) {
                            r.add("title", part.childText("value"));
                        }
                    }
                }
            }
            if (person) {
                for (XmlElement dob : identity.children("day-month-year")) {
                    r.add("birthDate", date(dob));
                }
                for (XmlElement pob : identity.children("place-of-birth")) {
                    Place p = places.get(pob.attribute("place-id"));
                    if (p != null) {
                        r.add("birthPlace", p.text());
                        r.add("birthCountry", p.country());
                    }
                }
                for (XmlElement nat : identity.children("nationality")) {
                    r.add("nationality", nat.child("country").attribute("iso-code"));
                }
            }
            for (XmlElement address : identity.children("address")) {
                Place p = places.get(address.attribute("place-id"));
                r.add("address", joinNonBlank(", ", address.childText("c-o"), address.childText("address-details"),
                        address.childText("p-o-box"), address.childText("zip-code"), p == null ? null : p.text()));
                r.add("country", p == null ? null : p.country());
            }
            for (XmlElement doc : identity.children("identification-document")) {
                String type = doc.attribute("document-type");
                String number = doc.childText("number");
                if (person) {
                    r.add(type != null && type.contains("passport") ? "passportNumber" : "idNumber", number);
                } else if (!"Vessel".equals(schema)) {
                    r.add("registrationNumber", number);
                }
                if (number != null) {
                    r.add("notes", joinNonBlank(" ", type + ":", number, doc.childText("issuer"),
                            doc.childText("date-of-issue"), doc.childText("expiry-date")));
                }
            }
        }
        for (XmlElement info : subject.children("other-information")) {
            r.add("notes", info.text());
            if ("Vessel".equals(schema) && info.text() != null) {
                Matcher m = IMO.matcher(info.text());
                if (m.find()) {
                    r.add("imoNumber", m.group(1));
                }
            }
        }
        for (XmlElement justification : subject.children("justification")) {
            r.sanction("reason", justification.text());
        }

        String[] set = sets.get(target.childText("sanctions-set-id"));
        if (set != null) {
            r.sanction("program", set[0]).sanction("provisions", set[1]).sanction("summary", set[2]);
        }
        for (XmlElement m : target.children("modification")) {
            if ("listed".equals(m.attribute("modification-type"))) {
                r.sanction("listingDate", m.attribute("publication-date"))
                        .sanction("startDate", m.attribute("effective-date"));
            }
        }
        r.sanction("modifiedAt", latest.attribute("publication-date"))
                .sanction("authorityId", ssid)
                .sanction("sourceUrl", "https://www.sesam.search.admin.ch/sesam-search-web/pages/search.xhtml");
        return r;
    }

    /** Parties du nom dans l'ordre de la source (valeur principale). */
    private static String fullName(XmlElement name) {
        List<XmlElement> parts = sortedParts(name);
        List<String> values = new ArrayList<>();
        for (XmlElement p : parts) {
            if (!"title".equals(p.attribute("name-part-type"))) {
                values.add(p.childText("value"));
            }
        }
        return joinNonBlank(" ", values.toArray(String[]::new));
    }

    /** Variante d'écriture complète (ex. cyrillique) quand chaque partie en a une dans la même langue et écriture. */
    private static String variantName(XmlElement name) {
        List<String> values = new ArrayList<>();
        for (XmlElement p : sortedParts(name)) {
            if ("title".equals(p.attribute("name-part-type"))) {
                continue;
            }
            XmlElement cyrl = p.children("spelling-variant").stream()
                    .filter(v -> "CYRL".equals(v.attribute("script")) || "ARAB".equals(v.attribute("script")))
                    .findFirst().orElse(null);
            if (cyrl == null) {
                return null;
            }
            values.add(cyrl.text());
        }
        return joinNonBlank(" ", values.toArray(String[]::new));
    }

    private static List<XmlElement> sortedParts(XmlElement name) {
        List<XmlElement> parts = new ArrayList<>(name.children("name-part"));
        parts.sort(Comparator.comparingInt(p -> {
            try {
                return Integer.parseInt(p.attribute("order"));
            } catch (RuntimeException e) {
                return 99;
            }
        }));
        return parts;
    }

    private static String date(XmlElement dmy) {
        String y = dmy.attribute("year");
        if (y == null) {
            return null;
        }
        String m = dmy.attribute("month");
        String d = dmy.attribute("day");
        if (m == null) {
            return y;
        }
        String ym = y + "-" + (m.length() == 1 ? "0" + m : m);
        return d == null ? ym : ym + "-" + (d.length() == 1 ? "0" + d : d);
    }

    private static String english(XmlElement program, String child) {
        for (XmlElement e : program.children(child)) {
            if ("eng".equals(e.attribute("lang"))) {
                return e.text();
            }
        }
        return null;
    }
}
