import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

public class ReplaceUserAgentScript {
    public static void main(String[] args) throws Exception {
        java.nio.file.Path path = Paths.get("D:/projects/swiftcheck-aml/swiftcheck-aml-core/back/src/main/java/com/swiftcheck/aml/service/SourceDownloadService.java");
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

        String genericUserAgent = "\"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36\"";
        
        // This is safe: replacing the literal with a conditional expression relying on `sourceUrl`.
        // Wait, does `sourceUrl` exist in all contexts?
        // In executeDownloadWithFallbacks:
        // .header("User-Agent", "Mozilla/5.0...")
        // In downloadViaHttpUrlConnection:
        // conn.setRequestProperty("User-Agent", "Mozilla/5.0...");
        // But what about the other places (e.g. WorldBank optimisation, fetchSitemaps, isPageAvailable)?
        // In fetchSitemaps it's `sitemapUrl` instead of `sourceUrl`.
        // In isPageAvailable it's `warningUrl` instead of `sourceUrl`.
        // So we can't just globally replace it with `sourceUrl.contains`.
        
        // Let's explicitly replace it for the two main methods by using a regex.
        String newContent = content.replace(
            ".header(\"User-Agent\",\n                                \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36\")",
            ".header(\"User-Agent\", sourceUrl.contains(\"wikidata.org\") ? \"SwiftCheckAML/1.0 (https://swiftcheck.com; contact@swiftcheck.com)\" : \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36\")"
        );
        newContent = newContent.replace(
            "conn.setRequestProperty(\"User-Agent\",\n                \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36\");",
            "conn.setRequestProperty(\"User-Agent\", sourceUrl.contains(\"wikidata.org\") ? \"SwiftCheckAML/1.0 (https://swiftcheck.com; contact@swiftcheck.com)\" : \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36\");"
        );
        
        Files.write(path, newContent.getBytes(StandardCharsets.UTF_8));
        System.out.println("REPLACED SUCCESSFULLY!");
    }
}
