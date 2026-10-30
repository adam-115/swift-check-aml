package com.swiftcheck.aml.plugins.frpep.euinstitutions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Lit les membres de chaque institution dans ses pages officielles, avec leur nationalité <b>quand la source la
 * donne</b>, ou ce qui permet de la déduire. Seule l'institution de la liste C/2023/611 est gardée.
 */
final class EuReaders {

    /**
     * Un membre d'une institution.
     *
     * @param name        nom tel qu'écrit
     * @param function    fonction (« Juge au Tribunal », « Membre de la Cour des comptes européenne »…)
     * @param institution institution
     * @param french      vrai si la source permet de le dire français
     * @param evidence    sur quoi repose la nationalité (« pays indiqué : France », « née en France »…), ou la
     *                    raison du rejet
     * @param sourceFile  fichier brut où il a été lu
     */
    record Member(String name, String function, String institution, boolean french, String evidence,
            String sourceFile) {
    }

    private static final Pattern BORN = Pattern.compile("^Née? (?:le .*?|en \\d{4}) à [^(]*\\(([^)]*)\\)");
    private static final Pattern COUNTRY_IN_PARENS = Pattern.compile("\\(([^()]*)\\)");
    private static final Pattern STATED_FRENCH = Pattern.compile("nationalité[^.]*français|nationalité[^.]*française");
    /** Fin de la biographie d'un membre dans les pages de la CJUE. */
    private static final Pattern BIO_END = Pattern.compile(
            "^(?:AFFICHER MOINS|Affiliations|Titres et distinctions|Déclaration d|Biographie et carrière)");
    /** Au moins tant de mentions « (France) » dans la biographie, et deux fois plus que tout autre pays. */
    static final int CAREER_MIN_MENTIONS = 4;

    private EuReaders() {
    }

