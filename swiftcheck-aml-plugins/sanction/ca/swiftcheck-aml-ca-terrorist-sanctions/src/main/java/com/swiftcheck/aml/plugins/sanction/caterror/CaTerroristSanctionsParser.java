package com.swiftcheck.aml.plugins.sanction.caterror;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.pf4j.Extension;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugins.sanction.common.AbstractSanctionParser;
import com.swiftcheck.aml.plugins.sanction.common.SanctionRecord;

/**
 * Entités terroristes inscrites en vertu de l'article 83.05 du Code criminel du Canada (Sécurité publique Canada,
 * page « Currently listed entities »). Les biens de ces entités sont gelés de plein droit.
 *
 * <p>Chaque entité est un titre {@code <h2 id="N">} suivi de paragraphes « Also known as », « Description »,
 * « Date listed », « Latest Date Reviewed ». La liste comprend surtout des organisations mais aussi quelques
 * personnes : le schéma {@code LegalEntity} couvre les deux.
 */
@Extension
public class CaTerroristSanctionsParser extends AbstractSanctionParser implements Iparser {

    static final String ID_PREFIX = "ca-terror";
    static final String PAGE = "https://www.publicsafety.gc.ca/cnt/ntnl-scrt/cntr-trrrsm/lstd-ntts/crrnt-lstd-ntts-en.aspx";

    @Override
    protected String idPrefix() {
        return ID_PREFIX;
    }

    @Override
    protected String authority() {
        return "Public Safety Canada";
    }

    @Override
    protected String[] topics() {
        return new String[] { "sanction", "crime.terror" };
    }

    @Override
    protected List<SanctionRecord> read(Path input) throws Exception {
        Document doc = Jsoup.parse(input.toFile(), "UTF-8", PAGE);
        List<SanctionRecord> records = new ArrayList<>();
        for (Element h2 : doc.select("h2[id]")) {
            String id = h2.id();
            String name = h2.text();
            if (!id.matches("[0-9][0-9-]*") || name.isBlank()) {
                continue;
            }
            SanctionRecord r = new SanctionRecord("LegalEntity", id).sourceRef("id=" + id);
            r.add("name", name);
            for (Element p = h2.nextElementSibling(); p != null && !p.tagName().equals("h2"); p = p.nextElementSibling()) {
                Element label = p.selectFirst("strong");
                if (label == null) {
                    continue;
                }
                String key = label.text().trim();
                String value = p.text().substring(Math.min(p.text().length(), label.text().length())).trim();
                switch (key) {
                    case "Also known as" -> aliases(r, value);
                    case "Description" -> r.add("notes", value);
                    case "Date listed" -> r.sanction("listingDate", value);
                    case "Latest Date Reviewed" -> r.sanction("modifiedAt", value);
                    default -> {
                    }
                }
            }
            r.sanction("program", "Criminal Code s. 83.05 (listed terrorist entities)").sanction("sourceUrl", PAGE + "#" + id);
            records.add(r);
        }
        return records;
    }

    /** « A; B; and C. » ou « A, B, C and D » : séparateurs « ; », « , » et « and » final. */
    private static void aliases(SanctionRecord r, String raw) {
        if (raw == null || raw.equalsIgnoreCase("N/A")) {
            return;
        }
        String s = raw.replaceAll("\\.$", "");
        String[] parts = s.contains(";") ? s.split(";\\s*(?:and\\s+)?") : s.split(",\\s*(?:and\\s+)?|\\s+and\\s+(?=[A-Z])");
        for (String p : parts) {
            r.add("alias", p.replaceFirst("^(?i)and\\s+", ""));
        }
    }
}
