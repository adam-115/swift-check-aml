package com.swiftcheck.aml.plugins.ptpep.regulators;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracteurs des pages des autorités de régulation portugaises. Un extracteur par site : aucun nom n'est embarqué dans
 * le plugin, les noms sont toujours lus dans la page officielle téléchargée à l'exécution.
 */
final class RegulatorPages {

    private RegulatorPages() {
    }

    /** Un titulaire lu sur une page ; {@code role} est la fonction écrite sur la page. */
    record Member(String name, String role) {
    }

    static final Map<String, Function<List<String>, List<Member>>> EXTRACTORS = new LinkedHashMap<>();

    static {
        EXTRACTORS.put("erc", RegulatorPages::erc);
    }

    /** « Presidente - », « Vice-Presidente - », « Vogal - » : la fonction est suivie d'un tiret, le nom est à la ligne suivante. */
    private static final Pattern ROLE = Pattern.compile("^(Presidente|Vice-Presidente|Vogal)\\s*-\\s*$");

    private static final Pattern NAME = Pattern.compile(
            "^\\p{Lu}[\\p{L}'’.-]*(?: (?:\\p{Lu}[\\p{L}'’.-]*|d[aeo]s?|e|do|dos|das|de))*(?: \\p{Lu}[\\p{L}'’.-]*)$");

    /**
     * Page « Conselho Regulador » de l'ERC :
     * <pre>
     * Presidente - | Helena Sousa | Vice-Presidente - | Pedro Correia Gonçalves | Vogal - | Telmo Gonçalves | …
     * </pre>
     */
    static List<Member> erc(List<String> lines) {
        List<Member> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            Matcher role = ROLE.matcher(lines.get(i));
            if (!role.matches()) {
                continue;
            }
            String name = lines.get(i + 1);
            if (NAME.matcher(name).matches() && out.stream().noneMatch(m -> m.name().equals(name))) {
                out.add(new Member(name, role.group(1)));
            }
        }
        return out;
    }
}
