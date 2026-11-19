package com.swiftcheck.aml.plugins.sanction.nl;

import static com.swiftcheck.aml.sdk.TextUtils.joinNonBlank;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.OdsReader;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;

/**
 * Liste nationale néerlandaise des personnes et organisations dont les avoirs sont gelés pour terrorisme
 * (Sanctieregeling terrorisme 2007-II, décision du ministre des Affaires étrangères), publiée par
 * rijksoverheid.nl au format ODS ({@code ned-terrorismelijst.ods}).
 *
 * <p>La liste n'a pas de numéro de fiche : la clé est nom + prénoms + date de naissance.
 */
@Extension
public class NlTerrorSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "nl-terror";

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "Ministry of Foreign Affairs of the Netherlands";
    }

    @Override
    protected String[] topics() {
        return new String[] { "sanction", "crime.terror" };
    }

    @Override
    protected List<SanctionRecord> read(Path input) throws Exception {
        List<List<String>> sheet = OdsReader.firstSheet(input);
        int header = -1;
        for (int i = 0; i < sheet.size() && header < 0; i++) {
            if (!sheet.get(i).isEmpty() && "achternaam".equalsIgnoreCase(String.valueOf(sheet.get(i).get(0)).trim())) {
                header = i;
            }
        }
        if (header < 0) {
            throw new IllegalStateException("En-tête « Achternaam » introuvable : le format de la liste a changé");
        }
        List<String> columns = sheet.get(header);
        int last = 0, first = col(columns, "voornaam"), alias = col(columns, "alias"), dob = col(columns, "geboortedatum"),
                pob = col(columns, "geboorteplaats"), decision = col(columns, "beslissing"), link = col(columns, "bekendmaking");
        List<SanctionRecord> records = new ArrayList<>();
        for (int i = header + 1; i < sheet.size(); i++) {
            List<String> row = sheet.get(i);
            String lastName = cell(row, last);
            if (lastName == null) {
                continue;
            }
            String firstName = cell(row, first);
            String birth = cell(row, dob);
            boolean organization = firstName == null && birth == null;
            SanctionRecord r = new SanctionRecord(organization ? "Organization" : "Person", lastName, firstName, birth)
                    .sourceRef("ligne " + (i + 1));
            r.add("name", joinNonBlank(" ", firstName, lastName));
            if (!organization) {
                r.add("firstName", firstName);
                r.add("lastName", lastName);
                r.add("birthDate", birth);
                r.add("birthPlace", cell(row, pob));
            }
            String aliases = cell(row, alias);
            if (aliases != null) {
                for (String a : aliases.split("[;\n]")) {
                    r.add("alias", a);
                }
            }
            r.sanction("program", "Sanctieregeling terrorisme 2007-II")
                    .sanction("listingDate", cell(row, decision))
                    .sanction("provisions", cell(row, link))
                    .sanction("sourceUrl", "https://www.rijksoverheid.nl/documenten/rapporten/2015/08/27/nationale-terrorismelijst");
            records.add(r);
        }
        return records;
    }

    private static int col(List<String> header, String start) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i) != null && header.get(i).toLowerCase(Locale.ROOT).contains(start)) {
                return i;
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int index) {
        return index < 0 || index >= row.size() ? null : row.get(index);
    }
}
