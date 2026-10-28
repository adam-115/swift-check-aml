package com.swiftcheck.aml.plugins.frpep.constitutionalcouncil;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.plugins.frpep.constitutionalcouncil.CouncilPages.Member;
import com.swiftcheck.aml.plugins.frpep.constitutionalcouncil.CouncilPages.Profile;
import com.swiftcheck.aml.sdk.FtmDatasetBuilder;
import com.swiftcheck.aml.sdk.FtmEntity;

/**
 * <b>fr_pep_constitutional_council</b> – président et membres du Conseil constitutionnel (catégorie FR-d ; CMF,
 * art. R. 561-18 ; directive (UE) 2015/849, art. 3, point 9 d)).
 *
 * <p>Source : conseil-constitutionnel.fr, page « Les membres » puis la fiche de chaque membre pour sa date de
 * prestation de serment et sa naissance (voir {@link CouncilPages}). Type {@code PLUGIN_MANAGED} : le plugin
 * télécharge les pages et les dépose dans {@code rawDir}. Paramètres :
 * <pre>{@code
 * {"membersUrl": "https://www.conseil-constitutionnel.fr/le-conseil-constitutionnel/les-membres", "delayMs": "500"}
 * }</pre>
 *
 * <p>Le secrétaire général n'est pas nommé sur le site : il n'est pas couvert ici (décret au Journal officiel).
 * Un membre qui quitte le Conseil disparaît de la page ; le core le garde publié pendant la rétention (12 mois).
 */
@Extension
public class FrPepConstitutionalCouncilParser implements Iparser {

    private static final Logger log = LoggerFactory.getLogger(FrPepConstitutionalCouncilParser.class);

    /** Préfixe des identifiants FtM : ne jamais le changer, les alertes y sont rattachées. */
    static final String ID_PREFIX = "fr-pep-constitutional-council";

    /** Catégorie de risque ajoutée à chaque personne. */
    static final String TOPIC = "role.pep";

    static final String MEMBERS_FILE = "les-membres.html";
    static final long DEFAULT_DELAY_MS = 500;

