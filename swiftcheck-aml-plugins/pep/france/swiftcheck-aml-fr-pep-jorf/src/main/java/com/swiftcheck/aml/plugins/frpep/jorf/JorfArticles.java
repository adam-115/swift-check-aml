package com.swiftcheck.aml.plugins.frpep.jorf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Les articles du Journal officiel gardés par le plugin, tels que publiés par la DILA (un XML {@code ARTICLE} par
 * article). Chaque article porte le titre, la nature et les dates du texte auquel il appartient (bloc
 * {@code CONTEXTE}) : une seule lecture de l'archive suffit, sans assembler les textes.
 *
 * <p>Sélection ({@link #keep}) : un article est gardé si le titre de son texte parle d'une nomination ou d'un départ
 * dans une haute institution, et si l'article lui-même cite l'une d'elles. Les décrets « (magistrature) », très
 * nombreux, ne sont ainsi gardés que pour leurs articles qui parlent de la Cour de cassation.
 */
final class JorfArticles {

    /**
     * Version de la sélection : elle entre dans le nom des extraits du cache. La changer force la relecture des
     * archives (qui doivent alors être téléchargées à nouveau).
     */
    static final int SELECTION_VERSION = 1;

    /** Fichier brut déposé dans {@code rawDir} : un XML par article gardé. */
    static final String ARTICLES_FILE = "jorf-articles.zip";
    /** Fichier brut déposé dans {@code rawDir} : la liste des fichiers de la DILA lus. */
    static final String SOURCES_FILE = "jorf-sources.csv";

    private static final Pattern TITLE_EVENT = Pattern.compile(
            "nomination|retraite|fin de fonctions|cessation de fonctions|designation|radiation|reconduction"
                    + "|renouvellement|maintien");
    private static final Pattern TITLE_INSTITUTION = Pattern.compile(
            "conseil d'etat|cour des comptes|cour de cassation|\\(magistrature\\)|ambassad|representante? permanente?");
    /** Textes qui citent une haute institution sans concerner ses membres. */
    private static final Pattern TITLE_EXCLUDED = Pattern.compile(
            "cour nationale du droit d'asile|attache|jury|titularisation|ecole nationale de la magistrature"
                    + "|chambres? regionales? des comptes\\)|conseil superieur|commission|comite");
    /** « conseil\w* » : « conseiller d'Etat », « conseillère d'Etat » ne contiennent pas « conseil d'Etat ». */
    private static final Pattern CONTENT_INSTITUTION = Pattern.compile(
            "conseil\\w* d'etat|cour des comptes|cour de cassation|ambassad|representante? permanente?");

    private static final Pattern CONTEXT = Pattern.compile(
            "<TEXTE cid=\"([^\"]*)\" date_publi=\"([^\"]*)\" date_signature=\"([^\"]*)\" nature=\"([^\"]*)\"");
    private static final Pattern TITLE = Pattern.compile("<TITRE_TXT[^>]*>([^<]*)</TITRE_TXT>");
    private static final Pattern ID = Pattern.compile("<ID>([^<]*)</ID>");
    private static final Pattern NUM = Pattern.compile("<META_ARTICLE>\\s*<NUM>([^<]*)</NUM>");
    private static final Pattern CONTENT = Pattern.compile("<BLOC_TEXTUEL>\\s*<CONTENU>(.*?)</CONTENU>",
            Pattern.DOTALL);

    private JorfArticles() {
    }

    /**
     * Un article du Journal officiel.
     *
     * @param id            identifiant de l'article (JORFARTI…)
     * @param textId        identifiant du texte (JORFTEXT…), qui donne l'adresse Légifrance
     * @param title         titre complet du texte
     * @param nature        DECRET, ARRETE…
     * @param publication   date de publication au JO (AAAA-MM-JJ)
     * @param signature     date du texte (AAAA-MM-JJ)
     * @param number        numéro de l'article dans le texte (« 1 », « 2 »…), pour l'ordre de lecture
     * @param lines         le texte de l'article, une ligne par paragraphe ou saut de ligne
     */
    record Article(String id, String textId, String title, String nature, String publication, String signature,
            String number, List<String> lines) {

        String url() {
            return "https://www.legifrance.gouv.fr/jorf/id/" + textId;
        }
    }

    /** Vrai si l'article doit être gardé (voir la description de la classe). */
    static boolean keep(String xml) {
        Matcher title = TITLE.matcher(xml);
        if (!title.find()) {
            return false;
        }
        String t = fold(decode(title.group(1)));
        if (!TITLE_EVENT.matcher(t).find() || !TITLE_INSTITUTION.matcher(t).find() || TITLE_EXCLUDED.matcher(t).find()) {
            return false;
        }
        // Textes propres au Conseil d'État ou à la Cour des comptes : tous leurs articles, même ceux qui ne citent pas
        // l'institution (« M. X, conseiller référendaire, est nommé président de chambre régionale des comptes »)
        if (t.contains("(conseil d'etat)") || t.contains("(cour des comptes)")) {
            return true;
        }
        Matcher content = CONTENT.matcher(xml);
        return content.find() && CONTENT_INSTITUTION.matcher(fold(decode(content.group(1)))).find();
    }

    /** Lit un XML {@code ARTICLE} de la DILA. */
    static Article parse(String xml) {
        Matcher context = CONTEXT.matcher(xml);
        Matcher title = TITLE.matcher(xml);
        Matcher id = ID.matcher(xml);
        Matcher num = NUM.matcher(xml);
        Matcher content = CONTENT.matcher(xml);
        if (!context.find() || !title.find() || !id.find()) {
            throw new IllegalArgumentException("Article du JO illisible (CONTEXTE, TITRE_TXT ou ID absent)");
        }
        return new Article(id.group(1), context.group(1), decode(title.group(1)).trim(), context.group(4),
                context.group(2), context.group(3), num.find() ? num.group(1).trim() : "",
                content.find() ? lines(content.group(1)) : List.of());
    }

    /** Contenu XHTML d'un article → lignes de texte (saut à chaque {@code <br/>}, paragraphe ou ligne de tableau). */
    static List<String> lines(String html) {
        String text = html.replaceAll("(?i)<br\\s*/?>|</?p[^>]*>|</?div[^>]*>|</tr>|</li>", "\n")
                .replaceAll("<[^>]+>", " ");
        List<String> lines = new ArrayList<>();
        for (String line : decode(text).split("\n")) {
            // Espaces insécables (U+00A0, U+202F…) : « \s » ne les reconnaît pas
            String l = line.replaceAll("[\\p{Z}\\s]+", " ").trim();
            if (!l.isEmpty()) {
                lines.add(l);
            }
        }
        return lines;
    }

    /** Entités XML et HTML présentes dans les textes de la DILA. */
    static String decode(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);").matcher(s);
        while (m.find()) {
            String e = m.group(1);
            String r = switch (e) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default -> new String(Character.toChars(e.startsWith("#x") ? Integer.parseInt(e.substring(2), 16)
                        : Integer.parseInt(e.substring(1))));
            };
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Minuscules sans accents, apostrophe droite : « Conseil d’État » → « conseil d'etat ». */
    static String fold(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.FRENCH), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('’', '\'');
    }

    /**
     * Écrit les articles (identifiant → XML) dans un zip reproductible : entrées triées, datées de 1970. Deux
     * exécutions sur les mêmes articles donnent la même empreinte, et le core s'arrête alors en UNCHANGED.
     */
    static void writeZip(Path target, SortedMap<String, String> articles) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream file = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Map.Entry<String, String> article : articles.entrySet()) {
                ZipEntry entry = new ZipEntry(article.getKey() + ".xml");
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(article.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Lit un zip écrit par {@link #writeZip} : identifiant de l'article → XML. */
    static SortedMap<String, String> readZip(Path file) throws IOException {
        SortedMap<String, String> articles = new TreeMap<>();
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                String id = entry.getName().replaceFirst("\\.xml$", "");
                articles.put(id, new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return articles;
    }
}
