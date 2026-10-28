package com.swiftcheck.aml.plugins.frpep.localexecutives;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Populations des collectivités, calculées à partir des populations municipales des communes (API Découpage
 * administratif d'Etalab, données Insee) :
 * <pre>[{"code": "69123", "nom": "Lyon", "population": 519127, "codeDepartement": "69", "codeRegion": "84",
 *   "codeEpci": "200046977"}, …]</pre>
 * Une intercommunalité, un département ou une région a pour population la somme de ses communes. Les collectivités
 * à statut particulier sont rattachées à la région (Corse « 94 », collectivités d'outre-mer « 975 »…) ou au
 * département (Martinique « 972 », Guyane « 973 ») du même code ; la Métropole de Lyon (« 69M ») à son EPCI.
 */
final class Populations {

    /** Collectivités à statut particulier dont le code RNE ne correspond ni à une région ni à un département. */
    private static final Map<String, String> SPECIAL_EPCI = Map.of("69M", "200046977");   // Métropole de Lyon

    private final Map<String, Long> communes = new HashMap<>();
    private final Map<String, Long> epcis = new HashMap<>();
    private final Map<String, Long> departments = new HashMap<>();
    private final Map<String, Long> regions = new HashMap<>();

    static Populations read(Path json) throws IOException {
        Populations p = new Populations();
        JsonNode list = new ObjectMapper().readTree(json.toFile());
        if (!list.isArray() || list.isEmpty()) {
            throw new IllegalStateException(json.getFileName() + " : liste des communes vide ou format changé.");
        }
        for (JsonNode c : list) {
            long population = c.path("population").asLong(0);
            p.communes.put(c.path("code").asText(), population);
            add(p.epcis, c.path("codeEpci").asText(""), population);
            add(p.departments, c.path("codeDepartement").asText(""), population);
            add(p.regions, c.path("codeRegion").asText(""), population);
        }
        return p;
    }

    private static void add(Map<String, Long> map, String code, long population) {
        if (!code.isEmpty()) {
            map.merge(code, population, Long::sum);
        }
    }

    long commune(String code) {
        return communes.getOrDefault(code, 0L);
    }

    long epci(String siren) {
        return epcis.getOrDefault(siren, 0L);
    }

    long department(String code) {
        return departments.getOrDefault(code, 0L);
    }

    long region(String code) {
        return regions.getOrDefault(code, 0L);
    }

    /** Collectivité à statut particulier (code RNE « 94 », « 972 », « 69M »…). */
    long specialCollectivity(String code) {
        if (SPECIAL_EPCI.containsKey(code)) {
            return epci(SPECIAL_EPCI.get(code));
        }
        return regions.containsKey(code) ? region(code) : department(code);
    }
}
