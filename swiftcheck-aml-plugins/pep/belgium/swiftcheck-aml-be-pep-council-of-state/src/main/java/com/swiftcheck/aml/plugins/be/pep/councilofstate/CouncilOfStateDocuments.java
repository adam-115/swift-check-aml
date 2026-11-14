package com.swiftcheck.aml.plugins.be.pep.councilofstate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture du texte des documents officiels du Conseil d'État (texte déjà extrait des PDF ou du HTML).
 * <ul>
 *   <li>compositions des chambres, francophones ({@code chambres.pdf}) et néerlandophones ({@code kamers.pdf}) :
 *       noms sous la forme « initiales + nom » (« P.-O. de Broux », « J. VAN NIEUWENHOVE ») ;</li>
 *   <li>composition de l'auditorat francophone ({@code auditfr.pdf}) : « M. X. DELGRANGE Premier auditeur » ;</li>
 *   <li>composition de l'auditorat néerlandophone ({@code auditnl.pdf}) : « Kristine BAMS Eerste Auditeur » ;</li>
 *   <li>page « Direction et gestion » : fiches avec le nom complet et la fonction.</li>
 * </ul>
 * Une même personne apparaît dans plusieurs documents et plusieurs chambres : {@link Members} les fusionne
 * (nom de famille + initiale du prénom) en gardant le nom le plus complet et la fonction la plus haute.
 */
final class CouncilOfStateDocuments {

    static final String PREMIER_PRESIDENT = "Premier président";
    static final String PRESIDENT = "Président";
    static final String AUDITEUR_GENERAL = "Auditeur général";
    static final String AUDITEUR_GENERAL_ADJOINT = "Auditeur général adjoint";
    static final String PRESIDENT_CHAMBRE = "Président de chambre";
    static final String PREMIER_AUDITEUR_CHEF = "Premier auditeur chef de section";
    static final String PREMIER_AUDITEUR = "Premier auditeur";
    static final String CONSEILLER = "Conseiller d'État";
    static final String AUDITEUR = "Auditeur";
    static final String AUDITEUR_ADJOINT = "Auditeur adjoint";
    static final String ASSESSEUR = "Assesseur";

    /** Rang des fonctions : quand une personne en a plusieurs, la plus haute est gardée. */
    private static final List<String> RANKS = List.of(PREMIER_PRESIDENT, PRESIDENT, AUDITEUR_GENERAL,
            AUDITEUR_GENERAL_ADJOINT, PRESIDENT_CHAMBRE, PREMIER_AUDITEUR_CHEF, PREMIER_AUDITEUR, CONSEILLER,
            AUDITEUR, AUDITEUR_ADJOINT, ASSESSEUR);

    /** Initiales : « P. », « Chr. », « A.-St. », « Ch.-H. ». */
    private static final Pattern INITIALS = Pattern.compile("[A-ZÀ-Þ][a-zà-ÿ]{0,2}\\.(?:-[A-ZÀ-Þ][a-zà-ÿ]{0,2}\\.)*");
    /** Particules d'un nom de famille, en minuscules dans la source (« de Broux », « VAN de VELDE »). */
    private static final Pattern PARTICLE = Pattern.compile("(?i)van|de|der|den|du|des|le|la|ten|ter|t'|t’|d'|d’");
    /** Mot de nom de famille : commence par une majuscule (« Ronvaux », « VAN », « D’HOOGHE », « ERNOUX-NEUFCOEUR »). */
    private static final Pattern SURNAME_WORD = Pattern.compile("[A-ZÀ-Þ][A-Za-zÀ-ÿ'’-]*");
    private static final Pattern PARENTHESIS = Pattern.compile("\\([^)]*\\)");


    private CouncilOfStateDocuments() {
    }

    /** Ce que la composition des chambres attribue à chaque nom selon la rubrique où il figure. */
    private enum ChamberRole { NONE, PRESIDENT, MEMBER, ASSESSOR, CLERK }

