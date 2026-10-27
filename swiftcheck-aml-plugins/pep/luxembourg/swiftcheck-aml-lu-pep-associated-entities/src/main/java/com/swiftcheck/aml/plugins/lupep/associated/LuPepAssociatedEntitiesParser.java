package com.swiftcheck.aml.plugins.lupep.associated;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.lupep.associated.ChdDeclarationDownloader.Deputy;
import com.swiftcheck.aml.plugins.lupep.associated.DeclarationReader.Declaration;
import com.swiftcheck.aml.plugins.lupep.associated.DeclarationReader.Holding;
import com.swiftcheck.aml.plugins.lupep.associated.DeclarationReader.HoldingKind;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>lu_pep_associated_entities</b> – Organisations liées aux députés luxembourgeois, d'après leurs déclarations
 * d'intérêts financiers publiées sur chd.lu (Code de conduite des députés, art. 4).
 *
 * <p>Ce plugin couvre la catégorie <b>LU-RCA</b>, fonction <b>Proche associé d'une PEP</b> (directive (UE)
 * 2015/849, art. 3, point 11), côté personnes morales : les organismes dont un député est administrateur ou
 * membre d'un comité (section E de la déclaration) et les sociétés dans lesquelles il détient une participation
 * (section G : répercussions possibles sur la politique publique, ou influence significative).
 *
 * <p>Source PLUGIN_MANAGED : le plugin télécharge lui-même les déclarations ({@link ChdDeclarationDownloader})
 * dans {@code rawDir} ; la conversion ne lit ensuite que ces fichiers. Paramètres :
 * <pre>{@code
 * {"deputiesUrl": "https://www.chd.lu/fr/deputies", "delayMs": "500"}
 * }</pre>
 *
 * <p>Entités FollowTheMoney : le député ({@code Person}, {@code role.pep}), chaque organisme
 * ({@code Organization}, {@code role.rca}), et le lien {@code Directorship} (section E) ou {@code Ownership}
 * (section G, avec le pourcentage s'il est indiqué). Un organisme déclaré par plusieurs députés, ou dans les deux
 * sections, n'est écrit qu'une fois.
 *
 * <p>Limite : une partie des déclarations sont des scans sans texte ; elles sont citées dans le message de
 * l'exécution mais ne produisent aucune entité (il faudrait une reconnaissance de caractères).
 */
@Extension
public class LuPepAssociatedEntitiesParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(LuPepAssociatedEntitiesParser.class);

    static final String ID_PREFIX = "lu-pep-assoc";
    static final String DEFAULT_DEPUTIES_URL = "https://www.chd.lu/fr/deputies";
    static final long DEFAULT_DELAY_MS = 500;

    static final String TOPIC_PEP = "role.pep";
    static final String TOPIC_RCA = "role.rca";

    private static final String POSITION = "Député à la Chambre des Députés du Luxembourg";
    private static final String MANDATE_ROLE =
            "Membre d'un comité ou conseil d'administration, ou activité extérieure (déclaration, section E)";

    /** Pourcentage seul, entre parenthèses ou non : « (50%) », « 50 % », « 33,3% ». */
    private static final Pattern PERCENT = Pattern.compile("\\(?\\s*(\\d{1,3}(?:[.,]\\d+)?)\\s*%\\s*\\)?");
    private static final Pattern PARENTHESES = Pattern.compile("\\(([^()]*)\\)");

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        new ChdDeclarationDownloader(delay(p))
                .download(p.getOrDefault("deputiesUrl", DEFAULT_DEPUTIES_URL).trim(), ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        Report report = convert(ctx.rawDir(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        ParserResult result = dataset.toConversionResult();
        return new ParserResult(result.entityCount(), result.rejectedCount(), result.conflictCount(),
                result.rejectedByProperty(), report.message());
    }

    /** Test du parser : chaque organisme gardé, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX);
        convert(ctx.rawDir(), dataset);
        List<SourceRecord> records = new ArrayList<>();
        for (FtmEntity entity : dataset.entities()) {
            if ("Organization".equals(entity.getSchema())) {
                records.add(new SourceRecord(entity.getId(), "Organization", entity.getValues("name").iterator().next(),
                        String.join(", ", entity.getValues("sourceUrl"))));
            }
        }
        return records;
    }

    /** Bilan de la conversion, pour le message de l'exécution. */
    record Report(int deputies, int declarations, List<String> scanned, List<String> unreadable,
            int deputiesWithLinks, int organizations, int mandates, int holdings, int skipped) {

        String message() {
            StringBuilder m = new StringBuilder(String.format(
                    "%d députés, %d déclarations : %d organismes liés à %d députés (%d mandats, %d participations).",
                    deputies, declarations, organizations, deputiesWithLinks, mandates, holdings));
            if (!scanned.isEmpty()) {
                m.append(String.format(" %d déclarations scannées sans texte, non lues : %s.",
                        scanned.size(), String.join(", ", scanned)));
            }
            if (!unreadable.isEmpty()) {
                m.append(String.format(" %d déclarations sans sections E/G reconnues : %s.",
                        unreadable.size(), String.join(", ", unreadable)));
            }
            if (skipped > 0) {
                m.append(String.format(" %d lignes illisibles (OCR) écartées.", skipped));
            }
            return m.toString();
        }
    }

    /**
     * Lit {@code deputies.json} et les déclarations PDF dans {@code rawDir} et écrit les entités. Séparé de
     * {@link #parse} pour être testé sans réseau.
     */
    Report convert(Path rawDir, FtmDatasetBuilder dataset) throws Exception {
        List<Deputy> deputies = json.readValue(rawDir.resolve(ChdDeclarationDownloader.DEPUTIES_FILE).toFile(),
                new TypeReference<List<Deputy>>() { });
        int declarations = 0;
        int mandates = 0;
        int holdings = 0;
        int withLinks = 0;
        int skipped = 0;
        List<String> scanned = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        Set<String> organizations = new HashSet<>();

        for (Deputy deputy : deputies) {
            if (deputy.file() == null || deputy.name() == null || !Files.exists(rawDir.resolve(deputy.file()))) {
                continue;
            }
            declarations++;
            Declaration declaration = DeclarationReader.read(rawDir.resolve(deputy.file()));
            if (declaration.scanned()) {
                scanned.add(deputy.name());
                continue;
            }
            if (!declaration.sectionsFound()) {
                unreadable.add(deputy.name());
                continue;
            }

            FtmEntity person = person(dataset, deputy);
            int links = 0;
            for (String mandate : declaration.mandates()) {
                Entry entry = Entry.of(mandate);
                if (entry == null) {
                    skipped++;
                    continue;
                }
                FtmEntity org = organization(dataset, entry, deputy, "section E");
                FtmEntity link = dataset.newEntity("Directorship", deputy.id(), "director", org.getId());
                link.addValue("director", person.getId());
                link.addValue("organization", org.getId());
                link.addValue("role", MANDATE_ROLE);
                link.addValue("description", mandate);
                link.addValue("sourceUrl", deputy.declarationUrl());
                dataset.addEntity(link);
                organizations.add(org.getId());
                links++;
                mandates++;
            }
            for (Holding holding : declaration.holdings()) {
                Entry entry = Entry.of(holding.text());
                if (entry == null) {
                    skipped++;
                    continue;
                }
                FtmEntity org = organization(dataset, entry, deputy, "section G");
                FtmEntity link = dataset.newEntity("Ownership", deputy.id(), "owner", org.getId());
                link.addValue("owner", person.getId());
                link.addValue("asset", org.getId());
                link.addValue("ownershipType", "Participation directe ou indirecte");
                link.addValue("summary", holding.kind() == HoldingKind.PUBLIC_POLICY
                        ? "Participation avec des répercussions possibles sur la politique publique"
                        : "Participation conférant une influence significative");
                if (entry.percentage() != null) {
                    link.addValue("percentage", entry.percentage());
                }
                link.addValue("description", holding.text());
                link.addValue("sourceUrl", deputy.declarationUrl());
                dataset.addEntity(link);
                organizations.add(org.getId());
                links++;
                holdings++;
            }
            // Le député n'est écrit que s'il a au moins un lien : la liste des députés est publiée par lu_pep_chamber
            if (links > 0) {
                dataset.addEntity(person);
                withLinks++;
            }
        }

        Report report = new Report(deputies.size(), declarations, scanned, unreadable, withLinks,
                organizations.size(), mandates, holdings, skipped);
        log.info(report.message());
        return report;
    }

    private static FtmEntity person(FtmDatasetBuilder dataset, Deputy deputy) {
        FtmEntity person = dataset.newEntity("Person", "deputy", deputy.id());
        person.addValue("name", deputy.name());
        person.addValue("topics", TOPIC_PEP);
        person.addValue("position", POSITION);
        person.addValue("country", "lu");
        person.addValue("sourceUrl", deputy.pageUrl());
        return person;
    }

    /** Organisme, identifié par son nom : le même organisme déclaré par deux députés n'est écrit qu'une fois. */
    private static FtmEntity organization(FtmDatasetBuilder dataset, Entry entry, Deputy deputy, String section) {
        FtmEntity org = dataset.newEntity("Organization", "org", entry.name());
        org.addValue("name", entry.name());
        org.addValue("topics", TOPIC_RCA);
        org.addValue("summary", "Déclaré par " + deputy.name() + ", député (déclaration d'intérêts financiers, "
                + section + ")");
        org.addValue("sourceUrl", deputy.declarationUrl());
        dataset.addEntity(org);
        return org;
    }

    /**
     * Ligne d'une déclaration découpée en nom de l'organisme et pourcentage détenu. Les précisions entre
     * parenthèses (« (AccountTech détient 78%) », « (CLOTURE 02/24) ») sont retirées du nom ; un pourcentage seul
     * (« (50%) ») est le pourcentage détenu par le député. Une participation indirecte n'a donc pas de pourcentage.
     */
    record Entry(String name, String percentage) {

        static Entry of(String text) {
            String percentage = null;
            StringBuilder rest = new StringBuilder();
            Matcher paren = PARENTHESES.matcher(text);
            int last = 0;
            while (paren.find()) {
                rest.append(text, last, paren.start()).append(' ');
                Matcher pct = PERCENT.matcher(paren.group(1).trim());
                if (pct.matches() && percentage == null) {
                    percentage = pct.group(1).replace(',', '.');
                }
                last = paren.end();
            }
            rest.append(text.substring(last));
            String name = rest.toString();
            Matcher pct = PERCENT.matcher(name);
            if (pct.find()) {
                if (percentage == null) {
                    percentage = pct.group(1).replace(',', '.');
                }
                name = pct.replaceAll(" ");
            }
            name = name.replaceAll("\\s+", " ").trim();
            // La fonction du député est souvent écrite avec l'organisme : elle reste dans la description du lien
            Matcher colon = ROLE_AFTER_COLON.matcher(name);
            if (colon.matches()) {
                name = colon.group(1);
            }
            name = ROLE_SUFFIX.matcher(name).replaceFirst("");
            name = ROLE_PREFIX.matcher(name).replaceFirst("");
            name = SHARES.matcher(name).replaceFirst("");
            name = name.replaceAll("[\\s,;:–-]+$", "").replaceAll("^[\\s,;:–-]+", "").trim();
            // Moins de deux lettres : case vide ; caractères impossibles : écriture manuscrite mal reconnue par l'OCR
            if (name.replaceAll("[^\\p{L}]", "").length() < 2 || GARBLED.matcher(name).find()) {
                return null;
            }
            return new Entry(name, percentage);
        }
    }

    private static final String ROLE = "(?:membre|member|vice-présidente?|présidente?|secrétaire(?: générale?)?"
            + "|trésorière|trésorier|administrat(?:eur|rice)|gérante?)";
    private static final String ARTICLE = "(?:(?:du|de la|de l[’']|des|de|d[’'])\\s*)?";
    /** « ADR: Membre du bureau exécutif » → « ADR ». */
    private static final Pattern ROLE_AFTER_COLON = Pattern.compile("(?iu)^([^:]{2,}?)\\s*:\\s*" + ROLE + "\\b.*$");
    /** « Amis de l'Orgue Diekirch, membre du conseil d'administration » → « Amis de l'Orgue Diekirch ». */
    private static final Pattern ROLE_SUFFIX = Pattern.compile("(?iu)\\s*,\\s*" + ROLE + "\\b.*$");
    /** « Membre du comité directeur du Parti démocratique » → « Parti démocratique » ; « CA Fondation X » → « Fondation X ». */
    private static final Pattern ROLE_PREFIX = Pattern.compile("(?iu)^(?:" + ROLE + "\\s+" + ARTICLE
            + "(?:(?:comité(?: directeur| exécutif| régional(?: \\p{Lu}\\p{Ll}+)?)?|conseil d[’']administration|CA|bureau(?: exécutif)?)\\s+)?"
            + ARTICLE + "|CA\\s+)");
    /** Détail des parts après le nom : « , 4 parts », « 1 Part sociale à valeur nominale de 25 € ». */
    private static final Pattern SHARES = Pattern.compile("(?iu)[\\s,]+(?:\\d+\\s+parts?\\b|valeur nominale\\b).*$");
    /** Signes d'une reconnaissance de caractères ratée : symboles absents d'un nom, « fÇ ». */
    private static final Pattern GARBLED = Pattern.compile("[\\^|{}<>~\\\\]|(?:^|\\s)\\p{Ll}\\p{Lu}(?:\\s|$)");

    private static long delay(Map<String, String> parameters) {
        try {
            return Long.parseLong(parameters.getOrDefault("delayMs", "").trim());
        } catch (NumberFormatException e) {
            return DEFAULT_DELAY_MS;
        }
    }
}
