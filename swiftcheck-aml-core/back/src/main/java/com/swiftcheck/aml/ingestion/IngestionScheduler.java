package com.swiftcheck.aml.ingestion;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.swiftcheck.aml.entities.ExecutionTriggerEnum;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;

/**
 * Lance automatiquement les parsers selon leur fréquence
 * ({@code Parser.refreshHours}).
 *
 * <p>
 * Toutes les 15 minutes (réglable :
 * {@code swiftcheck.ingestion.check-interval}), il parcourt les parsers
 * actifs et exécute ceux dont la dernière exécution date de plus de
 * {@code refreshHours} heures.
 * Les parsers sont exécutés l'un après l'autre ; une erreur sur l'un n'empêche
 * pas les suivants.
 *
 * <p>
 * Au démarrage de l'application, les exécutions restées RUNNING (arrêt brutal
 * du serveur) passent en FAILED,
 * sinon le parser resterait bloqué « déjà en cours » pour toujours.
 */
@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final ParserRepository parsers;
    private final ParserExecutionRepository executions;
    private final IngestionService ingestion;

    public IngestionScheduler(ParserRepository parsers, ParserExecutionRepository executions,
            IngestionService ingestion) {
        this.parsers = parsers;
        this.executions = executions;
        this.ingestion = ingestion;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void closeInterruptedExecutions() {
        for (ParserExecution execution : executions.findByStatus(ParserExecutionStatus.RUNNING)) {
            execution.setStatus(ParserExecutionStatus.FAILED);
            execution.setMessage("Exécution interrompue par un arrêt du serveur.");
            execution.setFinishedAt(Instant.now());
            executions.save(execution);
        }
    }

    // @Scheduled(initialDelayString = "${swiftcheck.ingestion.initial-delay:PT2M}", fixedDelayString = "${swiftcheck.ingestion.check-interval:PT15M}")
    public void runDueParsers() {
        Instant now = Instant.now();
        for (Parser parser : parsers.findByEnabledTrue()) {
            if (!isDue(parser, now)) {
                continue;
            }
            try {
                ingestion.run(parser.getId(), ExecutionTriggerEnum.SCHEDULED, false);
            } catch (Exception e) {
                log.error("Parser {} non exécuté : {}", parser.getDatasetName(), e.getMessage());
            }
        }
    }

    /**
     * Un parser est dû s'il a une fréquence (refreshHours > 0) et si sa dernière
     * exécution, quel que soit son
     * résultat, date de plus de refreshHours heures. Un parser en échec est donc
     * retenté à la période suivante,
     * et non toutes les 15 minutes.
     */
    boolean isDue(Parser parser, Instant now) {
        if (parser.getRefreshHours() <= 0) {
            return false;
        }
        return executions.findFirstByParserIdOrderByStartedAtDesc(parser.getId())
                .map(last -> !last.getStartedAt().plus(Duration.ofHours(parser.getRefreshHours())).isAfter(now))
                .orElse(true);
    }
}
