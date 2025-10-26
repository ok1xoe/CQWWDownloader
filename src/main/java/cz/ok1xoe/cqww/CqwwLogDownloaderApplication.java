package cz.ok1xoe.cqww;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


@SpringBootApplication
@Slf4j
public class CqwwLogDownloaderApplication implements ApplicationRunner {

    private static final String DEFAULT_URL = "https://cqww.com/publiclogs/2024ph/";

    public static void main(String[] args) {
        SpringApplication.run(CqwwLogDownloaderApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        final String url = args.containsOption("url") ? args.getOptionValues("url").get(0) : DEFAULT_URL;
        Path outDir = args.containsOption("out")
                ? Path.of(args.getOptionValues("out").get(0))
                : Path.of(System.getProperty("user.dir"));
        final int maxConcurrent = args.containsOption("maxConcurrent")
                ? Math.max(1, parseIntSafe(args.getOptionValues("maxConcurrent").get(0), 100))
                : 100;

        final int maxRetries = args.containsOption("retries")
                ? Math.max(0, parseIntSafe(args.getOptionValues("retries").get(0), 3))
                : 3;

        // Nový parametr pro řešení existujících souborů
        final String overwriteMode = args.containsOption("overwrite")
                ? args.getOptionValues("overwrite").get(0).toLowerCase()
                : "replace";

        if (!overwriteMode.equals("skip") && !overwriteMode.equals("new") && !overwriteMode.equals("replace")) {
            log.error("Neplatná hodnota --overwrite: {}. Povolené hodnoty: skip, new, replace", overwriteMode);
            return;
        }

        log.info("Start | url={} out={} maxConcurrent={} retries={} overwrite={}",
                url, outDir.toAbsolutePath(), maxConcurrent, maxRetries, overwriteMode);

        // ... existing code ...
        try {
            URI test = new URI(url);
            if (test.getScheme() == null || (!"http".equalsIgnoreCase(test.getScheme()) && !"https".equalsIgnoreCase(test.getScheme()))) {
                log.error("Neplatné schéma URL: {}", url);
                return;
            }
        } catch (URISyntaxException e) {
            log.error("Neplatná URL: {}", url, e);
            return;
        }

        // ... existing code ...
        try {
            if (!Files.exists(outDir)) Files.createDirectories(outDir);
            if (!Files.isDirectory(outDir)) {
                log.error("Cílová cesta není adresář: {}", outDir);
                return;
            }
            if (!Files.isWritable(outDir)) {
                log.error("Do cílového adresáře nelze zapisovat: {}", outDir);
                return;
            }
        } catch (AccessDeniedException e) {
            log.error("Přístup odepřen: {}", outDir, e);
            return;
        } catch (IOException e) {
            log.error("Nelze připravit cílový adresář: {}", outDir, e);
            return;
        } catch (SecurityException e) {
            log.error("Bezpečnostní omezení při práci s adresářem: {}", outDir, e);
            return;
        }

        // ... existing code ...
        final Document doc;
        try {
            doc = Jsoup.connect(url)
                    .userAgent("CQWW-Log-Downloader/1.3 (+Java 21 Virtual Threads; SpringBoot)")
                    .timeout(20_000)
                    .get();
        } catch (UnknownHostException e) {
            log.error("DNS chyba (UnknownHost): {}", e.getMessage(), e);
            return;
        } catch (HttpTimeoutException e) {
            log.error("Timeout při načítání seznamu: {}", e.getMessage(), e);
            return;
        } catch (IOException e) {
            log.error("IO chyba při načítání seznamu: {}", e.getMessage(), e);
            return;
        } catch (IllegalArgumentException e) {
            log.error("Chybná URL nebo parametry pro připojení: {}", url, e);
            return;
        } catch (SecurityException e) {
            log.error("Bezpečnostní omezení při síťové komunikaci: {}", e.getMessage(), e);
            return;
        }

        // ... existing code ...
        Set<URI> uris = new LinkedHashSet<>();
        try {
            Elements links = doc.select("a[href]");
            for (Element a : links) {
                String abs = a.attr("abs:href");
                if (abs == null || abs.isBlank()) continue;
                String lower = abs.toLowerCase();
                if (!lower.endsWith(".log")) continue;
                try {
                    uris.add(new URI(abs));
                } catch (URISyntaxException ex) {
                    log.warn("Přeskočen neplatný odkaz: {}", abs, ex);
                }
            }
        } catch (Exception e) {
            log.error("Chyba při parsování HTML.", e);
            return;
        }

        if (uris.isEmpty()) {
            log.warn("Na stránce nejsou žádné .log soubory. url={}", url);
            return;
        }

        log.info("Nalezeno .log souborů: {}", uris.size());

        // Virtual Threads executor
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            final Semaphore gate = new Semaphore(maxConcurrent);

            AtomicInteger ok = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            AtomicInteger skipped = new AtomicInteger(0);
            AtomicLong totalBytes = new AtomicLong(0);

            // Odeslat všechny úlohy s novým parametrem overwriteMode
            var futures = uris.stream().map(uri -> executor.submit(() -> {
                gate.acquireUninterruptibly();
                try {
                    new DownloadTask(uri, outDir, maxRetries, overwriteMode, totalBytes, ok, failed, skipped).call();
                } finally {
                    gate.release();
                }
                return null;
            })).toList();

            // ... existing code ...
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Přerušeno při čekání na úlohu.", e);
                } catch (ExecutionException e) {
                    log.debug("Výjimka z úlohy (už zalogována).", e.getCause());
                }
            }

