package com.swiftcheck.aml.plugins.depep.vita;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Lecture des CV (« Vita », « Curriculum Vitae », « Lebenslauf ») publiés en PDF par la Bundeswehr (Story 3).
 * <p>
 * Règle UAT « Alfons Mais » : la personne est identifiée par le <b>contenu</b> du document (grade, nom, fonction),
 * jamais par le nom du fichier dans l'URL. Chaque lecture rend la preuve exigée par AC1 : URL du document, numéro de
 * la page où le nom a été lu, extrait source et SHA-256 du PDF.
 * <p>
 * Le serveur de bundeswehr.de renvoie souvent le PDF compressé en gzip sans qu'on le demande : le contenu est
 * décompressé avant la lecture et l'empreinte porte sur le PDF lui-même. Un document de plus de 50 Mo est refusé.
 * Un document sans couche texte (scan, image) est signalé {@link Kind#IMAGE_ONLY} : il demande un OCR, qui n'est pas
 * fait ici, et la fiche éventuelle reste à revoir (REVIEW).
 */
public final class VitaDocuments {

    /** Taille maximale d'un document (Story 3 : rejet avec alerte au-delà de 50 Mo). */
    public static final long MAX_BYTES = 50L * 1024 * 1024;

    /** Nature du document lu. */
    public enum Kind { CV, ORGANIGRAM, OTHER, IMAGE_ONLY, TOO_LARGE, NOT_PDF, UNREADABLE }

    /**
     * Ce qu'un document apprend. {@code rank} est le grade (ou la fonction civile de tête : « Präsident »),
     * {@code title} les titres académiques, {@code name} le nom sans titre ni grade, {@code page} la page (1…n) où le
     * nom a été lu, {@code excerpt} la ligne du document qui le porte, {@code confidence} « high » quand deux endroits
     * du document concordent (en-tête et champ « Name »), « medium » sinon.
     */
    public record Vita(String url, Kind kind, String sha256, long bytes, int pages, String rank, String title,
                       String name, String function, String birthDate, String birthPlace, int page, String excerpt,
                       String confidence, List<String> warnings) {

        public boolean hasPerson() {
            return name != null && !name.isBlank();
        }

        /** Officier général ou amiral (classe F.1 stricte de la Story 2). */
        public boolean isGeneralOfficer() {
            return rank != null && GENERAL_RANKS.matcher(rank).matches();
        }

        /** Notes de preuve, une par ligne, au format des autres parsers (source / page / sha256 / extrait). */
        public List<String> evidenceNotes(String extractedAt) {
            List<String> notes = new ArrayList<>();
            notes.add("document: " + url + " ; page: " + (page > 0 ? page : "?") + " ; extracted_at: " + extractedAt
                    + (sha256 == null ? "" : " ; sha256: " + sha256));
            if (excerpt != null) notes.add("extrait: « " + excerpt + " »");
            notes.add("confidence: " + confidence + (kind == Kind.IMAGE_ONLY ? " (REVIEW : document image, OCR requis)" : ""));
            return notes;
        }
    }

    // --- Grades -----------------------------------------------------------------------------------------------

    /** Grades de la Bundeswehr, du plus long au plus court (pour que « Generalleutnant » passe avant « General »). */
    static final String RANKS = String.join("|",
            "Generaloberstabsarzt", "Generalstabsarzt", "Generalarzt", "Admiralstabsarzt", "Admiralarzt",
            "Generalleutnant", "Generalmajor", "Brigadegeneral", "General", "Vizeadmiral", "Konteradmiral",
            "Flottillenadmiral", "Admiral", "Flottenarzt", "Oberstarzt", "Oberstleutnant\\s+i\\.\\s?G\\.",
            "Oberstleutnant", "Oberst\\s+i\\.\\s?G\\.", "Oberst", "Kapitän\\s+zur\\s+See", "Fregattenkapitän",
            "Korvettenkapitän", "Oberstabsarzt", "Major", "Hauptmann", "Kapitänleutnant",
            // Fonctions civiles de tête des Bundesämter, placées comme un grade devant le nom.
            "Präsidentin", "Präsident", "Vizepräsidentin", "Vizepräsident");

    static final Pattern GENERAL_RANKS = Pattern.compile(
            "(?i)Generaloberstabsarzt|Generalstabsarzt|Generalarzt|Admiralstabsarzt|Admiralarzt|Generalleutnant"
                    + "|Generalmajor|Brigadegeneral|General|Vizeadmiral|Konteradmiral|Flottillenadmiral|Admiral");

    /** Titres placés devant un nom (regex utilisée pour chercher un grade devant un nom connu). */
    private static final String TITLES = "(?:(?:\\S*\\.|\\(univ\\.?\\)|Assessor|[A-ZÄÖÜ][a-zäöüß]+(?:wissenschaftler|ingenieur|kaufmann|ökonom)(?:in)?)\\s+)*";
    /** Un mot de titre : se termine par un point (Prof., Dr., Dipl.-Ing., habil., jur.), « (Univ.) » ou « Assessor ». */
    private static final Pattern TITLE_TOKEN = Pattern.compile(
            "(?i)\\S+\\.|\\(univ\\.?\\)|assessor|habil|[a-zäöüß]+(?:wissenschaftler|ingenieur|kaufmann|ökonom)(?:in)?");
    private static final Pattern NAME_WORD = Pattern.compile("[A-ZÄÖÜ][\\p{L}'’.-]*|von|van|de|der|zu|du|di|da|le|la");

    private static final Pattern RANK_LINE = Pattern.compile("^(" + RANKS + ")\\s+(.+)$");
    private static final Pattern RANK_ONLY = Pattern.compile("^(" + RANKS + ")$");
    /** « Generalmajor Michael Hogrebe ist … » : grade et nom au début d'une phrase. */
    private static final Pattern RANK_SENTENCE = Pattern.compile(
            "^(" + RANKS + ")\\s+((?:" + TITLES + ")?[A-ZÄÖÜ][\\p{L}'’-]*(?:\\s+(?:[A-ZÄÖÜ][\\p{L}'’.-]*|von|van|de|der|zu)){1,3})\\s+(?:ist|führt|wurde|kommandiert|übernahm)\\b.*$");
    /** Fonctionnaires : « Erster Direktor beim BAPersBw Thomas Uhle », « Direktorin beim BAPersBw Kornelia Fischer ». */
    private static final Pattern CIVIL_GRADE_LINE = Pattern.compile(
            "^((?:Erste[r]?\\s+)?Direktor(?:in)?\\s+(?:beim|im|am|des)\\s+\\S+|Ministerialdirektor(?:in)?|Ministerialdirigent(?:in)?"
                    + "|Leitende[r]?\\s+Regierungsdirektor(?:in)?)\\s+(.+?)(?:\\s+ist\\b.*)?$");
    /** Présidents d'établissement : « Präsident HSU/UniBw H, Prof. Dr. Klaus Beckmann ». */
    private static final Pattern PRESIDENT_COMMA = Pattern.compile("^((?:Vize)?[Pp]räsident(?:in)?)\\s+([^,]{2,60}),\\s*(.+)$");
    private static final Pattern HEAD_HEADER = Pattern.compile("^(VIZEPRÄSIDENTIN|VIZEPRÄSIDENT|PRÄSIDENTIN|PRÄSIDENT)$");
    private static final Pattern NAME_FIELD = Pattern.compile("^Name\\s*:?\\s+(.+)$");
    private static final Pattern BIRTH_FIELD = Pattern.compile(
            "(?i)(?:Geburtsdatum|Geburtstag|geboren(?:\\s+am)?)\\s*:?\\s+(\\d{1,2}\\.\\s*(?:[A-Za-zäöüÄÖÜ]+|\\d{1,2}\\.)\\s*\\d{4})(?:\\s+in\\s+([^,\\n]+))?");
    /** Début d'un libellé de fonction (pour ne pas prendre une ligne quelconque du parcours). */
    private static final String ROLE_START = "(?:Kommandeur|Kommandierende|Leiter|Chef|Befehlshaber|Inspekteur|Amtschef|Präsident|"
            + "Vizepräsident|Stellvertret|Direktor|Abteilungsleiter|Unterabteilungsleiter|Beauftragte|Deutsche[r]?\\s+Militärische|"
            + "Kommandant|Commander|Deputy|Referent|Dezernatsleiter|Gruppenleiter)\\S*";
    /** « Seit 10/2024 Vizepräsidentin des … », « seit 19. März 2025 Amtschef des … ». */
    private static final Pattern SEIT_FUNCTION = Pattern.compile(
            "(?im)^(?:(?:seit|ab)\\s+[^\\n]{0,25}?\\d{4}|[0-9/.]*\\d{4}\\s*[–-]\\s*(?:heute|dato|jetzt))\\s*:?\\s+(" + ROLE_START + "[^\\n]{3,})$");
    /** « Kommandeur Flugbereitschaft BMVg seit 26.09.2025 ». */
    private static final Pattern FUNCTION_SEIT_SUFFIX = Pattern.compile("^(" + ROLE_START + ".{3,}?)\\s+seit\\s+\\S*\\d.*$");
    private static final Pattern HEADER_WORDS = Pattern.compile(
            "(?i)^(curriculum vitae|vita|lebenslauf|name|geburtsdatum|geburtsort|familienstand|wohnort|"
                    + "militärischer werdegang|werdegang|ausbildung|beruflicher werdegang|www\\.bundeswehr\\.de|von\\s*[–-]\\s*bis).*");
    private static final Pattern TRAILING_DEGREE = Pattern.compile(",?\\s*(?:Ph\\.\\s?D\\.|M\\.\\s?A\\.|M\\.\\s?Sc\\.|MBA|LL\\.\\s?M\\.)\\s*$");

    private VitaDocuments() {
    }

    // --- Téléchargement ---------------------------------------------------------------------------------------

    /** Résultat brut d'un téléchargement : octets décompressés, ou raison de l'échec. */
    public record Download(String url, byte[] bytes, int status, String error) {
    }

    /** Télécharge un document (deux essais sur erreur réseau), décompresse le gzip et refuse au-delà de 50 Mo. */
    public static Download download(HttpClient client, String url, String userAgent) {
        String lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/pdf,image/*,*/*")
                        .timeout(Duration.ofSeconds(60))
                        .GET().build();
                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() >= 400) {
                    res.body().close();
                    return new Download(url, null, res.statusCode(), "HTTP " + res.statusCode());
                }
                long declared = res.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > MAX_BYTES) {
                    res.body().close();
                    return new Download(url, null, res.statusCode(), "TOO_LARGE " + declared + " octets");
                }
                byte[] raw = readLimited(res.body(), MAX_BYTES);
                if (raw == null) return new Download(url, null, res.statusCode(), "TOO_LARGE > 50 Mo");
                return new Download(url, gunzipIfNeeded(raw), res.statusCode(), null);
            } catch (IOException e) {
                lastError = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Download(url, null, 0, "interrompu");
            } catch (IllegalArgumentException e) {
                return new Download(url, null, 0, "URL invalide : " + e.getMessage());
            }
        }
        return new Download(url, null, 0, lastError);
    }

    private static byte[] readLimited(InputStream in, long max) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    static byte[] gunzipIfNeeded(byte[] raw) throws IOException {
        if (raw.length < 2 || (raw[0] & 0xff) != 0x1f || (raw[1] & 0xff) != 0x8b) return raw;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return gz.readAllBytes();
        }
    }

    // --- Lecture ----------------------------------------------------------------------------------------------

    /** Lit un document téléchargé (PDF, ou image signalée IMAGE_ONLY). */
    public static Vita read(String url, byte[] bytes) {
        List<String> warnings = new ArrayList<>();
        if (bytes == null) {
            return new Vita(url, Kind.UNREADABLE, null, 0, 0, null, null, null, null, null, null, 0, null, "none", warnings);
        }
        String sha = sha256(bytes);
        if (bytes.length > MAX_BYTES) {
            return new Vita(url, Kind.TOO_LARGE, sha, bytes.length, 0, null, null, null, null, null, null, 0, null, "none", warnings);
        }
        if (isHtml(bytes)) {
            // Page « Vita … » de bundeswehr.de (ex. Führungsakademie) : même lecture que le texte d'un PDF.
            String text = htmlText(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            Vita v = extract(url, List.of(text), sha, bytes.length, warnings);
            // La phrase « … ist seit 23. Juni 2026 Kommandeur der Führungsakademie » est la fonction actuelle ; la
            // ligne qui suit le titre n'est qu'une légende de photo.
            Matcher ms = IST_SEIT_FUNCTION.matcher(text.replace('\n', ' '));
            String seit = ms.find() ? ms.group(1).replaceAll("\\s+", " ").trim() : seitFunction(text);
            if (v.hasPerson() && seit != null && !isNoise(seit)) {
                v = new Vita(v.url(), v.kind(), v.sha256(), v.bytes(), v.pages(), v.rank(), v.title(), v.name(), seit,
                        v.birthDate(), v.birthPlace(), v.page(), v.excerpt(), v.confidence(), v.warnings());
            }
            return v;
        }
        if (!isPdf(bytes)) {
            Kind kind = isImage(bytes) ? Kind.IMAGE_ONLY : Kind.NOT_PDF;
            if (kind == Kind.IMAGE_ONLY) warnings.add("image sans couche texte : OCR requis");
            return new Vita(url, kind, sha, bytes.length, 0, null, null, null, null, null, null, 0, null, "none", warnings);
        }
        List<String> pages = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(stripper.getText(doc));
            }
        } catch (IOException e) {
            warnings.add("PDF illisible : " + e.getMessage());
            return new Vita(url, Kind.UNREADABLE, sha, bytes.length, 0, null, null, null, null, null, null, 0, null, "none", warnings);
        }
        return extract(url, pages, sha, bytes.length, warnings);
    }

    /** Extraction à partir du texte des pages (séparée pour les tests). */
    public static Vita extract(String url, List<String> pages, String sha, long size, List<String> warnings) {
        String all = String.join("\n", pages);
        int meaningful = all.replaceAll("(?i)www\\.bundeswehr\\.de|curriculum vitae|tel\\.?[^\\n]*|fax[^\\n]*|e-mail[^\\n]*|\\d{5}\\s+\\p{L}+", "")
                .replaceAll("\\s+", "").length();
        boolean organigram = all.toLowerCase(Locale.ROOT).contains("organigramm") || url.toLowerCase(Locale.ROOT).contains("organigramm");

        for (int p = 0; p < pages.size(); p++) {
            List<String> lines = lines(pages.get(p));
            Found f = findPerson(lines);
            if (f == null) continue;
            String nameField = findNameField(lines);
            String confidence = "medium";
            if (nameField != null && sameName(nameField, f.name)) confidence = "high";
            if (f.name == null && nameField != null) f.name = nameField;
            String[] tn = splitTitle(f.name);
            String title = joinTitles(f.title, tn[0]);
            String name = TRAILING_DEGREE.matcher(tn[1]).replaceAll("").trim();
            if (!looksLikeName(name)) continue;
            String function = f.function != null && !isNoise(f.function) ? f.function : seitFunction(all);
            Matcher b = BIRTH_FIELD.matcher(all);
            String birthDate = null, birthPlace = null;
            if (b.find()) {
                birthDate = b.group(1).replaceAll("\\s+", " ").trim();
                birthPlace = b.group(2) == null ? null : b.group(2).trim();
            }
            Kind kind = organigram ? Kind.ORGANIGRAM : Kind.CV;
            return new Vita(url, kind, sha, size, pages.size(), f.rank, title, name, function, birthDate, birthPlace,
                    p + 1, f.excerpt, confidence, warnings);
        }
        Kind kind = meaningful < 200 ? Kind.IMAGE_ONLY : organigram ? Kind.ORGANIGRAM : Kind.OTHER;
        if (kind == Kind.IMAGE_ONLY) warnings.add("pas de couche texte exploitable : OCR requis");
        return new Vita(url, kind, sha, size, pages.size(), null, null, null, null, null, null, 0, null, "none", warnings);
    }

    /** Une personne trouvée dans les lignes d'une page. */
    private static final class Found {
        String rank, title, name, function, excerpt;
    }

    /** Lignes non vides, espaces réduites ; les blocs d'adresse (colonne de droite) sont gardés tels quels. */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\\R")) {
            // Puces (« ▪ », « • ») et caractères de remplacement en tête de ligne retirés.
            String t = l.replace(' ', ' ').replaceAll("\\s+", " ").trim()
                    .replaceAll("^[^\\p{L}\\p{N}(„\"]+\\s*", "");
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static Found findPerson(List<String> lines) {
        int limit = Math.min(lines.size(), 120);
        // 1. « Grade Nom » sur une ligne (« Generalleutnant Dr. Christian Freuding »).
        for (int i = 0; i < limit; i++) {
            Matcher m = RANK_LINE.matcher(lines.get(i));
            if (!m.matches()) continue;
            String rest = m.group(2).replaceAll("^(?:a\\.\\s?D\\.\\s+)", "");
            String[] tn = splitTitle(rest);
            if (!looksLikeName(tn[1])) continue;
            Found f = new Found();
            f.rank = normalizeRank(m.group(1));
            f.title = tn[0];
            f.name = rest;
            f.excerpt = lines.get(i);
            f.function = functionAfter(lines, i + 1);
            if (f.function == null) f.function = functionBefore(lines, i);
            return f;
        }
        // 2. Grade seul, éventuellement une ligne de titres, puis le nom (« Brigadegeneral » / « Dipl.-Ing. (univ.) » /
        //    « Bernd Stingl »).
        for (int i = 0; i + 1 < limit; i++) {
            Matcher m = RANK_ONLY.matcher(lines.get(i));
            if (!m.matches()) continue;
            int j = i + 1;
            String titles = null;
            if (j + 1 < lines.size() && splitTitle(lines.get(j) + " X Y")[1].equals("X Y")) {
                titles = lines.get(j);
                j++;
            }
            String next = lines.get(j);
            String[] tn = splitTitle(next);
            if (!looksLikeName(tn[1])) continue;
            Found f = new Found();
            f.rank = normalizeRank(m.group(1));
            f.title = joinTitles(titles, tn[0]);
            f.name = next;
            f.excerpt = String.join(" ", lines.subList(i, j + 1));
            f.function = functionBefore(lines, i);
            if (f.function == null) f.function = functionAfter(lines, j + 1);
            return f;
        }
        // 2b. Nom puis grade seul sur la ligne suivante (« Armin Havenith » / « Brigadegeneral »).
        for (int i = 1; i < limit; i++) {
            Matcher m = RANK_ONLY.matcher(lines.get(i));
            if (!m.matches()) continue;
            String prev = lines.get(i - 1);
            String[] tn = splitTitle(prev);
            if (!looksLikeName(tn[1])) continue;
            Found f = new Found();
            f.rank = normalizeRank(m.group(1));
            f.title = tn[0];
            f.name = prev;
            f.excerpt = prev + " " + lines.get(i);
            f.function = functionAfter(lines, i + 1);
            return f;
        }
        // 3. « Generalmajor Michael Hogrebe ist … » dans le texte.
        for (int i = 0; i < limit; i++) {
            Matcher m = RANK_SENTENCE.matcher(lines.get(i));
            if (!m.matches()) continue;
            String[] tn = splitTitle(m.group(2));
            if (!looksLikeName(tn[1])) continue;
            Found f = new Found();
            f.rank = normalizeRank(m.group(1));
            f.title = tn[0];
            f.name = m.group(2);
            f.excerpt = lines.get(i);
            f.function = functionInSentence(lines, i);
            if (f.function == null) f.function = functionBefore(lines, i);
            return f;
        }
        // 4. Fonctionnaires : « Erster Direktor beim BAPersBw Thomas Uhle » (fonction au-dessus de la ligne).
        for (int i = 0; i < limit; i++) {
            Matcher m = CIVIL_GRADE_LINE.matcher(lines.get(i));
            if (!m.matches()) continue;
            String[] tn = splitTitle(m.group(2));
            if (!looksLikeName(tn[1])) continue;
            Found f = new Found();
            f.rank = m.group(1);
            f.title = tn[0];
            f.name = m.group(2);
            f.excerpt = lines.get(i);
            f.function = functionBefore(lines, i);
            return f;
        }
        // 5. « Präsident HSU/UniBw H, Prof. Dr. Klaus Beckmann ».
        for (int i = 0; i < Math.min(limit, 15); i++) {
            Matcher m = PRESIDENT_COMMA.matcher(lines.get(i));
            if (!m.matches()) continue;
            String[] tn = splitTitle(m.group(3));
            if (!looksLikeName(tn[1])) continue;
            Found f = new Found();
            f.rank = m.group(1);
            f.title = tn[0];
            f.name = m.group(3);
            f.excerpt = lines.get(i);
            f.function = m.group(1) + " " + m.group(2);
            return f;
        }
        // 6. Champ « Name » seul : le grade est cherché devant le nom ailleurs dans la page.
        String field = findNameField(lines);
        if (field == null) field = nameAfterHeading(lines);
        if (field != null) {
            String last = splitTitle(field)[1];
            last = last.substring(last.lastIndexOf(' ') + 1);
            Pattern rankBefore = Pattern.compile("(" + RANKS + ")\\s+(?:" + TITLES + ")?(?:[A-ZÄÖÜ][\\p{L}-]*\\s+)*" + Pattern.quote(last));
            Found f = new Found();
            f.name = field;
            f.excerpt = "Name " + field;
            String squeezedName = squeeze(splitTitle(field)[1]);
            Pattern squeezedRank = Pattern.compile("^(" + RANKS.replace("\\s+", "").replace("\\s?", "") + ")", Pattern.CASE_INSENSITIVE);
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                Matcher r = rankBefore.matcher(l);
                if (r.find()) {
                    f.rank = normalizeRank(r.group(1));
                    f.excerpt = l;
                    if (f.function == null) f.function = functionAfter(lines, i + 1);
                    break;
                }
                // Texte espacé lettre par lettre : on compare sans espaces.
                String sq = squeeze(l);
                Matcher sr = squeezedRank.matcher(sq);
                if (sq.endsWith(squeezedName) && sr.find() && sr.end() + squeezedName.length() <= sq.length()) {
                    String rank = sr.group(1);
                    for (String known : RANKS.split("\\|")) {
                        String plain = known.replace("\\s+", " ").replace("\\s?", "").replace("\\.", ".");
                        if (squeeze(plain).equals(rank)) {
                            f.rank = normalizeRank(plain);
                            break;
                        }
                    }
                    f.excerpt = l;
                    if (i + 1 < lines.size() && !NAME_FIELD.matcher(lines.get(i + 1)).matches()) {
                        f.function = lines.get(i + 1).replaceAll("(?<=\\p{L}) (?=\\p{Ll})", "").replaceAll("\\s+", " ");
                    }
                    break;
                }
            }
            // En-tête en capitales d'un Bundesamt (« VIZEPRÄSIDENT ») : fonction civile de tête.
            if (f.rank == null) {
                for (String l : lines) {
                    Matcher h = HEAD_HEADER.matcher(l);
                    if (h.matches()) {
                        String w = h.group(1).toLowerCase(Locale.GERMAN);
                        f.rank = w.startsWith("vize") ? "Vize" + w.substring(4) : Character.toUpperCase(w.charAt(0)) + w.substring(1);
                        break;
                    }
                }
            }
            if (f.function == null) f.function = seitFunction(String.join("\n", lines));
            return f;
        }
        return null;
    }

    /** Nom écrit seul sous « CURRICULUM VITAE », précédé de ses titres (« Univ.-Prof. … Eva-Maria Kern, MBA »). */
    private static String nameAfterHeading(List<String> lines) {
        for (int i = 0; i + 1 < Math.min(lines.size(), 6); i++) {
            if (!lines.get(i).matches("(?i)curriculum vitae|vita|lebenslauf")) continue;
            String next = lines.get(i + 1);
            String[] tn = splitTitle(next);
            if (tn[0] != null && looksLikeName(tn[1])) return next;
        }
        return null;
    }

    /** Fonction écrite sous l'en-tête « Grade Nom » (une ou deux lignes), sauf rubriques du CV. */
    private static String functionAfter(List<String> lines, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < Math.min(lines.size(), from + 3); i++) {
            String l = lines.get(i);
            // Suite d'un mot coupé en fin de ligne (« Personalma- » / « nagement der Bundeswehr »).
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-' && Character.isLowerCase(l.charAt(0))) {
                sb.append(l);
                continue;
            }
            if (HEADER_WORDS.matcher(l).matches() || l.matches("^\\d.*") || RANK_LINE.matcher(l).matches()
                    || l.matches("(?i)^(geboren|seit)\\b.*") || l.equals(l.toUpperCase(Locale.ROOT)) && l.length() > 3
                    || isNoise(l)) {
                break;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(l);
            if (!l.endsWith(" und") && !l.endsWith("-") && !l.endsWith(",") && !l.endsWith(" der") && !l.endsWith(" des")) break;
        }
        String f = sb.toString().replaceAll("-(?=[a-zäöü])", "").replaceAll("\\s+(?:und|der|des|,)$", "").trim();
        return f.isEmpty() ? null : f;
    }

    /** Fonction écrite au-dessus du grade (« Kommandierender General » / « Luftwaffentruppenkommando »). */
    private static String functionBefore(List<String> lines, int rankIndex) {
        List<String> parts = new ArrayList<>();
        for (int i = rankIndex - 1; i >= Math.max(0, rankIndex - 3); i--) {
            String l = lines.get(i);
            if (isNoise(l)) continue;
            if (HEADER_WORDS.matcher(l).matches() || l.matches(".*\\d.*") || l.equals(l.toUpperCase(Locale.ROOT))) break;
            parts.add(0, l);
        }
        String f = String.join(" ", parts).trim();
        return f.isEmpty() ? null : f;
    }

    /** Lignes qui ne sont pas une fonction : adresse, contact, naissance, distinctions, fin de mot coupé. */
    static boolean isNoise(String l) {
        return l.contains("@") || l.matches("(?i).*(www\\.|bundeswehr\\.org|tel\\.|fax|e-mail|mail:).*")
                || l.matches("(?i).*\\b(geboren|wurde am|verheiratet|ledig|auszeichnung|\\p{L}*medaille|ehrenkreuz|\\p{L}*abzeichen|kinder)\\b.*")
                || l.matches("(?i)^geb\\..*")
                || Character.isLowerCase(l.charAt(0));
    }

    private static String seitFunction(String all) {
        List<String> ls = lines(all);
        for (int i = 0; i < Math.min(ls.size(), 12); i++) {
            Matcher s = FUNCTION_SEIT_SUFFIX.matcher(ls.get(i));
            if (s.matches() && !isNoise(s.group(1))) return s.group(1).trim();
        }
        Matcher m = SEIT_FUNCTION.matcher(String.join("\n", ls));
        while (m.find()) {
            String f = m.group(1).replaceAll("\\s+", " ").trim();
            if (!isNoise(f)) return f.replaceAll("[,;]$", "");
        }
        return null;
    }

    /** Fonction dans la phrase qui suit « Generalmajor X ist » : « seit Januar 2025 Stellvertreter … Command. ». */
    private static String functionInSentence(List<String> lines, int i) {
        StringBuilder sb = new StringBuilder(lines.get(i).replaceFirst("^.*?\\b(?:ist|führt|wurde|kommandiert|übernahm)\\b", ""));
        for (int k = i + 1; k < Math.min(lines.size(), i + 4) && !sb.toString().trim().endsWith("."); k++) {
            if (lines.get(k).matches("^\\d{4}.*")) break;
            sb.append(' ').append(lines.get(k));
        }
        String f = sb.toString().replaceAll("\\s+", " ").trim()
                .replaceFirst("(?i)^seit\\s+(?:dem\\s+)?(?:\\d{1,2}\\.\\s*)?[\\p{L}0-9./]*\\s*\\d{4}\\s+", "")
                .replaceFirst("(?i)^als\\s+", "").replaceAll("\\.$", "").trim();
        return f.isEmpty() || f.length() > 200 ? null : f;
    }

    /** Ligne sans espaces (pour le texte espacé lettre par lettre : « G e n e r al l e ut n a n t »). */
    private static String squeeze(String s) {
        return s.replaceAll("\\s+", "").toLowerCase(Locale.GERMAN);
    }

    private static String findNameField(List<String> lines) {
        for (int i = 0; i < Math.min(lines.size(), 60); i++) {
            Matcher m = NAME_FIELD.matcher(lines.get(i));
            if (m.matches()) {
                String v = m.group(1).replaceAll("\\s{2,}.*", "").trim();
                String[] tn = splitTitle(v);
                if (looksLikeName(TRAILING_DEGREE.matcher(tn[1]).replaceAll("").trim())) return v;
            }
        }
        return null;
    }

    // --- Outils -----------------------------------------------------------------------------------------------

    /**
     * [titres académiques ou null, nom] ; les titres ne vont jamais dans le nom. Les mots de tête qui finissent par
     * un point (« Univ.-Prof. Dipl.-Ing. Dr. mont. Dr.-Ing. habil. »), « (Univ.) », « Assessor » et les diplômes
     * (« Sportwissenschaftler ») sont des titres ; les grades universitaires de fin (« , Ph.D. », « , MBA ») sont
     * retirés du nom.
     */
    public static String[] splitTitle(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        String[] words = s.split(" ");
        int i = 0;
        while (i < words.length - 2 && TITLE_TOKEN.matcher(words[i]).matches()) i++;
        // Un dernier mot de titre n'est pris que s'il reste au moins deux mots pour le nom.
        if (i == words.length - 2 && TITLE_TOKEN.matcher(words[i]).matches() && !NAME_WORD.matcher(words[i]).matches()) i++;
        String title = i == 0 ? null : String.join(" ", java.util.Arrays.copyOfRange(words, 0, i));
        String name = String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length));
        name = TRAILING_DEGREE.matcher(name).replaceAll("").trim();
        return new String[] {title, name};
    }

    private static String joinTitles(String a, String b) {
        if (a == null) return b;
        if (b == null || a.contains(b)) return a;
        return a + " " + b;
    }

    /** Nom de personne plausible : 2 à 5 mots à majuscule (particules admises), sans chiffre. */
    public static boolean looksLikeName(String s) {
        if (s == null) return false;
        String[] words = s.split("\\s+");
        if (words.length < 2 || words.length > 5) return false;
        int capitalized = 0;
        for (String w : words) {
            if (!NAME_WORD.matcher(w).matches()) return false;
            if (Character.isUpperCase(w.charAt(0))) capitalized++;
            if (w.length() > 2 && w.equals(w.toUpperCase(Locale.ROOT)) && !w.contains(".")) return false;
        }
        if (s.matches("(?i).*\\b(Bundeswehr|Kommando|Amt|Zentrum|Schule|Division|Brigade|Regiment|Geschwader|Bataillon|Straße|Kaserne|Allee)\\b.*")) {
            return false;
        }
        return capitalized >= 2;
    }

    private static boolean sameName(String a, String b) {
        String x = splitTitle(a)[1].toLowerCase(Locale.ROOT).replaceAll("[^\\p{L} ]", "");
        String y = splitTitle(b)[1].toLowerCase(Locale.ROOT).replaceAll("[^\\p{L} ]", "");
        return x.equals(y) || x.endsWith(y) || y.endsWith(x);
    }

    static String normalizeRank(String r) {
        return r.replaceAll("\\s+", " ").replaceAll("i\\. ?G\\.", "i.G.");
    }

    static boolean isPdf(byte[] b) {
        return b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }

    /** « … ist seit 23. Juni 2026 Kommandeur der Führungsakademie der Bundeswehr in Hamburg. » */
    private static final Pattern IST_SEIT_FUNCTION = Pattern.compile(
            "\\b(?:ist|führt)\\s+seit\\s+(?:dem\\s+)?(?:\\d{1,2}\\.\\s*)?(?:\\p{L}+\\s+)?\\d{4}\\s+(" + ROLE_START + "[^.]{3,200}?)(?:\\s+in\\s+\\p{Lu}\\p{L}+)?\\.");

    /** Métadonnées de page (date de publication, durée de lecture, partage, données JSON) : pas du contenu. */
    private static final Pattern HTML_META_LINE = Pattern.compile(
            "(?i)^(?:veröffentlichungsdatum.*|datum:?|lesedauer:?|\\d+ min|\\d{2}\\.\\d{2}\\.\\d{4}|auf \\w+ teilen|über e-mail teilen"
                    + "|zurück zu:.*|seite (?:vorlesen|drucken).*|\\{.*\\}|(?-i:[A-ZÄÖÜ\\- ]{6,}))$");

    static boolean isHtml(byte[] b) {
        String head = new String(b, 0, Math.min(b.length, 1024), java.nio.charset.StandardCharsets.UTF_8)
                .stripLeading().toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<html");
    }

    /**
     * Texte d'une page HTML, une ligne par bloc, à partir du titre {@code <h1>} (la navigation qui précède est
     * ignorée). Le préfixe « Vita » du titre est retiré : « Vita Konteradmiral Sascha Helge Rackwitz » devient une
     * ligne « grade + nom ».
     */
    static String htmlText(String html) {
        int start = html.indexOf("<h1");
        String s = start >= 0 ? html.substring(start) : html;
        s = s.replaceAll("(?is)<(script|style|noscript|button|svg)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>|</(p|div|h[1-6]|li|tr|section|article|header|footer|span|a)>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = decodeEntities(s);
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\\R")) {
            String t = line.replace(' ', ' ').replaceAll("\\s+", " ").trim();
            if (t.isEmpty() || HTML_META_LINE.matcher(t).matches()) continue;
            out.append(t.replaceFirst("^(?i)(?:vita|lebenslauf|curriculum vitae)\\s+(?=\\p{L})", "")).append('\n');
        }
        return out.toString();
    }

    private static String decodeEntities(String s) {
        String r = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&auml;", "ä").replace("&ouml;", "ö")
                .replace("&uuml;", "ü").replace("&Auml;", "Ä").replace("&Ouml;", "Ö").replace("&Uuml;", "Ü")
                .replace("&szlig;", "ß").replace("&raquo;", "»").replace("&laquo;", "«").replace("&ndash;", "–");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(r);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static boolean isImage(byte[] b) {
        return b.length > 4 && ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                || (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
