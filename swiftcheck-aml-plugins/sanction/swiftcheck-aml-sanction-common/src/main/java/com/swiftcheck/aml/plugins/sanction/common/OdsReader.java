package com.swiftcheck.aml.plugins.sanction.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Lecture minimale d'un classeur OpenDocument (.ods) : archive ZIP dont {@code content.xml} contient les feuilles.
 * Rend la première feuille sous forme de lignes de texte ; les cellules répétées
 * ({@code table:number-columns-repeated}) sont dépliées, sauf en fin de ligne.
 */
public final class OdsReader {

    private static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";

    private OdsReader() {
    }

    public static List<List<String>> firstSheet(Path file) throws IOException {
        return firstSheet(Files.readAllBytes(file));
    }

    public static List<List<String>> firstSheet(byte[] ods) throws IOException {
        byte[] content = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(ods))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if ("content.xml".equals(e.getName())) {
                    content = zip.readAllBytes();
                }
            }
        }
        if (content == null) {
            throw new IOException("Le fichier n'est pas un classeur .ods (content.xml absent)");
        }
        try {
            return parse(content);
        } catch (XMLStreamException ex) {
            throw new IOException("content.xml illisible", ex);
        }
    }

    private static List<List<String>> parse(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader r = f.createXMLStreamReader(new ByteArrayInputStream(xml));
        List<List<String>> rows = new ArrayList<>();
        List<String> row = null;
        StringBuilder cell = null;
        int repeat = 1;
        int tables = 0;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("table") && TABLE.equals(r.getNamespaceURI())) {
                    tables++;
                } else if (tables == 1 && n.equals("table-row")) {
                    row = new ArrayList<>();
                } else if (row != null && (n.equals("table-cell") || n.equals("covered-table-cell"))) {
                    cell = new StringBuilder();
                    String rep = r.getAttributeValue(TABLE, "number-columns-repeated");
                    repeat = rep == null ? 1 : Math.min(Integer.parseInt(rep), 200);
                } else if (cell != null && n.equals("p") && cell.length() > 0) {
                    cell.append('\n');
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && cell != null) {
                cell.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String n = r.getLocalName();
                if (row != null && (n.equals("table-cell") || n.equals("covered-table-cell")) && cell != null) {
                    String text = cell.toString().trim();
                    for (int i = 0; i < repeat; i++) {
                        row.add(text.isEmpty() ? null : text);
                    }
                    cell = null;
                } else if (n.equals("table-row") && row != null) {
                    while (!row.isEmpty() && row.get(row.size() - 1) == null) {
                        row.remove(row.size() - 1);
                    }
                    rows.add(row);
                    row = null;
                } else if (n.equals("table") && TABLE.equals(r.getNamespaceURI()) && tables == 1) {
                    return rows;
                }
            }
        }
        return rows;
    }
}
