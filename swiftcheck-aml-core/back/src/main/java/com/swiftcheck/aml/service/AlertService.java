package com.swiftcheck.aml.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.swiftcheck.aml.entities.AlertType;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserAlert;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.repositories.ParserAlertRepository;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;

/**
 * Alertes sur les parsers : une liste qui n'est plus mise à jour doit se voir tout de suite.
 *
 * <ul>
 *   <li><b>FAILED</b> / <b>BLOCKED</b> : ouverte après une exécution en échec ou bloquée ;</li>
 *   <li><b>STALE</b> : aucune publication depuis plus de deux fois la fréquence du parser (vérifié toutes
 *       les heures) ;</li>
 *   <li>toutes les alertes d'un parser sont résolues à sa publication suivante (SUCCESS ou UNCHANGED).</li>
 * </ul>
 * Une seule alerte ouverte par parser et par type : une nouvelle exécution en échec met à jour son message
 * au lieu d'en créer une autre.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** Statuts qui signifient « la liste publiée est à jour ». */
    static final List<ParserExecutionStatus> PUBLISHED = List.of(ParserExecutionStatus.SUCCESS,
            ParserExecutionStatus.UNCHANGED);

    private final ParserAlertRepository alerts;
    private final ParserRepository parsers;
    private final ParserExecutionRepository executions;

    public AlertService(ParserAlertRepository alerts, ParserRepository parsers, ParserExecutionRepository executions) {
        this.alerts = alerts;
        this.parsers = parsers;
        this.executions = executions;
    }

    public List<ParserAlert> findOpen() {
        return alerts.findByResolvedAtIsNullOrderByRaisedAtDesc();
    }

    public Page<ParserAlert> findAll(Pageable pageable) {
        return alerts.findAllByOrderByRaisedAtDesc(pageable);
    }

    public long countOpen() {
        return alerts.countByResolvedAtIsNull();
    }

    /** Résolution à la main, depuis l'écran « Alertes ». */
    public ParserAlert resolve(long id) {
        ParserAlert alert = alerts.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Alerte introuvable : " + id));
        if (alert.getResolvedAt() == null) {
            alert.setResolvedAt(Instant.now());
            alert = alerts.save(alert);
        }
        return alert;
    }

    /** Appelée à la fin de chaque exécution de parser. */
    public void afterExecution(ParserExecution execution) {
        Parser parser = execution.getParser();
        switch (execution.getStatus()) {
            case FAILED -> raise(parser, AlertType.FAILED, execution.getMessage());
            case BLOCKED -> raise(parser, AlertType.BLOCKED, execution.getMessage());
            case SUCCESS, UNCHANGED -> resolveAll(parser.getId());
            default -> { }
        }
    }

    /**
     * Ouvre une alerte STALE pour chaque parser actif qui n'a rien publié depuis plus de deux fois sa fréquence.
     * Les alertes des parsers désactivés sont résolues : ils ne sont plus censés tourner.
     */
    @Scheduled(initialDelayString = "${swiftcheck.alerts.initial-delay:PT5M}",
            fixedDelayString = "${swiftcheck.alerts.check-interval:PT1H}")
    public void checkStaleParsers() {
        Instant now = Instant.now();
        for (Parser parser : parsers.findAll()) {
            if (!parser.isEnabled()) {
                resolveAll(parser.getId());
            } else if (isStale(parser, now)) {
                raise(parser, AlertType.STALE, "Aucune publication depuis plus de " + 2 * parser.getRefreshHours()
                        + " heures (fréquence prévue : " + parser.getRefreshHours() + " h).");
            }
        }
    }

    /** Vrai si la dernière publication (ou la création du parser) date de plus de deux fois sa fréquence. */
    boolean isStale(Parser parser, Instant now) {
        if (parser.getRefreshHours() <= 0) {
            return false;                                     // parser lancé uniquement à la demande
        }
        Instant lastPublication = executions
                .findFirstByParserIdAndStatusInOrderByStartedAtDesc(parser.getId(), PUBLISHED)
                .map(ParserExecution::getStartedAt)
                .orElse(parser.getCreatedAt());
        return lastPublication.plus(Duration.ofHours(2L * parser.getRefreshHours())).isBefore(now);
    }

    private void raise(Parser parser, AlertType type, String message) {
        Optional<ParserAlert> open = alerts.findFirstByParserIdAndTypeAndResolvedAtIsNull(parser.getId(), type);
        ParserAlert alert = open.orElseGet(() -> {
            ParserAlert created = new ParserAlert();
            created.setParser(parser);
            created.setType(type);
            created.setRaisedAt(Instant.now());
            log.warn("Alerte {} sur le parser {} : {}", type, parser.getDatasetName(), message);
            return created;
        });
        alert.setMessage(message);                            // garde le message le plus récent
        alerts.save(alert);
    }

    private void resolveAll(long parserId) {
        List<ParserAlert> open = alerts.findByParserIdAndResolvedAtIsNull(parserId);
        Instant now = Instant.now();
        open.forEach(alert -> alert.setResolvedAt(now));
        alerts.saveAll(open);
    }
}
