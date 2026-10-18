package com.swiftcheck.aml.plugins.eu;

import static com.swiftcheck.aml.sdk.TextUtils.toFtmGender;
import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;
import com.swiftcheck.aml.sdk.XmlElement;
import com.swiftcheck.aml.sdk.XmlRecordReader;

/**
 * Liste consolidée des sanctions financières de l'Union européenne
 * (fichier XML « Financial Sanctions Files » de la Commission européenne).
 *
 * <p>
 * Chaque fiche {@code <sanctionEntity>} produit :
 * <ul>
 * <li>une {@code Person} (subjectType = person) ou une {@code Organization}
 * (subjectType = enterprise) ;</li>
 * <li>une {@code Sanction} reliée, qui porte les références juridiques.</li>
 * </ul>
 *
 * <p>
 * Structure simplifiée d'une fiche :
 * 
 * <pre>
 * &lt;sanctionEntity logicalId="13" euReferenceNumber="EU.27.28" unitedNationId=""&gt;
 *   &lt;remark&gt;...&lt;/remark&gt;
 *   &lt;regulation programme="IRQ" entryIntoForceDate="..." publicationDate="..." numberTitle="..."&gt;
 *     &lt;publicationUrl&gt;http://eur-lex...&lt;/publicationUrl&gt;
 *   &lt;/regulation&gt;
 *   &lt;subjectType code="person"/&gt;
 *   &lt;nameAlias wholeName="..." firstName="..." lastName="..." gender="M" function="..." title="..."/&gt;
 *   &lt;citizenship countryIso2Code="RU"/&gt;
 *   &lt;birthdate birthdate="1965-03-15" year="1965" city="..." countryIso2Code="RU"/&gt;
 *   &lt;address street="..." city="..." countryIso2Code="RU"&gt;&lt;contactInfo key="EMAIL" value="..."/&gt;&lt;/address&gt;
 *   &lt;identification identificationTypeCode="passport" number="..."/&gt;
 * &lt;/sanctionEntity&gt;
 * </pre>
 */
@Extension
public class EuSanctionsParser implements Iparser {

    /**
     * Type de document UE → propriété FtM pour une personne. Tout autre type
     * devient "idNumber".
     */
    private static final Map<String, String> PERSON_DOCUMENTS = Map.of(
            "passport", "passportNumber",
            "taxid", "taxNumber",
            "fiscalcode", "taxNumber");

    /**
     * Type de document UE → propriété FtM pour une organisation. Tout autre type
     * devient "idNumber".
     */
    private static final Map<String, String> ORGANIZATION_DOCUMENTS = Map.of(
            "regnumber", "registrationNumber",
            "taxid", "taxNumber",
            "fiscalcode", "taxNumber",
            "imo", "imoNumber",
            "swiftbic", "swiftBic");

    /**
     * Préfixe des identifiants FtM (ex. {@code eu-fsd-13}) : ne jamais le changer,
     * les alertes y sont rattachées.
     */
    static final String ID_PREFIX = "eu-fsd";

    /**
     * Catégorie de risque ajoutée à chaque personne ou organisation de la liste.
     */
    static final String TOPIC = "sanction";

