package com.swiftcheck.aml.sdk;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalise un nom/code de pays (EN, FR, ISO-2, ISO-3, gentilés courants) en code ISO 3166-1 alpha-2
 * minuscule, format attendu par FollowTheMoney pour les propriétés de type "country".
 */
public final class CountryNormalizer {

    /** Valeurs "vides" fréquentes dans les sources officielles (dont "00" = pays inconnu dans la liste UE) : ignorées sans être signalées en rejet. */
    public static final Set<String> NULL_MARKERS = Set.of("", "n a", "na", "n d", "nd", "unknown", "inconnu", "inconnue", "none", "null", "-", "00", "xx");

    private static final Map<String, String> LOOKUP = new HashMap<>();
    private static final Set<String> ISO2;

    static {
        java.util.Set<String> iso2 = new java.util.HashSet<>();
        for (String code : Locale.getISOCountries()) {
            String lower = code.toLowerCase(Locale.ROOT);
            iso2.add(lower);
            Locale loc = new Locale("", code);
            put(loc.getDisplayCountry(Locale.ENGLISH), lower);
            put(loc.getDisplayCountry(Locale.FRENCH), lower);
            try {
                put(loc.getISO3Country(), lower);
            } catch (Exception ignored) {
                // certains territoires n'ont pas de code ISO-3
            }
        }
        // Codes hors ISO mais supportés par FollowTheMoney
        iso2.add("xk");
        iso2.add("suhh");
        iso2.add("csxx");
        iso2.add("yucs");
        // Territoires spécifiques reconnus par FollowTheMoney
        iso2.addAll(java.util.List.of("cshh", "ua-cri", "ua-dpr", "ua-lpr", "ge-ab", "x-so", "md-pmr", "cy-trnc", "az-nk", "so-som"));
        ISO2 = java.util.Collections.unmodifiableSet(iso2);

        // Libellés officiels ONU / UE / OFAC non couverts par java.util.Locale
        alias("xk", "kosovo");
        alias("kp", "democratic people's republic of korea", "north korea", "dprk", "coree du nord", "republique populaire democratique de coree");
        alias("kr", "republic of korea", "south korea", "coree du sud", "republique de coree");
        alias("ir", "iran islamic republic of", "islamic republic of iran");
        alias("sy", "syrian arab republic", "republique arabe syrienne");
        alias("ru", "russian federation", "federation de russie", "russe", "russian");
        alias("vn", "viet nam");
        alias("tz", "united republic of tanzania", "republique unie de tanzanie");
        alias("la", "lao people's democratic republic", "laos");
        alias("md", "republic of moldova", "republique de moldova");
        alias("cd", "democratic republic of the congo", "dr congo", "drc", "republique democratique du congo", "congo kinshasa");
        alias("cg", "congo", "republic of the congo", "republique du congo", "congo brazzaville");
        alias("ci", "cote d'ivoire", "ivory coast");
        alias("tr", "turkiye", "turkey", "turquie");
        alias("gb", "united kingdom of great britain and northern ireland", "united kingdom", "uk", "great britain",
                "britannique", "british", "royaume uni");
        alias("us", "united states of america", "united states", "usa", "etats unis", "etats unis d'amerique", "american", "americaine", "americain");
        alias("ps", "state of palestine", "palestine", "etat de palestine", "occupied palestinian territory",
                "occupied palestinian territories", "palestinian");
        alias("ly", "libyan arab jamahiriya");
        alias("mk", "north macedonia", "the former yugoslav republic of macedonia", "macedoine du nord");
        alias("sz", "eswatini", "swaziland");
        alias("bn", "brunei darussalam");
        alias("cv", "cabo verde", "cape verde");
        alias("va", "holy see");
        alias("vc", "st vincent", "saint vincent");
        alias("cshh", "czechoslovakia", "tchecoslovaquie");
        alias("ua-cri", "crimea", "crimee", "autonomous republic of crimea");
        alias("ua-dpr", "donetsk people's republic");
        alias("ua-lpr", "luhansk people's republic");
        alias("ge-ab", "abkhazia");
        alias("x-so", "south ossetia");
        alias("md-pmr", "transnistria");
        alias("hk", "hong kong", "china hong kong special administrative region", "hong kong sar");
        alias("mo", "macao", "macau", "china macao special administrative region");
        alias("tw", "taiwan", "taiwan province of china");
        alias("cz", "czechia", "czech republic");
        alias("mm", "myanmar", "burma", "birmanie");
        alias("ba", "bosnia and herzegovina", "bosna", "bosnie herzegovine");
        alias("bo", "bolivia plurinational state of", "bolivie");
        alias("ve", "venezuela bolivarian republic of");
        alias("fm", "micronesia federated states of");
        alias("vg", "british virgin islands", "virgin islands british");
        alias("suhh", "former soviet union", "soviet union", "ussr", "urss");
        alias("yucs", "yugoslavia", "former yugoslavia", "yu");                 // ancien code ISO "YU"
        alias("csxx", "serbia and montenegro", "serbie et montenegro", "cs");   // ancien code ISO "CS"
        // Gentilés (nationalité saisie comme adjectif)
        alias("fr", "francaise", "francais", "french");
        alias("tn", "tunisienne", "tunisien", "tunisian");
        alias("ma", "marocaine", "marocain", "moroccan");
        alias("dz", "algerienne", "algerien", "algerian");
        alias("de", "allemande", "allemand", "german");
        alias("it", "italienne", "italien", "italian");
        alias("es", "espagnole", "espagnol", "spanish");
        alias("be", "belge", "belgian");
        alias("lu", "luxembourgeoise", "luxembourgeois", "luxembourgish");
        alias("ch", "suisse", "swiss");
        alias("sa", "saudi", "saoudienne", "saoudien");
        alias("ye", "yemenite");
        alias("iq", "irakienne", "irakien", "iraqi");
        alias("af", "afghane", "afghan");
        alias("pk", "pakistanaise", "pakistanais", "pakistani");
        alias("cn", "chinoise", "chinois", "chinese");
    }