    /**
     * Compositions des chambres (FR ou NL). Les rubriques donnent la fonction : Président(s) / Voorzitter(s) →
     * président de chambre ; Membres / Leden et Suppléants / Plaatsvervangers → conseiller d'État ;
     * Assesseurs / Assessoren → assesseur ; Greffier(s) / Griffier(s) et leurs suppléants → écartés.
     */
    static List<Member> parseChambers(String text) {
        List<Member> out = new ArrayList<>();
        ChamberRole role = ChamberRole.NONE;
        for (String line : joinWrappedLines(text)) {
            if (line.startsWith("Bruxelles, le") || line.startsWith("Brussel,")) {
                break; // signature du premier président : fin de la composition
            }
            String rest = line;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.matches("^(?:pr[ée]sidents?|voorzitters?)\\b.*")) {
                role = ChamberRole.PRESIDENT;
                rest = line.replaceFirst("^\\S+", "");
            } else if (lower.matches("^(?:membres|leden)\\b.*")) {
                role = ChamberRole.MEMBER;
                rest = line.replaceFirst("^\\S+", "");
            } else if (lower.matches("^(?:suppl[ée]ants?|plaatsvervangers?)\\b.*")) {
                // Le suppléant d'un greffier reste un greffier ; ailleurs, ce sont des conseillers d'État.
                if (role != ChamberRole.CLERK) {
                    role = ChamberRole.MEMBER;
                }
                rest = line.replaceFirst("^\\S+", "");
            } else if (lower.matches("^(?:assesseurs|assessoren)\\b.*")) {
                role = ChamberRole.ASSESSOR;
                rest = line.replaceFirst("^\\S+", "");
            } else if (lower.matches("^(?:greffiers?|griffiers?)\\b.*")) {
                role = ChamberRole.CLERK;
                continue;
            } else if (isChamberOrSectionHeading(line)) {
                role = ChamberRole.NONE;
                continue;
            }
            if (role == ChamberRole.NONE || role == ChamberRole.CLERK) {
                continue;
            }
            String function = switch (role) {
                case PRESIDENT -> PRESIDENT_CHAMBRE;
                case ASSESSOR -> ASSESSEUR;
                default -> CONSEILLER;
            };
            for (String[] name : initialsNames(rest)) {
                out.add(new Member(name[0], name[1], function));
            }
        }
        return out;
    }

    private static boolean isChamberOrSectionHeading(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        // « A. Section de législation », « B. Afdeling Bestuursrechtspraak », « IIe chambre », « chambre Vbis », « Ve kamer »
        return lower.matches("^[a-z]\\.\\s+(?:section|afdeling)\\b.*")
                || lower.matches("^(?:[ivxl]+e\\s+)?(?:chambre|kamer)\\b.*");
    }

    /**
     * Auditorat francophone : « M. X. DELGRANGE Premier auditeur chef de section ». La fonction peut être renvoyée
     * à la ligne (deux noms, puis deux lignes « Premier auditeur ») : elle est alors attribuée dans l'ordre.
     * Une rubrique en capitales (« AUDITEUR GENERAL ADJOINT ») donne la fonction du nom qui suit.
     */
    static List<Member> parseAuditFr(String text) {
        List<Member> out = new ArrayList<>();
        List<String[]> waiting = new ArrayList<>();
        String headingRole = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = PARENTHESIS.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").trim();
            if (line.isEmpty()) {
                continue;
            }
            String upper = stripAccents(line).toUpperCase(Locale.ROOT);
            if (upper.equals("AUDITEUR GENERAL ADJOINT")) {
                headingRole = AUDITEUR_GENERAL_ADJOINT;
                continue;
            }
            if (upper.equals("AUDITEUR GENERAL")) {
                headingRole = AUDITEUR_GENERAL;
                continue;
            }
            String roleOnly = frenchAuditRole(line);
            if (roleOnly != null && roleOnly.length() == line.length()) {
                if (!waiting.isEmpty()) {
                    String[] n = waiting.remove(0);
                    out.add(new Member(n[0], n[1], roleOnly));
                }
                continue;
            }
            Matcher m = Pattern.compile("^(?:M\\.|Mme)\\s+(.+)$").matcher(line);
            if (!m.matches()) {
                continue;
            }
            String body = m.group(1);
            String role = null;
            for (String r : List.of(PREMIER_AUDITEUR_CHEF, PREMIER_AUDITEUR, AUDITEUR_ADJOINT, AUDITEUR)) {
                int i = body.toLowerCase(Locale.ROOT).indexOf(r.toLowerCase(Locale.ROOT));
                if (i > 0) {
                    role = r;
                    body = body.substring(0, i).trim();
                    break;
                }
            }
            List<String[]> names = initialsNames(body);
            if (names.size() != 1) {
                continue; // « Mme N1 » (poste vacant) ou ligne inattendue
            }
            if (role != null) {
                out.add(new Member(names.get(0)[0], names.get(0)[1], role));
            } else if (headingRole != null) {
                out.add(new Member(names.get(0)[0], names.get(0)[1], headingRole));
                headingRole = null;
            } else {
                waiting.add(names.get(0));
            }
        }
        return out;
    }

    private static String frenchAuditRole(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String r : List.of(PREMIER_AUDITEUR_CHEF, PREMIER_AUDITEUR, AUDITEUR_ADJOINT, AUDITEUR)) {
            if (lower.equals(r.toLowerCase(Locale.ROOT))) {
                return r;
            }
        }
        return null;
    }

    /**
     * Auditorat néerlandophone : « Kristine BAMS Eerste Auditeur-Afdelingshoofd », prénom en minuscules suivi du nom
     * en capitales. La rubrique « Auditeur-generaal » donne la fonction du nom qui suit.
     */
    static List<Member> parseAuditNl(String text) {
        List<Member> out = new ArrayList<>();
        boolean nextIsGeneral = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = PARENTHESIS.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equalsIgnoreCase("Auditeur-generaal")) {
                nextIsGeneral = true;
                continue;
            }
            String role = null;
            String body = line;
            String lower = line.toLowerCase(Locale.ROOT);
            String[][] roles = {
                    {"eerste auditeur-afdelingshoofd", PREMIER_AUDITEUR_CHEF},
                    {"eerste auditeur", PREMIER_AUDITEUR},
                    {"adjunct-auditeur", AUDITEUR_ADJOINT},
                    {"auditeur", AUDITEUR}};
            for (String[] r : roles) {
                int i = lower.indexOf(r[0]);
                if (i > 0) {
                    role = r[1];
                    body = line.substring(0, i).trim();
                    break;
                }
            }
            if (role == null && nextIsGeneral) {
                role = AUDITEUR_GENERAL;
            }
            if (role == null) {
                continue;
            }
            String[] name = firstNameThenCapitals(body);
            if (name != null) {
                out.add(new Member(name[0], name[1], role));
                nextIsGeneral = false;
            }
        }
        return out;
    }

    /** « Kristine BAMS » → [Kristine, BAMS] ; « Pierrot T’KINDT », « Rita VAN DEN EECKHOUT ». */
    static String[] firstNameThenCapitals(String body) {
        String[] tokens = body.split("\\s+");
        int i = 0;
        List<String> first = new ArrayList<>();
        while (i < tokens.length && !isAllCaps(tokens[i])) {
            first.add(tokens[i]);
            i++;
        }
        List<String> last = new ArrayList<>();
        while (i < tokens.length && isAllCaps(tokens[i])) {
            last.add(tokens[i]);
            i++;
        }
        if (first.isEmpty() || last.isEmpty() || i != tokens.length) {
            return null;
        }
        if (!first.stream().allMatch(t -> t.matches("[A-ZÀ-Þ][a-zà-ÿ'’-]+"))) {
            return null;
        }
        return new String[]{String.join(" ", first), String.join(" ", last)};
    }

    private static boolean isAllCaps(String token) {
        String letters = token.replaceAll("[^\\p{L}]", "");
        return letters.length() >= 2 && letters.equals(letters.toUpperCase(Locale.ROOT));
    }

    /**
     * Noms « initiales + nom de famille » d'un fragment de ligne. Plusieurs noms peuvent se suivre (deux colonnes
     * du PDF sur une ligne) ou être séparés par des virgules et tirets (« J. VELAERS - M. TISON »). Le nom de
     * famille s'arrête au mot suivant en minuscules (« C. Bertin, greffier assumé ») ou aux initiales suivantes.
     * « N. » seul (poste vacant) ne donne rien.
     */
    static List<String[]> initialsNames(String fragment) {
        String clean = PARENTHESIS.matcher(fragment).replaceAll(" ").replaceAll("\\s+[-–]\\s+", " , ")
                .replace(",", " , ").replaceAll("\\s+", " ").trim();
        List<String[]> names = new ArrayList<>();
        String[] tokens = clean.isEmpty() ? new String[0] : clean.split(" ");
        int i = 0;
        while (i < tokens.length) {
            if (!INITIALS.matcher(tokens[i]).matches()) {
                i++;
                continue;
            }
            String initials = tokens[i];
            List<String> surname = new ArrayList<>();
            int j = i + 1;
            while (j < tokens.length && !INITIALS.matcher(tokens[j]).matches() && !tokens[j].equals(",")) {
                String t = tokens[j];
                if (PARTICLE.matcher(t).matches() || SURNAME_WORD.matcher(t).matches()) {
                    surname.add(t);
                    j++;
                } else {
                    break;
                }
            }
            // Une particule seule en fin de nom n'en est pas un (« P. de » coupé) : on la retire.
            while (!surname.isEmpty() && PARTICLE.matcher(surname.get(surname.size() - 1)).matches()) {
                surname.remove(surname.size() - 1);
            }
            if (!surname.isEmpty()) {
                names.add(new String[]{initials, String.join(" ", surname)});
            }
            i = Math.max(j, i + 1);
            while (i < tokens.length && !INITIALS.matcher(tokens[i]).matches()) {
                i++; // reste de la mention (« greffier assumé »)
            }
        }
        return names;
    }

    /**
     * Lignes du texte extrait, rassemblées quand le PDF coupe un nom (« J. VAN » / « NIEUWENHOVE ») ou une
     * parenthèse (« P. LEFRANC (t.e.m. » / « 30.09.2026) »). Les numéros de page sont écartés.
     */
    static List<String> joinWrappedLines(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder pending = null;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replace(' ', ' ').replaceAll("\\s+", " ").trim();
            if (line.isEmpty() || line.matches("\\d+")) {
                continue;
            }
            if (pending != null) {
                pending.append(' ').append(line);
            } else {
                pending = new StringBuilder(line);
            }
            String current = pending.toString();
            boolean openParen = current.lastIndexOf('(') > current.lastIndexOf(')');
            String lastWord = current.substring(current.lastIndexOf(' ') + 1);
            boolean danglingParticle = current.contains(" ") && PARTICLE.matcher(lastWord).matches()
                    && INITIALS.matcher(current).find();
            if (!openParen && !danglingParticle) {
                lines.add(current);
                pending = null;
            }
        }
        if (pending != null) {
            lines.add(pending.toString());
        }
        return lines;
    }

    static String stripAccents(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Une mention dans un document : prénom ou initiales, nom de famille, fonction. */
    record Member(String firstName, String lastName, String function) {
        String key() {
            String last = stripAccents(lastName).toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            String initial = firstName.isEmpty() ? "" : stripAccents(firstName.substring(0, 1)).toLowerCase(Locale.ROOT);
            return last + "|" + initial;
        }

        boolean hasFullFirstName() {
            return !firstName.contains(".");
        }

        String displayName() {
            return (firstName + " " + lastName).trim();
        }
    }

    /** Fusion des mentions : une personne par clé, nom le plus complet, fonction la plus haute, ordre d'arrivée. */
    static final class Members {
        private final Map<String, Member> byKey = new LinkedHashMap<>();
        private final Map<String, List<String>> aliases = new LinkedHashMap<>();
        private final Map<String, String> photos = new LinkedHashMap<>();

        void add(Member m) {
            add(m, null);
        }

        void add(Member m, String photoUrl) {
            String k = m.key();
            Member old = byKey.get(k);
            if (photoUrl != null) {
                photos.putIfAbsent(k, photoUrl);
            }
            if (old == null) {
                byKey.put(k, m);
                aliases.put(k, new ArrayList<>());
                return;
            }
            Member names = !old.hasFullFirstName() && m.hasFullFirstName() ? m : old;
            String function = rank(m.function()) < rank(old.function()) ? m.function() : old.function();
            Member merged = new Member(names.firstName(), names.lastName(), function);
            for (Member other : List.of(old, m)) {
                String alias = other.displayName();
                if (!alias.equalsIgnoreCase(merged.displayName()) && !aliases.get(k).contains(alias)) {
                    aliases.get(k).add(alias);
                }
            }
            byKey.put(k, merged);
        }

        List<Member> list() {
            return new ArrayList<>(byKey.values());
        }

        List<String> aliasesOf(Member m) {
            return aliases.getOrDefault(m.key(), List.of()).stream()
                    .filter(a -> !a.equalsIgnoreCase(m.displayName())).toList();
        }

        String photoOf(Member m) {
            return photos.get(m.key());
        }

        private static int rank(String function) {
            int i = RANKS.indexOf(function);
            return i < 0 ? RANKS.size() : i;
        }
    }
}
