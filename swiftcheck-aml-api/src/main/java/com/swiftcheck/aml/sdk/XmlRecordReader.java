package com.swiftcheck.aml.sdk;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Lecture simple des gros fichiers XML, fiche par fiche.
 *
 * <p>Le fichier est lu en flux (il n'est jamais chargé entièrement en mémoire), mais chaque fiche
 * (par exemple {@code <sanctionEntity>} pour l'UE ou {@code <INDIVIDUAL>} pour l'ONU) est remise au parser
 * sous forme d'un petit arbre {@link XmlElement} facile à parcourir.
 *
 * <pre>{@code
 * XmlRecordReader.readEach(fichier, "sanctionEntity", fiche -> {
 *     String id = fiche.attribute("logicalId");
 *     for (XmlElement nom : fiche.children("nameAlias")) { ... }
 * });
 * }</pre>
 */
public final class XmlRecordReader {

    /** Action à exécuter pour chaque fiche lue. */
    @FunctionalInterface
    public interface RecordHandler {
        void handle(XmlElement record) throws Exception;
    }

    private XmlRecordReader() {
    }

    /**
     * Parcourt le fichier et appelle {@code handler} pour chaque élément nommé {@code recordName},
     * où qu'il se trouve dans le document.
     */
    public static void readEach(InputStream xml, String recordName, RecordHandler handler) throws Exception {
        readEach(xml, Set.of(recordName), handler);
    }

    /**
     * Variante pour les fichiers qui contiennent plusieurs types de fiches
     * (ex. {@code INDIVIDUAL} et {@code ENTITY} dans la liste de l'ONU).
     */
    public static void readEach(InputStream xml, Set<String> recordNames, RecordHandler handler) throws Exception {
        XMLStreamReader reader = newFactory().createXMLStreamReader(xml);
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && recordNames.contains(reader.getLocalName())) {
                    handler.handle(readElement(reader));
                }
            }
        } finally {
            reader.close();
        }
    }

    /**
     * Construit l'arbre de l'élément courant (le lecteur est positionné sur sa balise ouvrante)
     * jusqu'à sa balise fermante.
     */
    private static XmlElement readElement(XMLStreamReader reader) throws XMLStreamException {
        Deque<XmlElement> stack = new ArrayDeque<>();
        XmlElement root = startNode(reader);
        stack.push(root);
        while (!stack.isEmpty() && reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                XmlElement child = startNode(reader);
                stack.peek().addChild(child);
                stack.push(child);
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                stack.peek().appendText(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                stack.pop();
            }
        }
        return root;
    }

    private static XmlElement startNode(XMLStreamReader reader) {
        XmlElement node = new XmlElement(reader.getLocalName());
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            node.putAttribute(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
        }
        return node;
    }

    private static XMLInputFactory newFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // Sécurité : pas d'entités externes ni de DTD (protection contre les attaques XXE).
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }
}