    /**
     * Point d'entrée appelé par le core : lit le fichier source (déjà téléchargé,
     * signé et archivé),
     * écrit les entités FtM dans {@code ctx.outputFile()} et rend le bilan.
     */
    @Override
    public ParserResult parse(ParserContext parserContext) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        try (InputStream source = Files.newInputStream(parserContext.inputFile())) {
            convert(source, dataset);
        }
        dataset.writeNdjsonFile(parserContext.outputFile());
        return dataset.toConversionResult();
    }

    /**
     * Test du parser : relit le XML source et liste chaque personne ou organisation, avec l'ID et le nom
     * que Yente doit connaître. Lecture indépendante de la conversion (aucune entité FtM n'est construite).
     */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext parserContext) throws Exception {
        try (InputStream source = Files.newInputStream(parserContext.inputFile())) {
            return readSourceRecords(source);
        }
    }

    /** Une fiche {@code <sanctionEntity>} = un élément attendu dans Yente. Séparé pour être testé directement. */
    List<SourceRecord> readSourceRecords(InputStream source) throws Exception {
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX); // uniquement pour calculer les ID comme à la conversion
        List<SourceRecord> records = new ArrayList<>();
        XmlRecordReader.readEach(source, "sanctionEntity", fiche -> {
            String logicalId = fiche.attribute("logicalId");
            if (logicalId == null) {
                return; // fiche ignorée aussi par la conversion
            }
            String schema = "enterprise".equals(fiche.child("subjectType").attribute("code")) ? "Organization" : "Person";
            String name = null;
            for (XmlElement alias : fiche.children("nameAlias")) {
                if (alias.attribute("wholeName") != null) {
                    name = alias.attribute("wholeName").replaceAll("\\s+", " ").trim(); // premier nom complet, espaces normalisés
                    break;
                }
            }
            records.add(new SourceRecord(ids.buildEntityId(logicalId), schema, name,
                    "logicalId=" + logicalId + (fiche.attribute("euReferenceNumber") != null
                            ? ", " + fiche.attribute("euReferenceNumber") : "")));
        });
        return records;
    }

    /**
     * Lit le XML fiche par fiche et ajoute les entités au dataset. Séparé de
     * {@link #parse} pour
     * être testé directement.
     */
    void convert(InputStream source, FtmDatasetBuilder dataset) throws Exception {
        XmlRecordReader.readEach(source, "sanctionEntity", fiche -> convertFiche(fiche, dataset));
    }

    /** Convertit une fiche UE en deux entités FtM : le sujet puis la sanction. */
    private void convertFiche(XmlElement fiche, FtmDatasetBuilder dataset) {
        String id = fiche.attribute("logicalId");
        if (id == null) {
            return; // sans identifiant, impossible de créer un ID stable
        }
        boolean isPerson = !"enterprise".equals(fiche.child("subjectType").attribute("code"));
        FtmEntity subject = dataset.newEntity(isPerson ? "Person" : "Organization", id);

        addNames(fiche, subject, isPerson);

        for (XmlElement citizenship : fiche.children("citizenship")) {
            // L'UE donne déjà un code ISO ; "00" (inconnu) est ignoré par la bibliothèque
            // commune.
            subject.addValue(isPerson ? "nationality" : "jurisdiction", citizenship.attribute("countryIso2Code"));
        }

        if (isPerson) {
            for (XmlElement birth : fiche.children("birthdate")) {
                addBirth(birth, subject);
            }
        }

        for (XmlElement address : fiche.children("address")) {
            addAddress(address, subject);
        }

        Map<String, String> documents = isPerson ? PERSON_DOCUMENTS : ORGANIZATION_DOCUMENTS;
        for (XmlElement document : fiche.children("identification")) {
            String type = document.attribute("identificationTypeCode");
            String number = document.attribute("number") != null ? document.attribute("number")
                    : document.attribute("latinNumber");
            subject.addValue(documents.getOrDefault(type, "idNumber"), number);
        }

        // Remarques : celles de la fiche, puis celles des sous-éléments (alias,
        // adresse, document…)
        for (XmlElement remark : fiche.children("remark")) {
            subject.addValue("notes", remark.text());
        }
        for (XmlElement child : fiche.children()) {
            for (XmlElement remark : child.children("remark")) {
                subject.addValue("notes", remark.text());
            }
        }
        dataset.addEntity(subject);

        dataset.addEntity(buildSanction(fiche, id, subject, dataset));
    }

    /** Le premier nom complet devient le nom principal, les suivants des alias. */
    private void addNames(XmlElement fiche, FtmEntity subject, boolean isPerson) {
        boolean first = true;
        for (XmlElement alias : fiche.children("nameAlias")) {
            String wholeName = alias.attribute("wholeName");
            if (wholeName != null) {
                if (first) {
                    subject.addValue("name", wholeName);
                    first = false;
                } else if ("false".equals(alias.attribute("strong"))) {
                    subject.addValue("weakAlias", wholeName); // alias jugé peu fiable par l'UE
                } else {
                    subject.addValue("alias", wholeName);
                }
            }
            if (isPerson) {
                subject.addValue("firstName", alias.attribute("firstName"));
                subject.addValue("middleName", alias.attribute("middleName"));
                subject.addValue("lastName", alias.attribute("lastName"));
                subject.addValue("title", alias.attribute("title"));
                subject.addValue("position", alias.attribute("function"));
                subject.addValue("gender", toFtmGender(alias.attribute("gender")));
            }
        }
    }

    /**
     * Date et lieu de naissance. Les années du calendrier islamique (ex. 1343) ne
     * sont pas converties :
     * elles sont gardées en note pour ne pas créer une fausse date.
     */
    private void addBirth(XmlElement birth, FtmEntity subject) {
        if (birth.attribute("birthdate") != null) {
            subject.addValue("birthDate", birth.attribute("birthdate"));
        } else if ("ISLAMIC".equals(birth.attribute("calendarType"))) {
            subject.addValue("notes", "Année de naissance (calendrier islamique) : " + birth.attribute("year"));
        } else if (birth.attribute("year") != null) {
            String month = birth.attribute("monthOfYear");
            subject.addValue("birthDate",
                    month != null ? birth.attribute("year") + "-" + month : birth.attribute("year"));
        }
        subject.addValue("birthDate", birth.attribute("yearRangeFrom"));
        subject.addValue("birthDate", birth.attribute("yearRangeTo"));
        subject.addValue("birthPlace",
                joinNonBlank(", ", birth.attribute("city"), birth.attribute("region"), birth.attribute("place"),
                        countryName(birth)));
        subject.addValue("birthCountry", birth.attribute("countryIso2Code"));
    }

    /** Adresse complète, pays, et coordonnées (téléphone, e-mail, site web). */
    private void addAddress(XmlElement address, FtmEntity subject) {
        subject.addValue("address",
                joinNonBlank(", ", address.attribute("street"), address.attribute("poBox"),
                        address.attribute("zipCode"),
                        address.attribute("city"), address.attribute("region"), address.attribute("place"),
                        countryName(address)));
        subject.addValue("country", address.attribute("countryIso2Code"));

        for (XmlElement contact : address.children("contactInfo")) {
            String value = contact.attribute("value");
            String key = contact.attribute("key");
            if ("PHONE".equals(key)) {
                subject.addValue("phone", value);
            } else if ("WEB".equals(key)) {
                subject.addValue("website", value);
            } else if ("EMAIL".equals(key) && value != null) {
                for (String email : value.split(";")) { // plusieurs e-mails séparés par ";"
                    subject.addValue("email", email);
                }
            }
        }
    }

    /**
     * Sanction reliée au sujet, avec les références juridiques de l'inscription.
     */
    private FtmEntity buildSanction(XmlElement fiche, String id, FtmEntity subject, FtmDatasetBuilder dataset) {
        FtmEntity sanction = dataset.newEntity("Sanction", id, "sanction")
                .addValue("entity", subject.getId())
                .addValue("authority", "European Union")
                .addValue("authorityId", fiche.attribute("euReferenceNumber")) // ex. "EU.27.28"
                .addValue("unscId", fiche.attribute("unitedNationId")); // référence ONU éventuelle

        for (XmlElement regulation : fiche.children("regulation")) {
            sanction.addValue("program", regulation.attribute("programme")) // ex. "UKR", "IRN"
                    .addValue("programId", regulation.attribute("programme"))
                    .addValue("startDate", regulation.attribute("entryIntoForceDate"))
                    .addValue("listingDate", regulation.attribute("publicationDate"))
                    .addValue("provisions", regulation.attribute("numberTitle")) // ex. "269/2014 (OJ L78)"
                    .addValue("sourceUrl", regulation.childText("publicationUrl"));
        }
        for (XmlElement remark : fiche.children("remark")) {
            sanction.addValue("reason", remark.text());
        }
        sanction.addValue("reason", fiche.attribute("designationDetails"));
        return sanction;
    }

    /** Nom du pays, sauf « UNKNOWN » utilisé par l'UE quand le pays est inconnu. */
    private static String countryName(XmlElement node) {
        String name = node.attribute("countryDescription");
        return "UNKNOWN".equals(name) ? null : name;
    }
}
