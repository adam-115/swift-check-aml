package com.swiftcheck.aml.plugins.frpep.jorf;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.swiftcheck.aml.plugins.frpep.jorf.JorfArticles.Article;

/**
 * Lit les nominations et les départs dans le texte d'un article du Journal officiel. Tournures relevées sur les
 * décrets et arrêtés publiés de 1990 à 2026 :
 *
 * <table>
 * <caption>Tournures reconnues</caption>
 * <tr><td>Phrase</td><td>« M. Paul BERNARD, maître des requêtes au Conseil d'Etat, est nommé conseiller d'Etat en
 * service ordinaire, à compter du 15 mai 2026. » ; aussi « est désigné dans les fonctions de », « est reconduit
 * dans ses fonctions de », préfixe « Par arrêté … en date du …, » ou « Art. 1er. - »</td></tr>
 * <tr><td>Collective</td><td>« Sont nommés conseillers maîtres à la Cour des comptes, à compter du 1er octobre
 * 2026 : » puis « M. Jean-Marc OLERON, administrateur de l'Etat (hors tour). »</td></tr>
 * <tr><td>Magistrature</td><td>« Sont nommés : », section « COUR DE CASSATION », puis « Premier avocat général :
 * M. Xavier TARABEUX, avocat général près ladite cour. » ou un en-tête « Avocates générales référendaires : » suivi
 * d'une ligne par personne</td></tr>
 * <tr><td>Retraite</td><td>« M. Eric SEGUY, conseiller à la Cour de cassation, est admis, sur sa demande, à faire
 * valoir ses droits à la retraite, à compter du 1er janvier 2027[, et maintenu en fonction jusqu'au …]. » ; ou
 * collective : « sont admis, par limite d'âge, à faire valoir leurs droits à la retraite : » puis une ligne par
 * personne</td></tr>
 * <tr><td>Fin de fonctions</td><td>« il est mis fin, à sa demande, à compter du 21 mai 2012, aux fonctions de maître
 * des requêtes en service extraordinaire de M. Alexandre Aïdara » ; « il est mis fin au maintien en activité de
 * Mme X, conseiller d'Etat » ; « radié des cadres », « admis à cesser ses fonctions »</td></tr>
 * <tr><td>Maintien</td><td>« Mme Anne-Marie GRIVEL est […] maintenue en activité […] du 19 octobre 2026 au 18 octobre
 * 2029 inclus pour exercer les fonctions d'avocate générale près la Cour de cassation. » ; « maintenue en position
 * de détachement auprès de la Cour des comptes afin d'exercer les fonctions de … pour une durée de trois
 * ans »</td></tr>
 * </table>
 *
 * <p>Noms : « M. Florent BOUDIÉ » (forme actuelle) ou « M. Attali (Bernard) » (textes anciens : nom, puis prénoms
 * entre parenthèses). Une phrase coupée en plusieurs lignes (ligne finie par une virgule ou par « M. ») est
 * recollée. La fin « en remplacement de M. X, admis à faire valoir ses droits à la retraite » parle du prédécesseur :
 * elle est retirée avant la lecture.
 *
 * <p>Une nomination dont la <b>qualité</b> (ce qui suit le nom) cite une autre haute institution vaut aussi départ
 * de celle-ci : « Mme Julie VIGNERAS, conseillère référendaire à la Cour de cassation » nommée première
 * vice-présidente d'un tribunal quitte la Cour de cassation.
 */
final class NominationReader {

    /**
     * Événement lu. {@code GOVERNMENT_END} : fin des fonctions de tout le Gouvernement, sans liste de noms ; il passe
     * avant les nominations du même jour (le nouveau Premier ministre est souvent nommé le jour même).
     */
    enum Kind { GOVERNMENT_END, NOMINATION, DEPARTURE, EXTENSION }

    /** Haute institution reconnue dans une fonction ou une qualité. */
    enum Institution {
        CONSEIL_ETAT("conseil-etat", "Conseil d'État"),
        COUR_CASSATION("cour-cassation", "Cour de cassation"),
        COUR_COMPTES("cour-des-comptes", "Cour des comptes"),
        AMBASSADE("ambassade", "Ambassades"),
        GOUVERNEMENT("gouvernement", "Gouvernement");

        final String key;
        final String label;

