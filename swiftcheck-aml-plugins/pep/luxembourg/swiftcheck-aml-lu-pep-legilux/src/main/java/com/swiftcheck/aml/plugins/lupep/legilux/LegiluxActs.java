package com.swiftcheck.aml.plugins.lupep.legilux;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import com.swiftcheck.aml.sdk.CsvRecordReader;

/**
 * Lecture des fichiers bruts déposés dans {@code rawDir} : la liste des actes (CSV du point SPARQL) et leurs
 * textes (archive ZIP des XML Akoma Ntoso de Legilux).
 *
 * <p>Le texte utile d'un acte est dans les paragraphes {@code <p>} de {@code <body>} :
 * <pre>{@code
 * <body><alinea><content>
 *   <p>Par arrêté grand-ducal du 24 juillet 2026, Monsieur Stephen DE RON, maître en droit, est nommé membre
 *      du Conseil d’État, avec effet au 15 septembre 2026.</p>
 * </content></alinea></body>
 * }</pre>
 * Un paragraphe peut contenir des balises (ex. {@code <ref>} vers un autre acte) : tout son texte est gardé.
 */
final class LegiluxActs {

    /** Réponse du point SPARQL : colonnes act, date, title. */
    static final String ACT_LIST_FILE = "legilux-actes.csv";

    /** Textes XML des actes, une entrée par acte. */
    static final String TEXTS_FILE = "legilux-textes.zip";

    /** Un acte de la liste : identifiant ELI, date de l'arrêté, titre (ex. « Conseil d’État - Nomination. »). */
    record Act(String uri, String date, String title) {
    }

    private LegiluxActs() {
    }

    static List<Act> readActList(Path csv) throws Exception {
        List<Act> acts = new ArrayList<>();
        CsvRecordReader.readEach(csv, row -> {
            if (row.get("act") == null) {
                throw new IllegalStateException("Réponse SPARQL inattendue : colonne « act » absente (ligne "
                        + row.lineNumber() + ")");
            }
            acts.add(new Act(row.get("act"), row.get("date"), row.get("title")));
        });
        return acts;
    }

    /**
     * Textes des actes, par nom d'entrée de l'archive ({@link #textEntryName}). Un paragraphe par ligne.
     * Les actes sans texte n'ont pas d'entrée.
     */
    static Map<String, String> readTexts(Path zipFile) throws Exception {
        Map<String, String> texts = new HashMap<>();
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                try (InputStream xml = zip.getInputStream(entry)) {
                    texts.put(entry.getName(), bodyText(xml));
                }
            }
        }
        return texts;
    }

    /** Texte des paragraphes {@code <p>} du corps de l'acte, un par ligne. */
    static String bodyText(InputStream xml) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);             // pas d'entité externe
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader reader = factory.createXMLStreamReader(xml);
        StringBuilder text = new StringBuilder();
        boolean inBody = false;
        int paragraphDepth = 0;                                  // > 0 : à l'intérieur d'un <p> du corps
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if ("body".equals(reader.getLocalName())) {
                        inBody = true;
                    } else if (inBody && "p".equals(reader.getLocalName())) {
                        paragraphDepth++;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("body".equals(reader.getLocalName())) {
                        inBody = false;
                    } else if (inBody && "p".equals(reader.getLocalName())) {
                        paragraphDepth--;
                        text.append('\n');
                    }
                } else if (paragraphDepth > 0
                        && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
                    text.append(reader.getText());
                }
            }
        } finally {
            reader.close();
        }
        return text.toString().trim();
    }

    /**
     * Chemin ELI d'un acte, ex. {@code http://data.legilux.public.lu/eli/etat/adm/pa/2026/07/24/b3381/jo}
     * → {@code etat/adm/pa/2026/07/24/b3381/jo}.
     */
    static String eliPath(String actUri) {
        int start = actUri.indexOf("/eli/");
        if (start < 0) {
            throw new IllegalStateException("Identifiant d'acte inattendu (pas d'ELI) : " + actUri);
        }
        return actUri.substring(start + "/eli/".length());
    }

    /** Nom du fichier XML d'un acte, ex. {@code eli-etat-adm-pa-2026-07-24-b3381-jo-fr-xml.xml}. */
    static String textEntryName(String actUri) {
        return "eli-" + eliPath(actUri).replace('/', '-') + "-fr-xml.xml";
    }

    /** Adresse du texte XML d'un acte, sur le même serveur que son identifiant (dossier {@code filestore}). */
    static URI textUri(String actUri) {
        URI act = URI.create(actUri);
        return URI.create("https://" + act.getHost() + "/filestore/eli/" + eliPath(actUri) + "/fr/xml/"
                + textEntryName(actUri));
    }

    /** Référence courte d'un acte pour les ID et les messages, ex. {@code pa-2026-07-24-b3381}. */
    static String shortRef(String actUri) {
        return eliPath(actUri).replaceFirst("^etat/adm/", "").replaceFirst("/jo$", "").replace('/', '-');
    }
}
