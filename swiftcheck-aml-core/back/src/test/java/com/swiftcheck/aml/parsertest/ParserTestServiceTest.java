package com.swiftcheck.aml.parsertest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.swiftcheck.aml.entities.ApplicationConfigEntity;
import com.swiftcheck.aml.entities.DataSourceType;
import com.swiftcheck.aml.entities.Parser;
import com.swiftcheck.aml.entities.ParserExecution;
import com.swiftcheck.aml.entities.ParserExecutionStatus;
import com.swiftcheck.aml.entities.ParserTest;
import com.swiftcheck.aml.entities.ParserTestFailure;
import com.swiftcheck.aml.entities.ParserTestStatus;
import com.swiftcheck.aml.entities.SourceFile;
import com.swiftcheck.aml.entities.TestFailureReason;
import com.swiftcheck.aml.ingestion.DatasetPublisher;
import com.swiftcheck.aml.plugin.Iparser;
import com.swiftcheck.aml.plugin.ParserContext;
import com.swiftcheck.aml.plugin.ParserResult;
import com.swiftcheck.aml.plugin.SourceRecord;
import com.swiftcheck.aml.repositories.ParserExecutionRepository;
import com.swiftcheck.aml.repositories.ParserRepository;
import com.swiftcheck.aml.repositories.ParserTestFailureRepository;
import com.swiftcheck.aml.repositories.ParserTestRepository;
import com.swiftcheck.aml.repositories.SourceFileRepository;
import com.swiftcheck.aml.service.ApplicationConfigService;
import com.swiftcheck.aml.service.PluginManagementService;
import com.swiftcheck.aml.service.YenteManifestService;

/**
 * Test du test de parser, avec un faux Yente (serveur HTTP local) :
 * <ul>
 *   <li>t-1 : indexé et retrouvé par son nom → trouvé ;</li>
 *   <li>t-2 : indexé, mais la recherche par nom renvoie une autre entité → NOT_MATCHED ;</li>
 *   <li>t-3 : dans le fichier publié mais inconnu de Yente → NOT_INDEXED ;</li>
 *   <li>t-4 : absent du fichier publié → NOT_CONVERTED ;</li>
 *   <li>t-5 : sans nom dans la source, indexé → trouvé par son ID.</li>
 * </ul>
 */
class ParserTestServiceTest {

    @TempDir
    Path tmp;

    private HttpServer yente;
    private final List<ParserTestFailure> savedFailures = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    /** Ce que le faux Yente connaît : ID → nom indexé. */
    private static final Map<String, String> INDEXED = Map.of(
            "t-1", "Ivan PETROV", "t-2", "Autre nom indexé", "t-5", "Sans nom", "z-9", "Ivan PETROVA");

    private ParserTestService service;
    private ParserTestRepository tests;

    @BeforeEach
    void setUp() throws IOException {
        yente = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        yente.createContext("/match/test_list", this::match);
        yente.createContext("/entities/", exchange -> {
            String id = exchange.getRequestURI().getPath().substring("/entities/".length());
            reply(exchange, INDEXED.containsKey(id) ? 200 : 404, "{}");
        });
        yente.start();

        Parser parser = new Parser();
        parser.setId(7);
        parser.setName("Liste test");
        parser.setDatasetName("test_list");
        parser.setPluginId("test-parser");
        parser.setDataSourceType(DataSourceType.LOCAL_FILE);

        ApplicationConfigEntity config = new ApplicationConfigEntity();
        config.setYenteUrl("http://127.0.0.1:" + yente.getAddress().getPort());
        config.setYenteDataFolder(tmp.resolve("yente").toString());
        config.setWorkFolder(tmp.resolve("work").toString());

        // Fichier publié : t-4 n'y est pas (perdu par la conversion)
        Path published = tmp.resolve("yente/test_list/test_list.ndjson");
        Files.createDirectories(published.getParent());
        Files.write(published, List.of(
                "{\"id\":\"t-1\",\"schema\":\"Person\",\"properties\":{\"name\":[\"Ivan PETROV\"]}}",
                "{\"id\":\"t-2\",\"schema\":\"Person\",\"properties\":{\"name\":[\"Jean DUPONT\"]}}",
                "{\"id\":\"t-3\",\"schema\":\"Organization\",\"properties\":{\"name\":[\"ACME\"]}}",
                "{\"id\":\"t-5\",\"schema\":\"Vessel\",\"properties\":{}}"));

        Path archive = Files.writeString(tmp.resolve("source.xml"), "<liste/>");
        SourceFile source = new SourceFile();
        source.setArchivePath(archive.toString());
        source.setOrigin("D:/sources/liste.xml");

        ParserExecution publication = new ParserExecution();
        publication.setId(42);
        publication.setStatus(ParserExecutionStatus.SUCCESS);

        // Faux plugin : relit la « source » (vérifie qu'on lui donne bien le fichier archivé)
        Iparser plugin = new Iparser() {
            @Override
            public ParserResult parse(ParserContext ctx) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<SourceRecord> readSourceRecords(ParserContext ctx) throws IOException {
                assertEquals("<liste/>", Files.readString(ctx.inputFile()));
                return List.of(
                        new SourceRecord("t-1", "Person", "Ivan PETROV", "fiche 1"),
                        new SourceRecord("t-2", "Person", "Jean DUPONT", "fiche 2"),
                        new SourceRecord("t-3", "Organization", "ACME", "fiche 3"),
                        new SourceRecord("t-4", "Person", "Perdu PAR-LA-CONVERSION", "fiche 4"),
                        new SourceRecord("t-5", "Vessel", null, "fiche 5"),
                        new SourceRecord("t-1", "Person", "Ivan PETROV", "fiche 1 en double"));
            }
        };

        ParserRepository parsers = mock(ParserRepository.class);
        when(parsers.findById(7L)).thenReturn(Optional.of(parser));
        ParserExecutionRepository executions = mock(ParserExecutionRepository.class);
        when(executions.findFirstByParserIdAndStatusOrderByStartedAtDesc(7, ParserExecutionStatus.SUCCESS))
                .thenReturn(Optional.of(publication));
        SourceFileRepository sources = mock(SourceFileRepository.class);
        when(sources.findByExecutionIdOrderByIdAsc(42)).thenReturn(List.of(source));
        tests = mock(ParserTestRepository.class);
        when(tests.save(any())).thenAnswer(call -> {
            ParserTest test = call.getArgument(0);
            test.setId(1);
            return test;
        });
        ParserTestFailureRepository failures = mock(ParserTestFailureRepository.class);
        when(failures.saveAll(any())).thenAnswer(call -> {
            call.<Iterable<ParserTestFailure>>getArgument(0).forEach(savedFailures::add);
            return null;
        });
        PluginManagementService plugins = mock(PluginManagementService.class);
        when(plugins.findParser(any(Parser.class))).thenReturn(Optional.of(plugin));
        ApplicationConfigService configService = mock(ApplicationConfigService.class);
        when(configService.current()).thenReturn(config);

        service = new ParserTestService(parsers, executions, sources, tests, failures, plugins, configService,
                new DatasetPublisher(new YenteManifestService()), new YenteLookup(), 99, 2, 2);
    }

