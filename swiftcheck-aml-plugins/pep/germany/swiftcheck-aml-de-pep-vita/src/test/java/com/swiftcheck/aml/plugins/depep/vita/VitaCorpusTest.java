package com.swiftcheck.aml.plugins.depep.vita;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Passe le lecteur sur un dossier de CV téléchargés (corpus de la Story 3) et imprime ce qu'il lit :
 * {@code mvn test -Dtest=VitaCorpusTest -DvitaDir=<dossier>}.
 */
class VitaCorpusTest {

    @Test
    @EnabledIfSystemProperty(named = "vitaDir", matches = ".+")
    void readCorpus() throws Exception {
        Path dir = Path.of(System.getProperty("vitaDir"));
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().matches(".*\\.(pdf|png)$")).sorted().toList();
        }
        String dump = System.getProperty("vitaDump", "");
        for (Path f : files) {
            if (!dump.isEmpty() && f.getFileName().toString().matches("(" + dump + ").*") && f.toString().endsWith(".pdf")) {
                try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.pdmodel.PDDocument.load(
                        VitaDocuments.gunzipIfNeeded(Files.readAllBytes(f)))) {
                    org.apache.pdfbox.text.PDFTextStripper st = new org.apache.pdfbox.text.PDFTextStripper();
                    st.setEndPage(1);
                    int n = 0;
                    for (String l : VitaDocuments.lines(st.getText(d))) {
                        if (n++ < 45) System.out.println("DUMP\t" + f.getFileName().toString().substring(0, 3) + "\t" + l);
                    }
                }
            }
            VitaDocuments.Vita v = VitaDocuments.read(f.getFileName().toString(), VitaDocuments.gunzipIfNeeded(Files.readAllBytes(f)));
            System.out.println(String.join("\t", "VITA", f.getFileName().toString(), v.kind().name(),
                    String.valueOf(v.rank()), String.valueOf(v.title()), String.valueOf(v.name()),
                    String.valueOf(v.function()), String.valueOf(v.birthDate()), "p" + v.page(), v.confidence()));
        }
    }
}