    private static final String MEMBER = "Membre du Conseil constitutionnel";
    private static final String PRESIDENT = "Président du Conseil constitutionnel";

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        Map<String, String> p = ctx.parameters() == null ? Map.of() : ctx.parameters();
        String url = p.get("membersUrl");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Paramètre « membersUrl » manquant dans la configuration du parser, ex. "
                    + "{\"membersUrl\": \"https://www.conseil-constitutionnel.fr/le-conseil-constitutionnel/les-membres\"}.");
        }
        List<String> failed = download(CouncilPages.httpFetcher(), url.trim(), delay(p), ctx.rawDir());

        FtmDatasetBuilder dataset = new FtmDatasetBuilder(ID_PREFIX, TOPIC);
        int members = convert(ctx.rawDir(), url.trim(), dataset);
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult().withMessage(members + " membres du Conseil constitutionnel."
                + (failed.isEmpty() ? "" : " Fiches non lues : " + String.join(", ", failed) + "."));
    }

    /** Test du parser : chaque membre, avec l'ID et le nom que Yente doit connaître (sans réseau). */
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder ids = new FtmDatasetBuilder(ID_PREFIX);   // uniquement pour calculer les ID
        List<SourceRecord> records = new ArrayList<>();
        for (Member m : CouncilPages.members(Files.readString(ctx.rawDir().resolve(MEMBERS_FILE)))) {
            records.add(new SourceRecord(ids.buildEntityId(m.slug()), "Person", m.name(), "/membres/" + m.slug()));
        }
        return records;
    }

    /** Télécharge la page des membres puis chaque fiche. @return les fiches non lues */
    static List<String> download(CouncilPages.Fetcher fetcher, String membersUrl, long delayMillis, Path rawDir)
            throws IOException, InterruptedException {
        Files.createDirectories(rawDir);
        String page = fetcher.get(membersUrl);
        List<Member> members = CouncilPages.members(page);
        if (members.isEmpty()) {
            throw new IOException("Aucun membre trouvé sur " + membersUrl + " : la page a peut-être changé.");
        }
        Files.writeString(rawDir.resolve(MEMBERS_FILE), page);
        URI base = URI.create(membersUrl);
        List<String> failed = new ArrayList<>();
        for (Member m : members) {
            Thread.sleep(delayMillis);
            String url = base.resolve("/membres/" + m.slug()).toString();
            try {
                Files.writeString(rawDir.resolve(profileFile(m.slug())), fetcher.get(url));
            } catch (IOException e) {
                log.warn("Fiche non lue : {} ({})", url, e.getMessage());
                failed.add(url);
            }
        }
        return failed;
    }

    static String profileFile(String slug) {
        return "membre-" + slug + ".html";
    }

    /** Écrit les membres à partir des pages de {@code rawDir}. @return le nombre de membres */
    int convert(Path rawDir, String membersUrl, FtmDatasetBuilder dataset) throws Exception {
        Path page = rawDir.resolve(MEMBERS_FILE);
        if (!Files.exists(page)) {
            throw new IllegalStateException(MEMBERS_FILE + " absent de " + rawDir + " : le téléchargement n'a pas eu lieu.");
        }
        List<Member> members = CouncilPages.members(Files.readString(page));
        if (members.isEmpty()) {
            throw new IllegalStateException("Aucun membre dans " + MEMBERS_FILE + " : la page a peut-être changé.");
        }
        URI base = URI.create(membersUrl);
        for (Member m : members) {
            Path file = rawDir.resolve(profileFile(m.slug()));
            Profile profile = Files.exists(file) ? CouncilPages.profile(Files.readString(file)) : new Profile(null, null, null);
            String pageUrl = base.resolve("/membres/" + m.slug()).toString();
            String[] name = CouncilPages.splitName(m.name());

            // ID = adresse de la fiche du membre (richard-ferrand)
            FtmEntity person = dataset.newEntity("Person", m.slug());
            person.addValue("name", m.name());
            person.addValue("firstName", name[0]);
            person.addValue("lastName", name[1]);
            person.addValue("birthDate", profile.birthDate());
            person.addValue("birthPlace", profile.birthPlace());
            person.addValue("country", "fr");
            person.addValue("nationality", "fr");
            person.addValue("position", MEMBER);
            person.addValue("sourceUrl", pageUrl);

            // Mandat de neuf ans à compter de la prestation de serment ; à défaut, date de nomination
            occupancy(dataset, m, person, position(dataset, "member", MEMBER), "member", profile, pageUrl);
            if (m.president()) {
                person.addValue("position", PRESIDENT);
                occupancy(dataset, m, person, position(dataset, "president", PRESIDENT), "president", profile, pageUrl);
            }
            dataset.addEntity(person);
        }
        return members.size();
    }

    private static FtmEntity position(FtmDatasetBuilder dataset, String key, String name) {
        FtmEntity position = dataset.newEntity("Position", key);
        position.addValue("name", name);
        position.addValue("country", "fr");
        position.addValue("organization", "Conseil constitutionnel");
        position.addValue("topics", "gov.national");
        position.addValue("topics", "gov.judicial");
        dataset.addEntity(position);
        return position;
    }

    private static void occupancy(FtmDatasetBuilder dataset, Member m, FtmEntity person, FtmEntity position, String key,
            Profile profile, String pageUrl) {
        FtmEntity occupancy = dataset.newEntity("Occupancy", m.slug(), key);
        occupancy.addValue("holder", person.getId());
        occupancy.addValue("post", position.getId());
        occupancy.addValue("startDate", profile.oathDate() != null ? profile.oathDate() : m.nominationDate());
        occupancy.addValue("status", "current");
        occupancy.addValue("description", m.nominatedBy() == null ? null
                : "Nommé(e) le " + m.nominationDate() + " par " + m.nominatedBy());
        occupancy.addValue("sourceUrl", pageUrl);
        dataset.addEntity(occupancy);
    }

    private static long delay(Map<String, String> p) {
        try {
            return Long.parseLong(p.getOrDefault("delayMs", String.valueOf(DEFAULT_DELAY_MS)).trim());
        } catch (NumberFormatException e) {
            return DEFAULT_DELAY_MS;
        }
    }
}