        Institution(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    /**
     * Un événement lu.
     *
     * @param kind        nomination, départ (retraite, fin de fonctions, nomination ailleurs) ou maintien
     * @param name        nom tel qu'écrit, prénom d'abord (« Xavier TARABEUX », « Bernard Attali »)
     * @param institution institution concernée
     * @param function    fonction écrite (nomination, maintien) ou qualité quittée (départ), sinon {@code null}
     * @param post        poste d'ambassadeur (« auprès de la République portugaise »), sinon {@code null}
     * @param date        date d'effet (à défaut, date du texte)
     * @param until       fin prévue (maintien en fonction jusqu'au …), sinon {@code null}
     * @param article     article source
     */
    record Event(Kind kind, String name, Institution institution, String function, String post, LocalDate date,
            LocalDate until, Article article) {
    }

    private static final String CIVILITY = "(?:M\\.|Mme|Mlle|Monsieur|Madame)";
    /** Civilité puis nom, jusqu'à la virgule, la parenthèse (prénoms), « est », « sont » ou la fin. */
    private static final Pattern PERSON = Pattern.compile(
            "(?:^|[\\s:,(])" + CIVILITY + "\\s+([^,;:()]+?)(?=\\s*(?:,|;|\\(|\\.$|$|\\s+est\\b|\\s+sont\\b))");
    /** Prénoms entre parenthèses après le nom (textes anciens) : « Attali (Bernard) », « Bertrand (François, Pierre) ». */
    private static final Pattern GIVEN_NAMES = Pattern.compile("^\\s*\\(([^)]+)\\)");
    private static final Pattern NOMINATED = Pattern.compile(
            "\\s(?:est|a été)\\s+(?:nommée?|désignée?|reconduite?|renouvelée?)\\s*,?\\s*(?:sur sa demande\\s*,\\s*)?"
                    + "(?:(?:dans les|dans ses) fonctions (?:de |d'|d’))?(.+)$");
    private static final Pattern RETIREMENT = Pattern.compile("droits à la retraite|admise?s? à la retraite");
    /** Départ sans retraite : fin de fonctions, fin du maintien en activité, radiation des cadres, cessation. */
    private static final Pattern ENDED = Pattern.compile(
            "(?i)\\bmis fin\\b|radiée?s? des cadres|admise?s? à cesser|cesse(?:ra|r)? (?:d'exercer )?ses fonctions");
    private static final Pattern ENDED_FUNCTION = Pattern.compile(
            "aux fonctions (?:de |d'|d’)(.+?)\\s+(?:de|du)\\s+" + CIVILITY + "\\s");
    private static final Pattern FUNCTIONS_OF_PERSON = Pattern.compile("aux fonctions de\\s+" + CIVILITY + "\\s");
    /** Maintien en fonction ou en détachement dans une haute institution : la fin prévue change. */
    private static final Pattern MAINTAINED = Pattern.compile(
            "maintenue?s?\\s+(?:en\\s+(?:activité|fonctions?)|en position de détachement)");
    private static final Pattern DURATION = Pattern.compile(
            "pour une (?:durée|période) (?:de |d')(un|une|deux|trois|quatre|cinq|six|\\d+) ans?");
    private static final Pattern FROM_DU = Pattern.compile("\\bdu\\s+" + dateRegex());
    private static final Pattern COLLECTIVE = Pattern.compile("^Sont\\s+(?:nommée?s|désignée?s)\\s*(.*?)\\s*:$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COLLECTIVE_RETIREMENT = Pattern.compile(
            "^Sont\\s+admise?s\\b.*(?:retraite|cesser).*:$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** « Par arrêté du vice-président du Conseil d'Etat en date du 31 août 2023, » en tête d'un arrêté. */
    private static final Pattern ACT_PREFIX = Pattern.compile(
            "^Par (?:décret|arrêté|arrêtés)[^,]*?\\ben date du " + dateRegex() + "\\s*,\\s*(?:le conseil des ministres"
                    + " entendu\\s*,\\s*)?");
    /** Ce qui suit « en remplacement de » parle du prédécesseur (« …, admis à faire valoir ses droits à la retraite »). */
    private static final Pattern REPLACING = Pattern.compile("\\s*,?\\s*en remplacement d[e'’u].*$");
    private static final Pattern EXERCISE = Pattern.compile("(?:pour|afin d'|afin d’)\\s*(?:continuer à )?exercer les"
            + " fonctions (?:de |d'|d’)(.+)$");
    private static final Pattern INLINE_FUNCTION = Pattern.compile("^([^:]{3,200}?)\\s*:\\s*(" + CIVILITY + "\\s.*)$");
    private static final Pattern ARTICLE_PREFIX = Pattern.compile(
            "^(?:Art\\.?|Article)\\s*\\d*\\s*(?:er)?\\.?\\s*[-–—]\\s*");
    private static final Pattern FROM_DATE = Pattern.compile(
            "(?:à compter du|avec effet au|à partir du)\\s+" + dateRegex());
    private static final Pattern UNTIL_DATE = Pattern.compile(
            "(?:jusqu'au|jusqu’au|\\bau)\\s+" + dateRegex() + "(?:\\s+inclus)?");
    /** Fin d'une fonction écrite : la date, le remplacement ou la ponctuation finale ne font pas partie du titre. */
    private static final Pattern FUNCTION_END = Pattern.compile(
            "\\s*(?:,\\s*(?:à compter|avec effet|à partir|pour une durée|pour une période|en qualité)"
                    + "|\\s+pour exercer les fonctions|\\s*\\((?:tour|hors tour|\\d)|[.;]\\s*$).*$");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("janvier", 1), Map.entry("fevrier", 2),
            Map.entry("mars", 3), Map.entry("avril", 4), Map.entry("mai", 5), Map.entry("juin", 6),
            Map.entry("juillet", 7), Map.entry("aout", 8), Map.entry("septembre", 9), Map.entry("octobre", 10),
            Map.entry("novembre", 11), Map.entry("decembre", 12));

    /** Fonctions d'appui ou d'autres juridictions, jamais retenues même si elles citent une haute institution. */
    private static final Pattern NOT_MEMBER = Pattern.compile(
            "greffi|\\battache|secretaire (?:general )?adjoint|stagiaire|rapporteure? exterieure?|chambres? (?:regionales?|"
                    + "territoriales?) des comptes|cour nationale du droit d'asile|tribunal administratif|tribunaux "
                    + "administratifs|cours? administratives? d'appel|avocats? aux? conseils?|chefs? des services"
                    + "|tresorier|agent comptable|conseiller (?:commercial|culturel|economique|des affaires etrangeres)"
                    + "|premier conseiller a l'ambassade|ambassade de france a ");

    private NominationReader() {
    }

    /** Les événements d'un article, dans l'ordre du texte. */
    static List<Event> read(Article article) {
        String title = JorfArticles.fold(article.title());
        if (JorfArticles.GOVERNMENT_TITLE.matcher(title).find()) {
            return readGovernment(article);
        }
        Institution titleInstitution = title.contains("(conseil d'etat)") ? Institution.CONSEIL_ETAT
                : title.contains("(cour des comptes)") ? Institution.COUR_COMPTES : null;
        // La DILA écrit 2999-01-01 quand la date du texte est inconnue : date de publication à la place
        LocalDate textDate = parseIsoDate(article.signature());
        if (textDate == null || textDate.getYear() >= 2999) {
            textDate = parseIsoDate(article.publication());
        }

        List<Event> events = new ArrayList<>();
        Institution section = titleInstitution;
        boolean sectioned = title.contains("(magistrature)");
        String headerFunction = null;
        boolean headerRetirement = false;
        LocalDate headerDate = null;
        for (String raw : joinLines(article.lines())) {
            String line = ACT_PREFIX.matcher(ARTICLE_PREFIX.matcher(raw).replaceFirst("").trim()).replaceFirst("");
            line = REPLACING.matcher(line).replaceFirst("");
            line = line.isEmpty() ? line : Character.toUpperCase(line.charAt(0)) + line.substring(1);

            if (COLLECTIVE_RETIREMENT.matcher(line).matches()) {
                headerRetirement = !MAINTAINED.matcher(line).find();
                headerFunction = null;
                headerDate = date(FROM_DATE, line);
                continue;
            }
            Matcher collective = COLLECTIVE.matcher(line);
            if (collective.matches()) {
                String rest = collective.group(1).replaceFirst("^(?:dans les fonctions (?:de |d'|d’))", "");
                headerDate = date(FROM_DATE, rest);
                headerFunction = rest.isBlank() ? null : cleanFunction(rest);
                headerRetirement = false;
                continue;
            }
            Matcher person = PERSON.matcher(line);
            if (!person.find()) {
                if (line.endsWith(":")) {
                    // En-tête de fonction : « Avocates générales référendaires : »
                    headerFunction = cleanFunction(line.substring(0, line.length() - 1));
                    headerRetirement = false;
                } else if (sectioned && line.length() < 120 && !line.contains(",")) {
                    // Section d'un décret de magistrature : « COUR DE CASSATION », « Tribunal judiciaire de Paris »
                    section = JorfArticles.fold(line).equals("cour de cassation") ? Institution.COUR_CASSATION : null;
                    headerFunction = null;
                    headerRetirement = false;
                }
                continue;
            }
            String name = person.group(1).trim();
            String afterName = line.substring(person.end(1));
            Matcher given = GIVEN_NAMES.matcher(afterName);
            if (given.find() && !given.group(1).matches("(?i)\\s*(?:hors tour|tour|rang|\\d).*")) {
                name = given.group(1).split(",")[0].trim() + " " + name;
                afterName = afterName.substring(given.end());
            }
            String quality = quality(afterName);
            Institution qualityInstitution = institution(quality, section);

            if (RETIREMENT.matcher(line).find() || ENDED.matcher(line).find() || headerRetirement) {
                departure(events, line, name, quality, qualityInstitution, titleInstitution, section,
                        orElse(headerDate, textDate), article);
                continue;
            }
            if (MAINTAINED.matcher(line).find()) {
                Matcher exercise = EXERCISE.matcher(line);
                String function = exercise.find() ? cleanFunction(exercise.group(1)) : quality;
                // « maintenue en position de détachement auprès de la Cour des comptes afin d'exercer les fonctions de
                // conseillère référendaire » : l'institution est écrite avant la fonction
                Institution in = institution(function, institution(line, section));
                LocalDate from = orElse(date(FROM_DU, line), orElse(date(FROM_DATE, line), textDate));
                LocalDate until = lastDate(line);
                Matcher duration = DURATION.matcher(line);
                if (until == null && duration.find() && from != null) {
                    until = from.plusYears(years(duration.group(1)));
                }
                if (in != null && until != null) {
                    events.add(new Event(Kind.EXTENSION, name, in, function, null, from, until, article));
                }
                continue;
            }

            String function;
            LocalDate when;
            Matcher nominated = NOMINATED.matcher(afterName);
            Matcher inline = INLINE_FUNCTION.matcher(line);
            if (nominated.find()) {
                function = cleanFunction(nominated.group(1));
                when = date(FROM_DATE, nominated.group(1));
            } else if (inline.matches() && inline.start(2) <= person.start(1)) {
                function = cleanFunction(inline.group(1));
                when = date(FROM_DATE, line);
            } else if (headerFunction != null) {
                function = headerFunction;
                when = date(FROM_DATE, line);
            } else {
                continue;               // radiation, détachement, mention sans fonction nouvelle
            }
            when = orElse(when, orElse(headerDate, textDate));

            Institution target = institution(function, section);
            if (target != null) {
                events.add(new Event(Kind.NOMINATION, name, target, function,
                        target == Institution.AMBASSADE ? post(function) : null, when, null, article));
            }
            // Nommé hors de l'institution citée par sa qualité : il la quitte
            if (qualityInstitution != null && qualityInstitution != target) {
                events.add(new Event(Kind.DEPARTURE, name, qualityInstitution, quality,
                        qualityInstitution == Institution.AMBASSADE ? post(quality) : null, when, null, article));
            }
        }
        return events;
    }

    /**
     * Décrets sur la composition du Gouvernement. Tournures relevées (2025-2026) :
     * <pre>
     * Sont nommés ministres :
     * M. Laurent NUNEZ, ministre de l'intérieur ;                     → la fonction suit le nom
     * Sont nommés ministres délégués et participent au conseil des ministres … :
     * - Auprès du ministre de l'intérieur :
     * Mme Marie-Pierre VEDRENNE ;                                       → fonction = en-tête + « auprès de »
     * Il est mis fin aux fonctions de Mme Rachida DATI, ministre de la culture.
     * Il est mis fin aux fonctions de :                                → liste de départs
     * M. Sébastien LECORNU est nommé Premier ministre.
     * Il est mis fin, sur la présentation de la démission du Gouvernement, aux fonctions de M. X, Premier ministre,
     * et des autres membres du Gouvernement.                           → fin de tout le Gouvernement
     * </pre>
     * Une personne qui quitte une fonction et en reçoit une autre dans le même article reste au Gouvernement : son
     * départ n'est pas gardé.
     */
    static List<Event> readGovernment(Article article) {
        LocalDate date = parseIsoDate(article.signature());
        if (date == null || date.getYear() >= 2999) {
            date = parseIsoDate(article.publication());
        }
        List<Event> events = new ArrayList<>();
        boolean ending = false;
        String header = null;
        String near = null;
        for (String raw : joinLines(article.lines())) {
            String line = ACT_PREFIX.matcher(ARTICLE_PREFIX.matcher(raw).replaceFirst("").trim()).replaceFirst("");
            String folded = JorfArticles.fold(line);
            // « Il est mis fin, sur la présentation de la démission du Gouvernement, aux fonctions de M. Jean-Pierre
            // Raffarin, Premier ministre, et des autres membres du Gouvernement. » (tournure de 1991 à 2025)
            if (folded.matches(".*\\bmis fin\\b.*\\bfonctions\\b.*\\bmembres du gouvernement\\b.*")
                    || folded.matches(".*\\bmis fin aux fonctions du gouvernement\\b.*")) {
                events.add(new Event(Kind.GOVERNMENT_END, null, Institution.GOUVERNEMENT, null, null, date, null,
                        article));
                continue;
            }
            if (folded.matches("^il est mis fin.*aux fonctions de\\s*:$")) {
                ending = true;
                continue;
            }
            Matcher collective = COLLECTIVE.matcher(line);
            if (collective.matches()) {
                ending = false;
                header = governmentRole(collective.group(1));
                near = null;
                continue;
            }
            if (line.endsWith(":") && folded.replaceFirst("^-\\s*", "").startsWith("aupres d")) {
                near = line.replaceFirst("^-\\s*", "").replaceFirst("\\s*:$", "").trim();
                near = Character.toLowerCase(near.charAt(0)) + near.substring(1);
                continue;
            }
            Matcher person = PERSON.matcher(line);
            if (!person.find()) {
                continue;
            }
            String name = person.group(1).trim();
            String afterName = line.substring(person.end(1));
            Matcher nominated = NOMINATED.matcher(afterName);
            String written = afterName.replaceFirst("^\\s*,\\s*", "").replaceFirst("\\s*[;.]\\s*$", "").trim();
            if (folded.startsWith("il est mis fin") || ending) {
                events.add(new Event(Kind.DEPARTURE, name, Institution.GOUVERNEMENT, written.isEmpty() ? null : written,
                        null, date, null, article));
            } else if (nominated.find()) {
                events.add(new Event(Kind.NOMINATION, name, Institution.GOUVERNEMENT,
                        nominated.group(1).replaceFirst("\\s*[;.]\\s*$", "").trim(), null, date, null, article));
            } else if (header != null) {
                events.add(new Event(Kind.NOMINATION, name, Institution.GOUVERNEMENT,
                        governmentFunction(header, near, afterName.startsWith(",") ? written : ""), null, date, null,
                        article));
            }
        }
        // Départ et nouvelle nomination dans le même article : la personne reste au Gouvernement
        java.util.Set<String> nominatedNames = new java.util.HashSet<>();
        events.stream().filter(e -> e.kind() == Kind.NOMINATION).forEach(e -> nominatedNames.add(e.name()));
        events.removeIf(e -> e.kind() == Kind.DEPARTURE && nominatedNames.contains(e.name()));
        return events;
    }

    /** En-tête « ministres délégués auprès du Premier ministre et participent … » → « ministre délégué ». */
    private static String governmentRole(String header) {
        String h = JorfArticles.fold(header);
        if (h.startsWith("secretaires d'etat")) {
            return "secrétaire d'État";
        }
        if (h.startsWith("ministres delegues")) {
            String near = header.replaceFirst("(?i)^ministres délégués\\s*", "").replaceFirst("\\s+et participent.*$", "")
                    .trim();
            return "ministre délégué" + (near.toLowerCase(java.util.Locale.FRENCH).startsWith("auprès") ? " " + near : "");
        }
        if (h.startsWith("ministres d'etat")) {
            return "ministre d'État";
        }
        return "ministre";
    }

    /**
     * Fonction d'un membre d'une liste : celle écrite après le nom si elle est complète (« ministre de l'intérieur »,
     * « garde des sceaux, ministre de la justice »), sinon l'en-tête, « auprès de … » et la précision écrite
     * (« ministre délégué auprès du Premier ministre, chargé des relations avec le Parlement »).
     */
    private static String governmentFunction(String role, String near, String written) {
        String w = JorfArticles.fold(written);
        if (w.matches("^(?:ministre|garde des sceaux|secretaire d'etat|haute?-commissaire|premier ministre).*")) {
            return written;
        }
        String base = role + (near != null && !role.contains("auprès") ? " " + near : "");
        return written.isEmpty() ? base : base + ", " + written;
    }

    /**
     * Retraite, fin de fonctions, radiation des cadres. L'institution quittée est celle de la fonction terminée
     * (« mis fin aux fonctions de maître des requêtes en service extraordinaire de M. X »), à défaut celle de la
     * qualité (« M. X, conseiller à la Cour de cassation, est admis… »), à défaut celle du titre. Une fin de
     * fonctions <b>hors</b> institution (président d'une chambre régionale des comptes) ne fait pas quitter le corps.
     */
    private static void departure(List<Event> events, String line, String name, String quality,
            Institution qualityInstitution, Institution titleInstitution, Institution section, LocalDate defaultDate,
            Article article) {
        // « il est mis fin aux fonctions de M. Philippe Belaval, conseiller d'Etat, directeur des Archives de France » :
        // c'est la dernière fonction citée qui prend fin, pas l'appartenance au Conseil d'État
        if (FUNCTIONS_OF_PERSON.matcher(line).find()) {
            return;
        }
        Institution from;
        String function = quality.isBlank() ? null : quality;
        Matcher ended = ENDED_FUNCTION.matcher(line);
        if (ended.find()) {
            function = cleanFunction(ended.group(1));
            from = institution(function, section != null ? section : titleInstitution);
            if (from == null) {
                return;
            }
        } else {
            from = qualityInstitution != null ? qualityInstitution : titleInstitution;
        }
        if (from == null) {
            return;
        }
        // Retraite suivie d'un maintien en fonction : la fin est celle du maintien
        LocalDate until = MAINTAINED.matcher(line).find() ? date(UNTIL_DATE, line) : null;
        LocalDate when = until != null ? until : orElse(date(FROM_DATE, line), defaultDate);
        events.add(new Event(Kind.DEPARTURE, name, from, function,
                from == Institution.AMBASSADE && function != null ? post(function) : null, when, null, article));
    }

    /**
     * Recolle une phrase coupée : une ligne finie par une virgule, par « M. » ou « Mme » se poursuit sur la ligne
     * suivante (« Art. 1er. - M. Jean-Daniel Tordjman, conseiller commercial de 1re classe, » / « est nommé … »).
     */
    static List<String> joinLines(List<String> lines) {
        List<String> joined = new ArrayList<>();
        StringBuilder pending = null;
        for (String line : lines) {
            if (pending != null) {
                pending.append(' ').append(line);
            } else {
                pending = new StringBuilder(line);
            }
            if (!(line.endsWith(",") || line.matches(".*\\b(?:M\\.|Mme|Mlle)$"))) {
                joined.add(pending.toString());
                pending = null;
            }
        }
        if (pending != null) {
            joined.add(pending.toString());
        }
        return joined;
    }

    /**
     * Haute institution d'une fonction ou d'une qualité écrite. {@code section} : institution de la section en cours
     * (décret de magistrature) ou du titre, pour une fonction qui ne la nomme pas (« conseiller maître (1er tour) »
     * dans un décret « (Cour des comptes) », « ladite cour » sous « COUR DE CASSATION »).
     */
    static Institution institution(String text, Institution section) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = JorfArticles.fold(text);
        if (NOT_MEMBER.matcher(t).find()) {
            return null;
        }
        // Représentant permanent de la France auprès d'une organisation : rang d'ambassadeur. Pas « représentant
        // permanent de l'Etat au conseil d'administration d'une société ».
        if (t.matches(".*\\bambassad(?:eur|rice)s?\\b.*")
                || t.matches(".*\\brepresentante?s? permanente?s? (?:de la france|de la republique francaise)?\\s*"
                        + "aupres d.*")) {
            return Institution.AMBASSADE;
        }
        if (t.contains("cour de cassation")) {
            return Institution.COUR_CASSATION;
        }
        if (t.contains("cour des comptes")) {
            return Institution.COUR_COMPTES;
        }
        if (t.matches(".*\\b(?:conseil\\w* d'etat|du conseil d'etat|maitre(?:sse)?s? des requetes)\\b.*")) {
            return Institution.CONSEIL_ETAT;
        }
        if (section == null || t.contains("cour d'appel") || t.contains("tribunal")) {
            return null;
        }
        // Fonction sans institution écrite : celle de la section ou du titre, si c'en est bien une fonction
        boolean member = switch (section) {
            case CONSEIL_ETAT -> t.matches(".*\\b(?:conseill\\w*|maitre(?:sse)?s?|auditeur|auditrice|rapporteure?"
                    + " publi(?:c|que)|president\\w* de (?:la )?section|vice-president\\w*)\\b.*");
            case COUR_COMPTES -> t.matches(".*\\b(?:conseill\\w* (?:maitre|referendaire)\\w*|auditeur|auditrice"
                    + "|president\\w* de chambre|premier\\w* president\\w*|procureur\\w* general\\w*"
                    + "|avocat\\w* general\\w*)\\b.*");
            case COUR_CASSATION -> t.matches(".*\\b(?:conseill\\w*|auditeur|auditrice|president\\w*|procureur\\w*"
                    + " general\\w*|avocat\\w* general\\w*)\\b.*|.*ladite cour.*");
            case AMBASSADE, GOUVERNEMENT -> false;
        };
        return member ? section : null;
    }

