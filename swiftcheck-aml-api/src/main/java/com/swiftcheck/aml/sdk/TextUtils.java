package com.swiftcheck.aml.sdk;

/**
 * Petites fonctions de mise en forme partagées par les plugins (assemblage de texte, genre).
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * Assemble les morceaux non vides avec un séparateur.
     * Exemple : {@code joinNonBlank(", ", "1 Main St", null, "Paris")} donne {@code "1 Main St, Paris"}.
     *
     * @return le texte assemblé, ou {@code null} si tous les morceaux sont vides
     */
    public static String joinNonBlank(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(separator);
                }
                sb.append(part.trim());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Civilités féminines : sans ce cas, « Madame » commencerait par m et deviendrait male. */
    private static final java.util.Set<String> FEMALE_TITLES = java.util.Set.of(
            "madame", "mme", "mademoiselle", "mlle", "mrs", "ms", "miss");

    /**
     * Convertit un genre ou une civilité saisis librement en valeur FtM.
     * Exemples : "M", "Male", "Masculin", "Monsieur" → male ; "F", "Female", "Femme", "Madame", "Mme" → female.
     */
    public static String toFtmGender(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String word = raw.trim().toLowerCase(java.util.Locale.ROOT).replace(".", "");
        if (word.isEmpty()) {
            return null;
        }
        if (FEMALE_TITLES.contains(word)) {
            return "female";
        }
        char first = word.charAt(0);
        if (first == 'm') {
            return "male";
        }
        if (first == 'f') {
            return "female";
        }
        return null;
    }
}
