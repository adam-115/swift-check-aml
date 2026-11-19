package com.swiftcheck.aml.plugins.sanction.ca;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

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
 * Liste consolidée des sanctions autonomes du Canada (Affaires mondiales Canada, fichier {@code sema-lmes.xml}) :
 * personnes, entités et navires visés par les règlements pris en vertu de la Loi sur les mesures économiques
 * spéciales (LMES) et de la Loi sur la justice pour les victimes de dirigeants étrangers corrompus.
 *
 * <p>La colonne « Country-Pays » est le régime (Russie, Iran, JVCFOR…), pas la nationalité. La clé stable d'une
 * fiche est régime + annexe + numéro d'article.
 */
@Extension
public class CaSemaSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "ca-sema";

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "Global Affairs Canada";
    }

    @Override
    protected List<SanctionRecord> read(Path input) throws Exception {
        try (InputStream in = Files.newInputStream(input)) {
            return read(in);
        }
    }

    List<SanctionRecord> read(InputStream in) throws Exception {
        List<SanctionRecord> records = new ArrayList<>();
        XmlRecordReader.readEach(in, "record", rec -> {
            SanctionRecord r = convert(rec);
            if (r != null) {
                records.add(r);
            }
        });
        return records;
    }

    private static SanctionRecord convert(XmlElement rec) {
        String regime = rec.childText("Country-Pays");
        String item = rec.childText("Item-NumeroDarticle");
        String schedule = rec.childText("Schedule-Annexe");
        if (regime == null || item == null) {
            return null;
        }
        String regimeKey = regime.split(" / ")[0].replaceAll("\\(.*?\\)", "").trim();
        String entityName = rec.childText("EntityOrShip-EntiteOuNavire");
        String imo = rec.childText("ShipIMONumber-NumeroOMIDuNavire");
        String given = rec.childText("GivenName-Prenom");
        String last = rec.childText("LastName-NomDeFamille");
        String schema = imo != null ? "Vessel" : entityName != null && given == null && last == null ? "Organization" : "Person";
        String date = rec.childText("DateOfBirthOrShipBuildDate-DateDeNaissanceOuDateDeConstructionDuNavire");
        String title = rec.childText("TitleOrShipType-TitreOuTypeDeNavire");

        SanctionRecord r = new SanctionRecord(schema, regimeKey, schedule, item)
                .sourceRef(regimeKey + ", annexe " + schedule + ", article " + item);
        if ("Person".equals(schema)) {
            r.add("name", joinNonBlank(" ", given, last));
            r.add("firstName", given);
            r.add("lastName", last);
            r.add("alias", entityName);
            r.addAll("birthDate", Dates.parse(date));
            r.add("title", title);
        } else {
            r.add("name", entityName);
            if ("Vessel".equals(schema)) {
                r.add("imoNumber", imo);
                r.add("type", title == null ? null : title.split("\\|")[0].trim());
                r.addAll("buildDate", Dates.parse(date));
            } else {
                r.add("notes", title);
            }
        }
        String aliases = rec.childText("Aliases-Alias");
        if (aliases != null) {
            // Personnes : alias séparés par des virgules ; entités : la virgule fait partie des noms (« Co., Ltd »).
            String separators = "Person".equals(schema) ? "(?i)\\s+or\\s+|[;,]" : "(?i)\\s+or\\s+|;";
            for (String alias : aliases.split(separators)) {
                r.add("alias", alias.replaceFirst("^\\s*[A-Z][a-z]+:\\s*", "")); // « Russian: Руслан… »
            }
        }
        r.sanction("program", regime)
                .sanction("provisions", schedule == null ? null : "Schedule " + schedule + ", item " + item)
                .sanction("listingDate", rec.childText("DateOfListing-DateDinscription"))
                .sanction("sourceUrl", "https://www.international.gc.ca/world-monde/international_relations-relations_internationales/sanctions/consolidated-consolide.aspx");
        return r;
    }
}
