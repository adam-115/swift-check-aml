package com.swiftcheck.aml.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class XmlRecordReaderTest {

    private static final String XML = """
            <export xmlns="urn:test">
              <fiche id="1">
                <nom valeur="Ivan"/>
                <nom valeur="Vania"/>
                <note>  premier texte  </note>
              </fiche>
              <autre/>
              <fiche id="2"><note><![CDATA[a & b]]></note></fiche>
            </export>
            """;

    @Test
    void readsEachRecordAsTree() throws Exception {
        List<XmlElement> fiches = new ArrayList<>();
        XmlRecordReader.readEach(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)), "fiche", fiches::add);

        assertEquals(2, fiches.size());
        XmlElement f1 = fiches.get(0);
        assertEquals("1", f1.attribute("id"));
        assertEquals(2, f1.children("nom").size());
        assertEquals("Vania", f1.children("nom").get(1).attribute("valeur"));
        assertEquals("premier texte", f1.childText("note"));
        assertEquals("a & b", fiches.get(1).childText("note"));
    }

    @Test
    void missingValuesAreNullSafe() throws Exception {
        List<XmlElement> fiches = new ArrayList<>();
        XmlRecordReader.readEach(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)), "fiche", fiches::add);

        XmlElement f2 = fiches.get(1);
        assertTrue(f2.child("inexistant").isEmpty());
        assertNull(f2.child("inexistant").attribute("x"));
        assertNull(f2.attribute("absent"));
        assertTrue(f2.children("nom").isEmpty());
    }
}
