package com.swiftcheck.aml.plugins.depep.bundesrat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.depep.bundesrat.DePepBundesratParser.Member;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class DePepBundesratParserTest {

    @Test
    void testExtractMembers(@TempDir Path tempDir) throws Exception {
        String html;
        try (InputStream in = getClass().getResourceAsStream("/test_bundesrat.html")) {
            assertNotNull(in);
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<Member> members = DePepBundesratParser.extractMembers(html);
        assertEquals(2, members.size());

        Member wuest = members.get(0);
        assertEquals("wuest-hendrik", wuest.slug());
        assertEquals("Hendrik Wüst", wuest.name());
        assertEquals("CDU", wuest.party());
        assertEquals("Nordrhein-Westfalen", wuest.state());
        assertTrue(DePepBundesratParser.isMinisterPresident(wuest.function()));

        Member crumbach = members.get(1);
        assertEquals("crumbach-robert", crumbach.slug());
        assertEquals("Robert Crumbach", crumbach.name());
        assertEquals("SPD", crumbach.party());
        assertEquals("Brandenburg", crumbach.state());
        assertTrue(!DePepBundesratParser.isMinisterPresident(crumbach.function()));

        Path htmlFile = tempDir.resolve("bundesrat.html");
        Files.writeString(htmlFile, html, StandardCharsets.UTF_8);

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(DePepBundesratParser.ID_PREFIX, DePepBundesratParser.TOPIC);
        for (Member m : members) {
            boolean isMp = DePepBundesratParser.isMinisterPresident(m.function());
            // Test writing logic directly by parsing with ParserContext
        }

        ParserContext ctx = new ParserContext("de_pep_bundesrat", htmlFile, tempDir, tempDir.resolve("out.ndjson"), Map.of());
        new DePepBundesratParser().parse(ctx);

        List<SourceRecord> records = new DePepBundesratParser().readSourceRecords(ctx);
        assertEquals(2, records.size());
    }
}