    @AfterEach
    void stopYente() {
        yente.stop(0);
    }

    @Test
    void chaqueElementDeLaSourceEstClasse() {
        ParserTest test = service.run(7);

        assertEquals(ParserTestStatus.FAILED, test.getStatus(), test.getMessage());
        assertEquals(42L, test.getExecutionId());
        assertEquals(5, test.getSourceCount(), "le doublon t-1 ne compte qu'une fois");
        assertEquals(2, test.getFoundCount());          // t-1 par le nom, t-5 par l'ID
        assertEquals(1, test.getNotConvertedCount());   // t-4
        assertEquals(1, test.getNotIndexedCount());     // t-3
        assertEquals(1, test.getNotMatchedCount());     // t-2
        assertEquals(0, test.getErrorCount());

        Map<String, TestFailureReason> reasons = savedFailures.stream()
                .collect(Collectors.toMap(ParserTestFailure::getEntityId, ParserTestFailure::getReason));
        assertEquals(Map.of("t-2", TestFailureReason.NOT_MATCHED, "t-3", TestFailureReason.NOT_INDEXED,
                "t-4", TestFailureReason.NOT_CONVERTED), reasons);
        ParserTestFailure notMatched = savedFailures.stream().filter(f -> f.getEntityId().equals("t-2")).findFirst().get();
        assertTrue(notMatched.getDetail().contains("t-2") || notMatched.getDetail().contains("aucun"), notMatched.getDetail());
        assertTrue(test.getMessage().startsWith("5 éléments dans la source : 2 retrouvés"), test.getMessage());
        assertTrue(Files.notExists(tmp.resolve("work/test-1")), "dossier de travail supprimé");
    }

    @Test
    void yenteInjoignableDonneDesErreursEtNonDesAbsences() {
        yente.stop(0);
        ParserTest test = service.run(7);
        assertEquals(ParserTestStatus.FAILED, test.getStatus());
        assertEquals(4, test.getErrorCount(), test.getMessage());   // tous sauf t-4 (non converti)
        assertEquals(0, test.getNotIndexedCount());
    }

    @Test
    void refuseSansPublication() {
        ParserExecutionRepository none = mock(ParserExecutionRepository.class);
        when(none.findFirstByParserIdAndStatusOrderByStartedAtDesc(anyLong(), any())).thenReturn(Optional.empty());
        // Un parser jamais publié : rien à tester
        ParserTestService withoutPublication = new ParserTestService(parserRepository(), none,
                mock(SourceFileRepository.class), tests, mock(ParserTestFailureRepository.class),
                mock(PluginManagementService.class), mock(ApplicationConfigService.class),
                new DatasetPublisher(new YenteManifestService()), new YenteLookup(), 99, 25, 4);
        var error = assertThrows(IllegalStateException.class, () -> withoutPublication.run(7));
        assertTrue(error.getMessage().contains("Aucune publication"));
    }

    private static ParserRepository parserRepository() {
        Parser parser = new Parser();
        parser.setId(7);
        ParserRepository parsers = mock(ParserRepository.class);
        when(parsers.findById(7L)).thenReturn(Optional.of(parser));
        return parsers;
    }

    /** Faux /match : renvoie, pour chaque nom, les entités indexées dont le nom commence pareil. */
    private void match(HttpExchange exchange) throws IOException {
        JsonNode queries = json.readTree(exchange.getRequestBody()).path("queries");
        StringBuilder responses = new StringBuilder("{\"responses\":{");
        var names = queries.fieldNames();
        boolean first = true;
        while (names.hasNext()) {
            String key = names.next();
            String name = queries.path(key).path("properties").path("name").get(0).asText();
            String prefix = name.substring(0, Math.min(4, name.length()));
            Set<String> ids = INDEXED.entrySet().stream().filter(e -> e.getValue().startsWith(prefix))
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            String results = ids.stream()
                    .map(id -> "{\"id\":\"" + id + "\",\"caption\":\"" + INDEXED.get(id) + "\",\"score\":0.9}")
                    .collect(Collectors.joining(","));
            responses.append(first ? "" : ",").append('"').append(key).append("\":{\"results\":[").append(results).append("]}");
            first = false;
        }
        reply(exchange, 200, responses.append("}}").toString());
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
