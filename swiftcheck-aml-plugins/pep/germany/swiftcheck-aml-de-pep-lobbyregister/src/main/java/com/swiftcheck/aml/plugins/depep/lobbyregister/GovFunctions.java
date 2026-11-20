package com.swiftcheck.aml.plugins.depep.lobbyregister;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import java.util.regex.Pattern;

import com.swiftcheck.aml.plugins.depep.lobbyregister.LobbyregisterModels.RecentGovernmentFunction;

/**
 * Qualification des fonctions publiques déclarées au Lobbyregister (« recentGovernmentFunction », cinq dernières
 * années) au regard du GwG § 1 Abs. 12.
 * <p>
 * Le registre déclare aussi de simples postes de l'administration fédérale (Referent, Sachbearbeiter, Praktikant) :
 * ils ne font pas une PEP. Sont retenus : les députés au Bundestag (Nr. 1 b), les membres du gouvernement fédéral
 * (Nr. 1 a) et, dans l'administration, les seules fonctions de rang PEP (Staatssekretär, Botschafter, chef d'une
 * autorité fédérale suprême).
 */
final class GovFunctions {

    /** Statut d'une PEP d'après la fin de fonction déclarée (GwG § 15 Abs. 7 : au moins 12 mois après la fin). */
    enum Status { ACTIVE_PEP, COOLING, EXPIRED }

    /** Fonction PEP qualifiée : libellé allemand d'origine, alinéa du GwG, statut, date de fin. */
    record PepFunction(String label, String gwgReference, Status status, String endDate, String coolingUntil) {

        /** Seules une PEP en fonction ou en période de refroidissement fondent un lien RCA. */
        boolean propagates() {
            return status != Status.EXPIRED;
        }

        String statusNote() {
            return switch (status) {
                case ACTIVE_PEP -> "pep_status: ACTIVE_PEP";
                case COOLING -> "pep_status: COOLING (Funktion beendet " + endDate + ", bis " + coolingUntil + ")";
                case EXPIRED -> "pep_status: EXPIRED (Funktion beendet " + endDate + ")";
            };
        }
    }

    /** Fonctions de l'administration fédérale de rang PEP (GwG § 1 Abs. 12 S. 2 Nr. 1 a et f). */
    private static final Pattern ADMIN_PEP = Pattern.compile(
            "(?iu)^(?:beamtete[r]?\\s+)?staatssekretär(?:in)?\\b.*|^botschafter(?:in)?\\b.*|^ständige[r]? vertreter(?:in)? der bundesrepublik\\b.*");
    private static final Pattern ADMIN_EXCLUDED = Pattern.compile("(?iu).*\\b(büro|referent|persönliche|praktik|mitarbeiter|assistenz)\\w*.*");

    private GovFunctions() {
    }

    /** Fonction PEP qualifiée, ou null si la fonction déclarée ne fait pas une PEP. */
    static PepFunction qualify(RecentGovernmentFunction f, LocalDate today) {
        if (f == null) return null;
        String type = f.type != null && f.type.code != null ? f.type.code : "";
        String label;
        String ref;
        if ("HOUSE_OF_REPRESENTATIVES".equals(type) || f.houseOfRepresentatives != null) {
            label = f.houseOfRepresentatives != null && f.houseOfRepresentatives.function != null
                    && f.houseOfRepresentatives.function.de != null
                    ? f.houseOfRepresentatives.function.de : "Mitglied des Deutschen Bundestages";
            ref = "GwG § 1 Abs. 12 S. 2 Nr. 1 b) (Parlamentsabgeordnete)";
        } else if ("FEDERAL_GOVERNMENT".equals(type) || f.federalGovernment != null) {
            var g = f.federalGovernment;
            String fn = g != null && g.function != null && g.function.de != null ? g.function.de : "Mitglied der Bundesregierung";
            String dep = g != null && g.department != null ? (g.department.shortTitle != null ? g.department.shortTitle : g.department.title) : null;
            label = fn + (dep != null ? " (" + dep + ")" : "");
            ref = "GwG § 1 Abs. 12 S. 2 Nr. 1 a) (Regierungsmitglieder)";
        } else if (f.federalAdministration != null && f.federalAdministration.function != null) {
            String fn = f.federalAdministration.function.trim().replaceAll("\\s+", " ");
            if (!ADMIN_PEP.matcher(fn).matches() || ADMIN_EXCLUDED.matcher(fn).matches()) return null;
            String auth = f.federalAdministration.supremeFederalAuthorityShort != null
                    ? f.federalAdministration.supremeFederalAuthorityShort : f.federalAdministration.supremeFederalAuthority;
            label = fn + (auth != null && !auth.isBlank() ? " (" + auth.trim() + ")" : "");
            ref = fn.toLowerCase(Locale.GERMAN).startsWith("botschaft")
                    ? "GwG § 1 Abs. 12 S. 2 Nr. 1 f) (Botschafter)" : "GwG § 1 Abs. 12 S. 2 Nr. 1 a) (Staatssekretäre)";
        } else {
            return null;
        }

        if (!f.ended) return new PepFunction(label, ref, Status.ACTIVE_PEP, null, null);
        LocalDate end = parseEnd(f.endDate);
        if (end == null) return new PepFunction(label, ref, Status.COOLING, "?", "?");
        LocalDate until = end.plusMonths(12);
        Status s = today.isAfter(until) ? Status.EXPIRED : Status.COOLING;
        return new PepFunction(label, ref, s, f.endDate, until.toString());
    }

    /** « 2021-12 » ou « 2021-12-31 » : dernier jour du mois quand seul le mois est donné. */
    static LocalDate parseEnd(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String s = raw.trim();
            if (s.matches("\\d{4}-\\d{2}")) return YearMonth.parse(s).atEndOfMonth();
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