    /** Ce qui suit le nom jusqu'à « est … » : « conseiller à la Cour de cassation ». */
    private static String quality(String afterName) {
        String q = afterName.replaceFirst("^\\s*,\\s*", "");
        if (q.length() == afterName.length()) {
            return "";                  // pas de virgule après le nom : pas de qualité
        }
        q = q.replaceFirst("\\s*,?\\s+(?:est|sont|a été)\\s.*$", "").replaceFirst("[.;]\\s*$", "");
        return q.replaceFirst("\\s*\\((?:hors tour|tour|rang|\\d).*$", "").trim();
    }

    /** Poste d'ambassadeur : ce qui suit « de la République française », ou la fonction entière. */
    static String post(String function) {
        String f = function.replaceFirst("(?i)^.*?(?:de la République française|de la France)\\s+", "");
        return f.isBlank() ? function : f.trim();
    }

    private static String cleanFunction(String text) {
        String f = FUNCTION_END.matcher(text.trim()).replaceFirst("");
        f = f.replaceFirst("\\s*,\\s*$", "").replaceFirst("\\s+en service ordinaire$", "")
                .replaceFirst("\\s*\\((?:tour|hors tour|\\d).*$", "");
        return f.trim();
    }

    private static LocalDate date(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? toDate(m.group(1), m.group(2), m.group(3)) : null;
    }

    private static LocalDate lastDate(String text) {
        Matcher m = UNTIL_DATE.matcher(text);
        LocalDate last = null;
        while (m.find()) {
            last = toDate(m.group(1), m.group(2), m.group(3));
        }
        return last;
    }

    /** Jour, mois en lettres, année : « 1 er octobre 2026 », « 1er octobre 2026 », « 15 mai 2026 ». */
    private static String dateRegex() {
        return "(\\d{1,2})\\s*(?:er|re)?\\s+(janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre"
                + "|octobre|novembre|décembre|decembre)\\s+(\\d{4})";
    }

    private static LocalDate toDate(String day, String month, String year) {
        try {
            return LocalDate.of(Integer.parseInt(year), MONTHS.get(JorfArticles.fold(month)), Integer.parseInt(day));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static LocalDate parseIsoDate(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int years(String word) {
        return switch (word) {
            case "un", "une" -> 1;
            case "deux" -> 2;
            case "trois" -> 3;
            case "quatre" -> 4;
            case "cinq" -> 5;
            case "six" -> 6;
            default -> Integer.parseInt(word);
        };
    }

    private static <T> T orElse(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
