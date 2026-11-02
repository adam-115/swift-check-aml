package com.swiftcheck.aml.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class WikidataImagesTest {

    /** Réponse réelle de query.wikidata.org (novembre 2026) pour P1186 = 124831. */
    private static final String TSV = "?id\t?image\n"
            + "\"124831\"\t<http://commons.wikimedia.org/wiki/Special:FilePath/Isabella%20Adinolfi%2C%202023.09.09%20%28cropped%29.jpg>\n";

    @Test
    void readsTheImageOfEachIdentifier() throws Exception {
        List<String> queries = new ArrayList<>();
        WikidataImages.Result result = WikidataImages.byExternalId("P1186", List.of("124831", "999"), q -> {
            queries.add(q);
            return TSV;
        });

        assertEquals(Map.of("124831", "https://commons.wikimedia.org/wiki/Special:FilePath/"
                + "Isabella%20Adinolfi%2C%202023.09.09%20%28cropped%29.jpg"), result.images());
        assertEquals(TSV, result.raw(), "réponse brute, à archiver");
        assertTrue(queries.get(0).contains("VALUES ?id { \"124831\" \"999\" }"));
        assertTrue(queries.get(0).contains("wdt:P1186 ?id ; wdt:P18 ?img"));
    }

    @Test
    void identifiersAreSentByBatchAndUnsafeOnesAreDropped() throws Exception {
        List<String> ids = new ArrayList<>(IntStream.range(0, 300).mapToObj(String::valueOf).toList());
        ids.add("1\" } ?x ?y ?z . { \"");                 // ne doit jamais entrer dans la requête
        List<String> queries = new ArrayList<>();
        WikidataImages.byExternalId("P1186", ids, q -> {
            queries.add(q);
            return "?id\t?image\n";
        });
        assertEquals(2, queries.size(), "250 + 50");
        assertTrue(queries.stream().noneMatch(q -> q.contains("?x ?y ?z")));
        assertThrows(IllegalArgumentException.class, () -> WikidataImages.byExternalId("P18 } #", ids, q -> ""));
    }

    @Test
    void personsKnownByTheirQid() throws Exception {
        // Réponse réelle (novembre 2026) : Q3052772 = Emmanuel Macron
        String tsv = "?id\t?image\n\"Q3052772\"\t<http://commons.wikimedia.org/wiki/Special:FilePath/"
                + "Emmanuel%20Macron%202025%20%28cropped%29.jpg>\n";
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("fr-pep-test", "role.pep");
        FtmEntity person = dataset.newEntity("Person", "Q3052772");
        person.addValue("name", "Emmanuel Macron");
        dataset.addEntity(person);
        List<String> queries = new ArrayList<>();

        String note = WikidataImages.addPhotos(dataset, WikidataImages.QID, List.of("Q3052772", "Q999", "1 } ?x"), q -> {
            queries.add(q);
            return tsv;
        }, null);

        assertEquals("Photos Wikimedia Commons : 1 sur 1.", note, "Q999 n'est pas dans le dataset");
        assertTrue(queries.get(0).contains("VALUES ?item { wd:Q3052772 }"));
        assertEquals(person.getId(), dataset.media().get(0).entityId());
        assertEquals("", WikidataImages.addPhotos(dataset, WikidataImages.QID, List.of("Q3052772"), null, null),
                "point SPARQL désactivé");
    }

    @Test
    void onlyCommonsFilesAreKept() {
        assertNull(WikidataImages.commonsUrl("http://example.org/photo.jpg"));
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/A.jpg",
                WikidataImages.commonsUrl("http://commons.wikimedia.org/wiki/Special:FilePath/A.jpg"));
        assertEquals("https://commons.wikimedia.org/wiki/File:A.jpg",
                WikidataImages.filePage("https://commons.wikimedia.org/wiki/Special:FilePath/A.jpg"));
    }
}