    private CountryNormalizer() {
    }

    /**
     * @return le code ISO-2 minuscule, ou {@code null} si la valeur n'est pas reconnue.
     */
    public static String toIsoCode(String raw) {
        if (raw == null) {
            return null;
        }
        String key = key(raw);
        if (key.isEmpty() || NULL_MARKERS.contains(key)) {
            return null;
        }
        if (ISO2.contains(key)) {
            return key;
        }
        String hit = LOOKUP.get(key);
        if (hit != null) {
            return hit;
        }
        // "Iran (Islamic Republic of)" -> "iran" ; "Myanmar (Burma)" -> "myanmar"
        String noParen = key(raw.replaceAll("\\(.*?\\)", " "));
        hit = LOOKUP.get(noParen);
        if (hit != null) {
            return hit;
        }
        // "The Gambia", "the Netherlands"
        if (key.startsWith("the ")) {
            return LOOKUP.get(key.substring(4));
        }
        // "Former USSR (now Belarus)", "Ukrainian SSR, currently Ukraine" : on garde le pays actuel
        for (String marker : new String[] {" now ", " currently "}) {
            int i = key.indexOf(marker);
            if (i >= 0) {
                return toIsoCode(key.substring(i + marker.length()));
            }
        }
        // Républiques soviétiques sans pays actuel précisé
        if (key.contains("ussr") || key.matches(".*\\bssr\\b.*") || key.contains("soviet")) {
            return "suhh";
        }
        return null;
    }

    /** Indique si la valeur brute est un marqueur d'absence ("n.d.", "unknown"...). */
    public static boolean isUnknownValue(String raw) {
        return raw == null || NULL_MARKERS.contains(key(raw));
    }

    static String key(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        s = s.toLowerCase(Locale.ROOT)
                .replace('’', '\'')
                .replace("&", " and ")
                .replaceAll("[^a-z0-9' ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    private static void put(String name, String code) {
        if (name == null || name.isBlank()) {
            return;
        }
        LOOKUP.putIfAbsent(key(name), code);
        String noParen = key(name.replaceAll("\\(.*?\\)", " "));
        if (!noParen.isEmpty()) {
            LOOKUP.putIfAbsent(noParen, code);
        }
    }

    private static void alias(String code, String... names) {
        for (String n : names) {
            LOOKUP.put(key(n), code);
        }
    }
}
