package com.swiftcheck.aml.plugins.ptpep.courts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Extracteurs des pages des hautes juridictions portugaises. Un extracteur par site : aucun nom n'est embarqué dans le
 * plugin, les noms sont toujours lus dans la page officielle téléchargée à l'exécution.
 */
final class CourtPages {

    private CourtPages() {
    }

    /** Un titulaire lu sur une page ; {@code role} est la fonction écrite sur la page. */
    record Member(String name, String role) {
    }

    static final Map<String, Function<List<String>, List<Member>>> EXTRACTORS = new LinkedHashMap<>();

    static {
        EXTRACTORS.put("tcontas", CourtPages::tribunalDeContas);
    }

    /** Fonctions qui précèdent le nom sur la page du Tribunal de Contas. */
    private static final Pattern ROLE = Pattern.compile(
            "^(Presidente do Tribunal de Contas|Vice-Presidente|Ju[ií]z[a]? Conselheir[oa])$");

    /** Une ligne de nom : mots commençant par une majuscule ou particules, sans chiffre, sans fonction ni mention. */
    private static final Pattern NAME = Pattern.compile(
            "^\\p{Lu}[\\p{L}'’.-]*(?: (?:\\p{Lu}[\\p{L}'’.-]*|d[aeo]s?|e|do|dos|das|de))*(?: \\p{Lu}[\\p{L}'’.-]*)$");

    /**
     * Page « Composição » du Tribunal de Contas. Chaque fonction est suivie, sur la ligne suivante, du nom de son
     * titulaire :
     * <pre>
     * Presidente do Tribunal de Contas | Filipa Urbano Calvão
     * Vice-Presidente | António Francisco Martins
     * Juíza Conselheira | Helena Maria Mateus de Vasconcelos Abreu Lopes
     * </pre>
     * Les lignes « Colocado na … Secção » et « Ver mais » qui suivent sont ignorées.
     */
    static List<Member> tribunalDeContas(List<String> lines) {
        List<Member> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (!ROLE.matcher(lines.get(i)).matches()) {
                continue;
            }
            String name = lines.get(i + 1);
            if (NAME.matcher(name).matches() && !ROLE.matcher(name).matches()
                    && out.stream().noneMatch(m -> m.name().equals(name))) {
                out.add(new Member(name, lines.get(i)));
            }
        }
        return out;
    }
}
