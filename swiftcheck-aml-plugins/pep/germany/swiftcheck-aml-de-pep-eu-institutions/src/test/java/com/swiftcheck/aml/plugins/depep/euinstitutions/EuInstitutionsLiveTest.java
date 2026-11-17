package com.swiftcheck.aml.plugins.depep.euinstitutions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;

/** Exécution réelle sur les pages officielles ; lancée à la main avec {@code -Dlive.raw=…} (dossier des pages lues). */
@EnabledIfSystemProperty(named = "live.raw", matches = ".+")
class EuInstitutionsLiveTest {

    static final Map<String, String> PARAMETERS = Map.of(
            "delayMs", "500",
            "cjUrl", "https://curia.europa.eu/jcms/jcms/Jo2_7026/fr/",
            "gcUrl", "https://curia.europa.eu/jcms/jcms/Jo2_7035/fr/",
            "ecaUrl", "https://www.eca.europa.eu/ContentPagesDocuments/Organisation/COMPOSITION_EN.pdf",
            "ecbBoardUrl", "https://www.ecb.europa.eu/ecb/orga/decisions/eb/html/index.fr.html",
            "ssmUrl", "https://www.bankingsupervision.europa.eu/about/organisation/leadership/supervisoryboard/html/index.en.html",
            "councilSgUrl", "https://www.consilium.europa.eu/fr/general-secretariat/secretary-general/",
            "councilSgBioUrl", "https://www.consilium.europa.eu/fr/general-secretariat/secretary-general/biography/",
            "knownNationals", "[{\"name\":\"Isabel Schnabel\",\"evidence\":\"candidate du gouvernement allemand, recommandée par l'Eurogroupe en 2019\"}]");

    @Test
    void readsTheOfficialPages() throws Exception {
        Path raw = Path.of(System.getProperty("live.raw"));
        Files.createDirectories(raw);
        ParserResult result = new DePepEuInstitutionsParser().parse(new ParserContext("de_pep_eu_institutions", null, raw,
                raw.resolve("out.ndjson"), PARAMETERS));
        System.out.println("LIVE " + result.message());
    }
}
