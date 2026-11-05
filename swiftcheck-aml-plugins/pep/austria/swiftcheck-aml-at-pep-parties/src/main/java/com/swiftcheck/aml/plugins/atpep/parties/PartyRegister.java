package com.swiftcheck.aml.plugins.atpep.parties;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture du « Parteienregisterauszug » du ministère de l'Intérieur (PDF, une ligne par parti). Pour chaque parti
 * demandé par son numéro de registre, les « personnes habilitées à représenter » sont écrites en texte libre, coupé en
 * fin de ligne avec des traits d'union :
 * <pre>
 * "Österreichische Volkspartei" (ÖVP) 500004 14.11.1975 Bundesparteiobmann Dr. nein
 * Christian Stocker, Bundesfinanzr-
 * eferent Andreas Ottenschläger,
 * Generalsekretär Nico Marchetti
 * </pre>
 * Un parti qui n'a rien déclaré (« Nicht bekannt gegeben ») donne une liste vide : c'est signalé, pas inventé.
 */
final class PartyRegister {

    private PartyRegister() {
    }

    /** Un parti demandé : numéro de registre et nom court. */
    record Party(String number, String label) {
    }

    /** Une personne habilitée : nom sans titres académiques et fonction écrite dans le registre. */
    record Person(String name, String firstName, String lastName, String role) {
    }

    /** Résultat pour un parti : entrée trouvée ou non, et personnes lues. */
    record Entry(Party party, boolean found, String registeredName, List<Person> people) {
    }

    private static final Pattern ENTRY_START = Pattern.compile(
            "^(?:\"|\\p{Lu}).*?\\s(5\\d{5})\\s(\\d{2}\\.\\d{2}\\.\\d{4})\\s?(.*)$");
    private static final Pattern ROLE_WORD = Pattern.compile(
            "(?iu)^(bundes\\p{L}*|landes\\p{L}*|partei\\p{L}*|stellvertreter(?:in)?|vorsitz\\p{L}*|obmann|obfrau|sprecher(?:in)?"
                    + "|gesch\\p{L}*ftsf\\p{L}*|finanzref\\p{L}*|referent(?:in)?|generalsekret\\p{L}*|kassier\\p{L}*|klub\\p{L}*"
                    + "|präsident(?:in)?|vize\\p{L}*|obmannstellvertreter(?:in)?)$");

    static List<Entry> read(String text, List<Party> parties) {
        String[] lines = text.split("\\R");
        List<Entry> out = new ArrayList<>();
        for (Party party : parties) {
            int start = -1;
            for (int i = 0; i < lines.length; i++) {
                Matcher m = ENTRY_START.matcher(lines[i]);
                if (m.matches() && m.group(1).equals(party.number())) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                out.add(new Entry(party, false, null, List.of()));
                continue;
            }
            Matcher first = ENTRY_START.matcher(lines[start]);
            first.matches();
            StringBuilder body = new StringBuilder(first.group(3).replaceAll("\\s+(nein|ja)$", ""));
            for (int i = start + 1; i < lines.length && !ENTRY_START.matcher(lines[i]).matches(); i++) {
                String line = lines[i].trim();
                if (line.startsWith("Parteienregisterauszug") || line.startsWith("Name Parteien") || line.matches("\\d+")
                        || line.matches("(?i)seite \\d+.*")) {
                    continue;
                }
                line = line.replaceAll("\\s+(nein|ja)$", "");
                if (body.length() > 0 && body.charAt(body.length() - 1) == '-' && !line.isEmpty()
                        && Character.isLowerCase(line.charAt(0))) {
                    body.setLength(body.length() - 1);          // « Bundesfinanzr- » + « eferent » → « Bundesfinanzreferent »
                } else if (body.length() > 0) {
                    body.append(' ');
                }
                body.append(line);
            }
            String registered = lines[start].replaceAll("\\s5\\d{5}\\s.*$", "").trim();
            out.add(new Entry(party, true, registered, people(body.toString().trim())));
        }
        return out;
    }

    /** « Bundesparteiobmann Dr. Christian Stocker, Generalsekretär Nico Marchetti » → deux personnes. */
    static List<Person> people(String body) {
        List<Person> out = new ArrayList<>();
        if (body.isBlank() || body.startsWith("Nicht bekannt gegeben")) {
            return out;
        }
        for (String segment : body.split(",\\s+")) {
            String[] tokens = segment.trim().split("\\s+");
            int i = 0;
            while (i < tokens.length && ROLE_WORD.matcher(tokens[i]).matches()) {
                i++;
            }
            if (i == 0 || i >= tokens.length) {
                continue;
            }
            String role = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, i));
            int n = i;
            while (n < tokens.length - 1 && (tokens[n].contains(".") || tokens[n].equals("MA") || tokens[n].equals("BA")
                    || tokens[n].equals("MSc") || tokens[n].equals("MBA"))) {
                n++;
            }
            List<String> nameTokens = new ArrayList<>();
            for (int k = n; k < tokens.length; k++) {
                if (!(k > n && (tokens[k].contains(".") || tokens[k].matches("MA|BA|MSc|MBA|BSc|LL\\.M\\.")))) {
                    nameTokens.add(tokens[k]);
                }
            }
            String name = String.join(" ", nameTokens);
            if (nameTokens.size() < 2 || !name.matches("\\p{Lu}[\\p{L}'’ -]+")) {
                continue;
            }
            out.add(new Person(name, nameTokens.get(0), nameTokens.get(nameTokens.size() - 1), role));
        }
        return out;
    }
}
