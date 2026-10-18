package com.swiftcheck.aml.ingestion;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.RetainedEntity;
import com.swiftcheck.aml.repositories.RetainedEntityRepository;

/**
 * Rétention : une entité qui disparaît de la source reste publiée pendant {@code Parser.retentionMonths} mois.
 *
 * <p>Pourquoi : une personne reste PEP au moins 12 mois après la fin de ses fonctions (directive 2015/849,
 * art. 22). Or les sources « en fonction » (gouvernement, députés actifs) ne donnent pas de date de fin :
 * un ministre qui quitte le gouvernement disparaît simplement de la page. Sans rétention, il ne serait plus
 * trouvé dans Yente dès l'exécution suivante.
 *
 * <p>À chaque exécution, en comparant la version publiée et ce que le plugin vient de produire :
 * <ol>
 *   <li>une entité gardée qui revient dans la source n'est plus gardée (la source fait foi) ;</li>
 *   <li>une entité publiée qui n'est plus dans la source est gardée, avec la date du jour ;</li>
 *   <li>une entité gardée depuis plus de {@code retentionMonths} mois est oubliée ;</li>
 *   <li>les entités encore gardées sont ajoutées à la fin du fichier produit, telles qu'elles étaient publiées.</li>
 * </ol>
 * Les plugins n'ont rien à faire : ils lisent leur source, le core garde la mémoire.
 * Avec {@code retentionMonths = 0} (sanctions), rien n'est gardé : une radiation s'applique tout de suite.
 */
@Service
public class RetentionService {

    /**
     * @param retainedCount entités gardées et ajoutées au fichier à publier
     * @param expiredCount  entités oubliées lors de cette exécution (rétention écoulée)
     */
    public record Result(int retainedCount, int expiredCount) {
    }

    private final RetainedEntityRepository retained;

    public RetentionService(RetainedEntityRepository retained) {
        this.retained = retained;
    }

    /** Entités actuellement gardées pour ce parser, les plus récemment retirées d'abord. */
    public List<RetainedEntity> findRetained(Parser parser) {
        return retained.findByParserIdOrderByRemovedAtDesc(parser.getId());
    }

    /** Date à partir de laquelle l'entité n'est plus publiée : date du retrait + rétention du parser. */
    public static Instant expiresAt(RetainedEntity entity, Parser parser) {
        return entity.getRemovedAt().atZone(ZoneOffset.UTC).plusMonths(parser.getRetentionMonths()).toInstant();
    }

    /**
     * Vrai si au moins une entité gardée a atteint sa date de fin. Dans ce cas l'exécution doit republier
     * même si la source n'a pas changé, sinon l'entité resterait publiée tant que la source ne bouge pas.
     */
    public boolean hasExpired(Parser parser, Instant now) {
        return findRetained(parser).stream().anyMatch(e -> !expiresAt(e, parser).isAfter(now));
    }

    /**
     * Applique la rétention et ajoute les entités gardées à {@code outputFile}.
     *
     * @param published  version actuellement publiée dans Yente (avec les entités déjà gardées), vide si aucune
     * @param produced   entités produites par le plugin à cette exécution
     * @param outputFile fichier produit par le plugin, complété ici avec les entités gardées
     */
    public Result apply(Parser parser, DatasetFile published, DatasetFile produced, Path outputFile, Instant now)
            throws IOException {
        if (parser.getRetentionMonths() <= 0) {
            retained.deleteByParserId(parser.getId());       // rétention désactivée : plus rien n'est gardé
            return new Result(0, 0);
        }

        // Entités déjà gardées, par identifiant FtM
        Map<String, RetainedEntity> kept = new LinkedHashMap<>();
        for (RetainedEntity entity : findRetained(parser)) {
            kept.put(entity.getEntityId(), entity);
        }
        List<RetainedEntity> toDelete = new ArrayList<>();
        List<RetainedEntity> toSave = new ArrayList<>();

        // 1. Revenue dans la source : la version du plugin remplace la version gardée
        for (Iterator<RetainedEntity> it = kept.values().iterator(); it.hasNext(); ) {
            RetainedEntity entity = it.next();
            if (produced.entities().containsKey(entity.getEntityId())) {
                toDelete.add(entity);
                it.remove();
            }
        }

        // 2. Publiée mais absente de la source : gardée à partir d'aujourd'hui
        for (Map.Entry<String, DatasetFile.Entity> entry : published.entities().entrySet()) {
            String id = entry.getKey();
            if (!produced.entities().containsKey(id) && !kept.containsKey(id)) {
                RetainedEntity entity = new RetainedEntity();
                entity.setParserId(parser.getId());
                entity.setEntityId(id);
                entity.setEntitySchema(entry.getValue().schema());
                entity.setCaption(entry.getValue().caption());
                entity.setJson(entry.getValue().json());
                entity.setRemovedAt(now);
                kept.put(id, entity);
                toSave.add(entity);
            }
        }

        // 3. Rétention écoulée : l'entité est oubliée et ne sera plus publiée
        int expired = 0;
        for (Iterator<RetainedEntity> it = kept.values().iterator(); it.hasNext(); ) {
            RetainedEntity entity = it.next();
            if (!expiresAt(entity, parser).isAfter(now)) {
                toDelete.add(entity);
                it.remove();
                expired++;
            }
        }

        // toDelete ne contient que des lignes déjà en base : une entité gardée aujourd'hui n'expire pas aujourd'hui
        retained.deleteAll(toDelete);
        retained.saveAll(toSave);

        // 4. Ajouter les entités gardées à la fin du fichier produit
        appendLines(outputFile, kept.values().stream().map(RetainedEntity::getJson).toList());
        return new Result(kept.size(), expired);
    }

    /** Ajoute des lignes NDJSON à la fin du fichier, en commençant par un retour à la ligne s'il en manque un. */
    private static void appendLines(Path file, List<String> lines) throws IOException {
        if (lines.isEmpty()) {
            return;
        }
        boolean needsNewLine = endsWithoutNewLine(file);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            if (needsNewLine) {
                writer.write('\n');
            }
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
    }

    private static boolean endsWithoutNewLine(Path file) throws IOException {
        if (Files.size(file) == 0) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(raf.length() - 1);
            return raf.read() != '\n';
        }
    }
}