            log.info("HOTOVO | úspěšně: {} přeskočeno: {} neúspěšně: {} celkem: {}B",
                    ok.get(), skipped.get(), failed.get(), totalBytes.get());
        } catch (Exception e) {
            log.error("Neočekávaná chyba exekutoru.", e);
        }
    }

    private int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            log.warn("Nelze parsovat číslo '{}', použiji {}.", s, def);
            return def;
        }
    }

    @Slf4j
    static class DownloadTask implements Callable<Void> {
        private final URI uri;
        private final Path outDir;
        private final int maxRetries;
        private final String overwriteMode;
        private final AtomicLong totalBytes;
        private final AtomicInteger ok, failed, skipped;

        private static final boolean COLOR = System.console() != null;
        private static final String GREEN = COLOR ? "\u001B[32m" : "";
        private static final String RED = COLOR ? "\u001B[31m" : "";
        private static final String YELLOW = COLOR ? "\u001B[33m" : "";
        private static final String RESET = COLOR ? "\u001B[0m" : "";

        DownloadTask(URI uri, Path outDir, int maxRetries, String overwriteMode,
                     AtomicLong totalBytes, AtomicInteger ok, AtomicInteger failed, AtomicInteger skipped) {
            this.uri = uri;
            this.outDir = outDir;
            this.maxRetries = maxRetries;
            this.overwriteMode = overwriteMode;
            this.totalBytes = totalBytes;
            this.ok = ok;
            this.failed = failed;
            this.skipped = skipped;
        }

        @Override
        public Void call() {
            String cid = java.util.UUID.randomUUID().toString().substring(0, 8);
            org.slf4j.MDC.put("cid", "[" + cid + "]");
            String fileName = Path.of(uri.getPath()).getFileName().toString();

            try {
                Path targetPath = outDir.resolve(fileName);

                // Kontrola existence souboru a rozhodnutí co dělat
                if (Files.exists(targetPath)) {
                    switch (overwriteMode) {
                        case "skip":
                            skipped.incrementAndGet();
                            log.info("Přeskočen (už existuje): {}", fileName);
                            System.out.printf("%s⏭️  [SKIP]%s %s (už existuje)%n", YELLOW, RESET, fileName);
                            return null;

                        case "new":
                            // Stáhnout s prefixem _new
                            String newFileName = fileName.replaceFirst("(\\.[^.]+)$", "_new$1");
                            targetPath = outDir.resolve(newFileName);
                            log.info("Soubor existuje, stahování jako: {}", newFileName);
                            break;

                        case "replace":
                            // Pokračovat normálně - přepíše se
                            log.info("Soubor existuje, bude přepsán: {}", fileName);
                            break;
                    }
                }

                long bytes = 0L;
                int attempt = 0;
                long waitMs = 500;

                while (true) {
                    attempt++;
                    try {
                        bytes = HttpDownloadUtils.downloadToFile(uri, targetPath, Duration.ofSeconds(60));
                        ok.incrementAndGet();
                        totalBytes.addAndGet(bytes);
                        log.info("Staženo: {} ({} B) -> {}", uri, bytes, targetPath.getFileName());
                        System.out.printf("%s⬇️  [OK]%s %s (%d B)%n", GREEN, RESET, targetPath.getFileName(), bytes);
                        break;
                    } catch (Exception e) {
                        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                        log.warn("Chyba při stahování (pokus {}/{}): {} - {}", attempt, maxRetries + 1, uri, msg);
                        System.out.printf("%s❌ [ERR]%s %s (%s)%n", RED, RESET, fileName, msg);
                        if (attempt > maxRetries) {
                            failed.incrementAndGet();
                            log.error("Vyčerpány pokusy: {}", uri);
                            break;
                        }
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.error("Přerušeno při čekání na další pokus", ie);
                            failed.incrementAndGet();
                            break;
                        }
                        waitMs = Math.min(waitMs * 2, 8000);
                    }
                }
            } finally {
                org.slf4j.MDC.remove("cid");
            }
            return null;
        }
    }
}