package com.swiftcheck.aml.plugins.depep.federalcourts;

import java.util.Map;

/** Paramètres du parser tels qu'ils sont en base (changeSet 155), pour les tests. */
final class TestParameters {

    static final String COURTS = """
            [
             {"key":"bverfg","name":"Bundesverfassungsgericht","acronym":"BVerfG",
              "url":"https://www.bundesverfassungsgericht.de/DE/DasBundesverfassungsgericht/RichterinnenRichter/richterinnenRichter_node.html"},
             {"key":"bgh","name":"Bundesgerichtshof","acronym":"BGH","delayMs":30000,"wikipedia":"Liste_der_Richter_am_Bundesgerichtshof",
              "url":"https://www.bundesgerichtshof.de/DE/DasGericht/Praesidenten/praesidenten_node.html",
              "plan":{"url":"https://www.bundesgerichtshof.de/DE/DasGericht/Geschaeftsverteilung/geschaeftsverteilung_node.html",
                      "links":["/Geschaeftsverteilungsplan\\\\d{4}/[^/]*_node\\\\.html","\\\\.pdf\\\\?__blob=publicationFile"]},
              "search":{"url":"https://www.bundesgerichtshof.de/SiteGlobals/Forms/Suche/Servicesuche_Formular.html?nn=373996&templateQueryString={query}",
                        "query":"\\"{lastName}\\" Richter Bundesgerichtshof ernannt",
                        "link":"/SharedDocs/Pressemitteilungen/DE/\\\\d{4}/\\\\d+\\\\.html","results":2}},
             {"key":"bverwg","name":"Bundesverwaltungsgericht","acronym":"BVerwG","wikipedia":"Liste_der_Richter_am_deutschen_Bundesverwaltungsgericht",
              "url":"https://www.bverwg.de/das-gericht/organisation/der-praesident",
              "plan":{"url":"https://www.bverwg.de/user/pages/03.Rechtsprechung/06.Geschaeftsverteilungsplan/gvp_bverwg.pdf","links":[]}},
             {"key":"bfh","name":"Bundesfinanzhof","acronym":"BFH","delayMs":60000,"wikipedia":"Liste_der_Richter_am_Bundesfinanzhof",
              "url":"https://www.bundesfinanzhof.de/de/gericht/organisation/praesident-und-vizepraesident/",
              "plan":{"url":"https://www.bundesfinanzhof.de/de/gericht/organisation/senate/","links":["GVPl_BFH_Stand_[^/]*\\\\.pdf"]}},
             {"key":"bag","name":"Bundesarbeitsgericht","acronym":"BAG","wikipedia":"Liste_der_Richter_am_Bundesarbeitsgericht",
              "url":"https://www.bundesarbeitsgericht.de/die-arbeitsgerichtsbarkeit/organisation/",
              "plan":{"url":"https://www.bundesarbeitsgericht.de/die-arbeitsgerichtsbarkeit/geschaeftsverteilung/","links":["/gvpl_\\\\d+\\\\.pdf$"]}},
             {"key":"bsg","name":"Bundessozialgericht","acronym":"BSG","delayMs":30000,"wikipedia":"Liste_der_Richter_am_Bundessozialgericht",
              "url":"https://www.bsg.bund.de/DE/Gericht/Praesidentin_Vizepraesident/praesidentin_vizepraesident_node.html",
              "plan":{"url":"https://www.bsg.bund.de/DE/Verfahren/Geschaeftsverteilung/geschaeftsverteilung_node.html",
                      "links":["Geschaeftsverteilungsplan_\\\\d{4}(_\\\\d+)?\\\\.html","\\\\.pdf\\\\?__blob=publicationFile"]}}
            ]""";

    private TestParameters() {
    }

    static Map<String, String> parameters() {
        return Map.of("courts", COURTS, "monthsAfterEnd", "12");
    }

    static DePepFederalCourtsParser.Config config() {
        return DePepFederalCourtsParser.config(parameters());
    }
}
