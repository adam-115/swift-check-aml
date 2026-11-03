package com.swiftcheck.aml.plugins.depep.federalgovernment;

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
import com.swiftcheck.aml.plugins.depep.federalgovernment.DePepFederalGovernmentParser.Member;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

class DePepFederalGovernmentParserTest {

    @Test
    void testExtractCabinetMembers(@TempDir Path tempDir) throws Exception {
        String html;
        try (InputStream in = getClass().getResourceAsStream("/test_kabinett.html")) {
            assertNotNull(in);
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<Member> members = DePepFederalGovernmentParser.extractMembers(html);
        assertEquals(2, members.size());

        Member merz = members.get(0);
        assertEquals("Friedrich Merz", merz.name());
        assertEquals("Bundeskanzler", merz.role());
        assertTrue(DePepFederalGovernmentParser.isChancellor(merz.role()));

        Member klingbeil = members.get(1);
        assertEquals("Lars Klingbeil", klingbeil.name());
        assertEquals("Bundesminister der Finanzen", klingbeil.role());
        assertTrue(!DePepFederalGovernmentParser.isChancellor(klingbeil.role()));

        Path htmlFile = tempDir.resolve("kabinett.html");
        Files.writeString(htmlFile, html, StandardCharsets.UTF_8);

        ParserContext ctx = new ParserContext("de_pep_federal_government", htmlFile, tempDir, tempDir.resolve("out.ndjson"), Map.of());
        new DePepFederalGovernmentParser().parse(ctx);

        List<SourceRecord> records = new DePepFederalGovernmentParser().readSourceRecords(ctx);
        assertEquals(2, records.size());
    }
}