    static String text(Path file) throws IOException {
        return PageText.of(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Membres de la Cour de justice ou du Tribunal : chaque fiche donne le nom, la fonction, puis « Biographie et
     * carrière » et la biographie (« Né en 1967 à Voiron (France), M. Stéphane Gervasoni… »). La nationalité n'y
     * est pas écrite ; elle est déduite par {@link #frenchFromBiography}.
     */
    static List<Member> cjue(Path file, String court) throws IOException {
        List<String> lines = List.of(text(file).split("\n"));
        List<Member> members = new ArrayList<>();
        for (int i = 2; i < lines.size(); i++) {
            if (!lines.get(i).equals("Biographie et carrière")) {
                continue;
            }
            String name = lines.get(i - 2);
            String role = lines.get(i - 1);
            StringBuilder bio = new StringBuilder();
            for (int j = i + 1; j < lines.size() && !BIO_END.matcher(lines.get(j)).find(); j++) {
                bio.append(lines.get(j)).append(' ');
            }
            String evidence = frenchFromBiography(bio.toString());
            members.add(new Member(name, cjueFunction(role, court), "Cour de justice de l'Union européenne",
                    evidence != null, evidence, file.getFileName().toString()));
        }
        return members;
    }

    /** « Président de la VIème chambre » au Tribunal → « Juge au Tribunal (président de la VIème chambre) ». */
    private static String cjueFunction(String role, String court) {
        String r = role.toLowerCase(java.util.Locale.FRENCH);
        if (r.startsWith("avocat")) {
            return "Avocat général à la Cour de justice";
        }
        if (r.startsWith("greffi")) {
            return "Greffier " + (court.equals("Tribunal") ? "du Tribunal" : "de la Cour de justice");
        }
        String base = court.equals("Tribunal") ? "Juge au Tribunal" : "Juge à la Cour de justice";
        return r.startsWith("juge") ? base : base + " (" + r + ")";
    }

    /**
     * Nationalité française d'après une biographie, ou {@code null} :
     * <ol>
     *   <li>nationalité française écrite (« possède la double nationalité chypriote et française ») ;</li>
     *   <li>naissance en France (« Né en 1962 à Paris (France) ») ;</li>
     *   <li>carrière en France : au moins {@value #CAREER_MIN_MENTIONS} mentions « (France) », et deux fois plus que
     *       tout autre pays (M. Raphaël Meyer, né en Suisse, juge français au Tribunal).</li>
     * </ol>
     * Ce n'est pas une nationalité officielle : la règle et sa raison sont écrites dans l'entité.
     */
    static String frenchFromBiography(String bio) {
        if (STATED_FRENCH.matcher(bio).find()) {
            return "nationalité française écrite dans la biographie";
        }
        Matcher born = BORN.matcher(bio.trim());
        if (born.find() && born.group(1).trim().equals("France")) {
            return "né(e) en France (biographie officielle)";
        }
        Map<String, Integer> counts = new HashMap<>();
        Matcher m = COUNTRY_IN_PARENS.matcher(bio);
        while (m.find()) {
            String[] parts = m.group(1).split(",");
            String country = parts[parts.length - 1].trim();
            if (country.matches("\\p{Lu}[\\p{L}' -]{2,28}")) {
                counts.merge(country, 1, Integer::sum);
            }
        }
        int france = counts.getOrDefault("France", 0);
        int other = counts.entrySet().stream().filter(e -> !e.getKey().equals("France"))
                .mapToInt(Map.Entry::getValue).max().orElse(0);
        if (france >= CAREER_MIN_MENTIONS && france >= 2 * other) {
            return "carrière en France (" + france + " mentions dans la biographie officielle)";
        }
        return null;
    }

    /** Cour des comptes européenne : le PDF de composition donne chaque membre (« Pierre MOSCOVICI ») puis son pays. */
    static List<Member> eca(Path pdf) throws IOException {
        List<String> lines = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            for (String line : new PDFTextStripper().getText(doc).split("\\R")) {
                String l = line.replaceAll("[\\p{Z}\\s]+", " ").trim();
                if (!l.isEmpty()) {
                    lines.add(l);
                }
            }
        }
        List<Member> members = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            String name = lines.get(i);
            String country = lines.get(i + 1);
            // Nom : au moins un mot en majuscules ; pays : un mot ou deux, sans chiffre
            if (name.matches(".*\\b\\p{Lu}{2,}.*") && !name.contains(":") && !name.contains("@")
                    && country.matches("\\p{Lu}[\\p{Ll}]+(?: [\\p{Lu}\\p{Ll}][\\p{Ll}]+)?")) {
                members.add(new Member(name, "Membre de la Cour des comptes européenne", "Cour des comptes européenne",
                        country.equals("France"), "pays indiqué par la Cour : " + country, pdf.getFileName().toString()));
                i++;
            }
        }
        return members;
    }

    /**
     * Directoire de la BCE : la page donne « Christine Lagarde » puis « présidente de la BCE » ; le CV de chaque
     * membre donne « Lieu de naissance » puis « Paris, France ».
     */
    static List<Member> ecbBoard(Path rawDir) throws IOException {
        Path board = rawDir.resolve(EuSources.ECB_BOARD_FILE);
        if (!Files.exists(board)) {
            return List.of();
        }
        Map<String, String> birthplaces = new HashMap<>();
        try (var files = Files.list(rawDir)) {
            for (Path cv : files.filter(f -> f.getFileName().toString().startsWith(EuSources.ECB_CV_PREFIX)).toList()) {
                String[] lines = text(cv).split("\n");
                String name = null;
                for (int i = 0; i + 1 < lines.length; i++) {
                    if (name == null && lines[i + 1].matches("(?i)(?:la |le )?(?:présidente?|vice-présidente?|membre)"
                            + " (?:de la|du directoire).*")) {
                        name = lines[i];
                    }
                    if (lines[i].startsWith("Lieu de naissance") && name != null) {
                        birthplaces.put(name, lines[i + 1].trim());
                    }
                }
            }
        }
        List<Member> members = new ArrayList<>();
        String[] lines = text(board).split("\n");
        for (int i = 0; i + 1 < lines.length; i++) {
            String role = lines[i + 1];
            if (role.matches("(?i)(?:présidente?|vice-présidente?|membre du directoire) de la BCE\\s*")) {
                String place = birthplaces.get(lines[i]);
                boolean french = place != null && place.endsWith(", France");
                members.add(new Member(lines[i], capitalize(role.trim()), "Banque centrale européenne", french,
                        place == null ? "lieu de naissance absent du CV" : "né(e) à " + place + " (CV officiel)",
                        EuSources.ECB_BOARD_FILE));
            }
        }
        return members;
    }

    /**
     * Conseil de surveillance prudentielle de la BCE : chaque représentant national est suivi de son autorité. Le
     * représentant de la Banque de France ou de l'ACPR est le membre français.
     */
    static List<Member> ssm(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        String[] lines = text(file).split("\n");
        List<Member> members = new ArrayList<>();
        for (int i = 0; i + 1 < lines.length; i++) {
            String authority = lines[i + 1];
            if (authority.matches("Banque de France|Autorité de contrôle prudentiel et de résolution.*|ACPR")
                    && !lines[i].matches("N\\.\\s*N\\.?")) {
                members.add(new Member(lines[i], "Membre du conseil de surveillance prudentielle de la BCE",
                        "Banque centrale européenne", true, "représentant de l'autorité française (" + authority + ")",
                        file.getFileName().toString()));
            }
        }
        return members;
    }

    /** Secrétaire général du Conseil : nom sur la page (« Thérèse Blanchet / est l'actuelle secrétaire générale »), nationalité dans la biographie. */
    static List<Member> councilSecretaryGeneral(Path page, Path bio) throws IOException {
        if (!Files.exists(page)) {
            return List.of();
        }
        String[] lines = text(page).split("\n");
        String name = null;
        for (int i = 0; i + 1 < lines.length; i++) {
            if (lines[i + 1].startsWith("est l'actuel") || lines[i + 1].startsWith("est l’actuel")) {
                name = lines[i];
            }
        }
        if (name == null) {
            return List.of();
        }
        String nationality = null;
        if (Files.exists(bio)) {
            String[] b = text(bio).split("\n");
            for (int i = 0; i + 1 < b.length; i++) {
                if (b[i].startsWith("Nationalité")) {
                    nationality = (b[i] + " " + b[i + 1]).replaceFirst("^Nationalité\\s*:?\\s*:?\\s*", "").trim();
                }
            }
        }
        boolean french = nationality != null && nationality.toLowerCase(java.util.Locale.FRENCH).startsWith("français");
        return List.of(new Member(name, "Secrétaire général du Conseil de l'Union européenne", "Conseil de l'Union européenne",
                french, nationality == null ? "nationalité absente de la biographie" : "nationalité indiquée : " + nationality,
                page.getFileName().toString()));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
