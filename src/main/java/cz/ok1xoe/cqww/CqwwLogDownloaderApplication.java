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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootApplication
@Slf4j
public class CqwwLogDownloaderApplication implements ApplicationRunner {

    private static final String DEFAULT_URL = "https://cqww.com/publiclogs/2024ph/";
    private static final String INDEX_URL = "https://cqww.com/publiclogs/";
    private static final Pattern YEAR_PATTERN = Pattern.compile("(\\d{4})(ph|cw|rtty)", Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) {
        SpringApplication.run(CqwwLogDownloaderApplication.class, args);
    }

    @Override
    public void run(ApplicationArguments args) {
        // Targeted režimy podle kombinací:
        // A) --call -> stáhne pro danou značku všechny roky a módy (dle indexu)
        // B) --call + --year -> stáhne pro daný rok CW i SSB
        // C) --call + --mode -> stáhne pro daný mód všechny roky
        // D) --call + --year + --mode -> stáhne jen jeden konkrétní log
        // Ostatní kombinace jsou zakázané.
        if (args.containsOption("call") || args.containsOption("year") || args.containsOption("mode")) {
            runTargetedDownload(args);
            return;
        }

        // Původní funkcionalita (beze změn): stahování podle URL / indexu
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

        final String overwriteMode = args.containsOption("overwrite")
                ? args.getOptionValues("overwrite").get(0).toLowerCase(Locale.ROOT)
                : "replace";

        if (!overwriteMode.equals("skip") && !overwriteMode.equals("new") && !overwriteMode.equals("replace")) {
            log.error("Invalid --overwrite value: {}. Allowed values: skip, new, replace", overwriteMode);
            return;
        }

        log.info("Start | url={} out={} maxConcurrent={} retries={} overwrite={}",
                url, outDir.toAbsolutePath(), maxConcurrent, maxRetries, overwriteMode);

        try {
            URI test = new URI(url);
            if (test.getScheme() == null || (!"http".equalsIgnoreCase(test.getScheme()) && !"https".equalsIgnoreCase(test.getScheme()))) {
                log.error("Invalid URL scheme: {}", url);
                return;
            }
        } catch (URISyntaxException e) {
            log.error("Invalid URL: {}", url, e);
            return;
        }

        try {
            if (!Files.exists(outDir)) Files.createDirectories(outDir);
            if (!Files.isDirectory(outDir)) {
                log.error("Target path is not a directory: {}", outDir);
                return;
            }
            if (!Files.isWritable(outDir)) {
                log.error("Target directory is not writable: {}", outDir);
                return;
            }
        } catch (AccessDeniedException e) {
            log.error("Access denied: {}", outDir, e);
            return;
        } catch (IOException e) {
            log.error("Cannot prepare target directory: {}", outDir, e);
            return;
        } catch (SecurityException e) {
            log.error("Security restriction while accessing directory: {}", outDir, e);
            return;
        }

        // Detect if it's an index page or a specific year page
        boolean isIndexPage = url.trim().endsWith("/publiclogs/") || url.trim().equals(INDEX_URL.replaceAll("/$", ""));

        if (isIndexPage) {
            log.info("Index page detected - will process all years");
            processIndexPage(url, outDir, maxConcurrent, maxRetries, overwriteMode);
        } else {
            log.info("Single year page detected - downloading from one source");
            processSingleYearPage(url, outDir, maxConcurrent, maxRetries, overwriteMode);
        }
    }

    private void runTargetedDownload(ApplicationArguments args) {
        boolean hasCall = args.containsOption("call");
        boolean hasYear = args.containsOption("year");
        boolean hasMode = args.containsOption("mode");

        // Povolené kombinace:
        // call
        // call+year
        // call+mode
        // call+year+mode
        boolean allowed = hasCall && (
                (!hasYear && !hasMode) ||
                        (hasYear && !hasMode) ||
                        (!hasYear && hasMode) ||
                        (hasYear && hasMode)
        );

        if (!allowed) {
            log.error("Invalid parameter combination. Allowed:");
            log.error("  --call");
            log.error("  --call + --year");
            log.error("  --call + --mode");
            log.error("  --call + --year + --mode");
            return;
        }

        String call = getSingleOption(args, "call").orElse("").trim();
        String year = getSingleOption(args, "year").orElse("").trim();
        String modeRaw = getSingleOption(args, "mode").orElse("").trim();

        if (call.isBlank()) {
            log.error("Parameter --call is required.");
            return;
        }
        String callNorm = call.toUpperCase(Locale.ROOT);

        if (hasYear && !year.matches("\\d{4}")) {
            log.error("Invalid --year value: {} (expected 4 digits, e.g. 2020)", year);
            return;
        }

        final Mode selectedMode;
        if (hasMode) {
            try {
                selectedMode = Mode.parse(modeRaw);
            } catch (IllegalArgumentException ex) {
                log.error("Invalid --mode value: {} (allowed: CW, SSB, RTTY; PH accepted as alias for SSB)", modeRaw);
                return;
            }
        } else {
            selectedMode = null;
        }

        Path outDir = args.containsOption("out")
                ? Path.of(args.getOptionValues("out").get(0))
                : Path.of(System.getProperty("user.dir"));

        final int maxRetries = args.containsOption("retries")
                ? Math.max(0, parseIntSafe(args.getOptionValues("retries").get(0), 3))
                : 3;

        final String overwriteMode = args.containsOption("overwrite")
                ? args.getOptionValues("overwrite").get(0).toLowerCase(Locale.ROOT)
                : "replace";

        if (!overwriteMode.equals("skip") && !overwriteMode.equals("new") && !overwriteMode.equals("replace")) {
            log.error("Invalid --overwrite value: {}. Allowed values: skip, new, replace", overwriteMode);
            return;
        }

        try {
            if (!Files.exists(outDir)) Files.createDirectories(outDir);
            if (!Files.isDirectory(outDir)) {
                log.error("Target path is not a directory: {}", outDir);
                return;
            }
            if (!Files.isWritable(outDir)) {
                log.error("Target directory is not writable: {}", outDir);
                return;
            }
        } catch (AccessDeniedException e) {
            log.error("Access denied: {}", outDir, e);
            return;
        } catch (IOException e) {
            log.error("Cannot prepare target directory: {}", outDir, e);
            return;
        } catch (SecurityException e) {
            log.error("Security restriction while accessing directory: {}", outDir, e);
            return;
        }

        AtomicLong totalBytes = new AtomicLong(0);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);

        // D) call + year + mode => konkrétní log
        if (hasYear && hasMode) {
            String yearUrl = buildYearUrl(year, selectedMode);
            downloadOne(callNorm, yearUrl, year, selectedMode, outDir, maxRetries, overwriteMode, totalBytes, ok, failed, skipped);

            log.info("DONE | successful: {} skipped: {} failed: {} total: {}B",
                    ok.get(), skipped.get(), failed.get(), totalBytes.get());
            return;
        }

        // B) call + year => CW i SSB pro rok
        if (hasYear) {
            for (Mode m : List.of(Mode.CW, Mode.SSB)) {
                String yearUrl = buildYearUrl(year, m);
                downloadOne(callNorm, yearUrl, year, m, outDir, maxRetries, overwriteMode, totalBytes, ok, failed, skipped);
            }

            log.info("DONE | successful: {} skipped: {} failed: {} total: {}B",
                    ok.get(), skipped.get(), failed.get(), totalBytes.get());
            return;
        }

        // C) call + mode => všechny roky pro mód
        if (hasMode) {
            List<YearInfo> all = discoverYearLinksFromIndex(INDEX_URL);
            List<YearInfo> filtered = all.stream()
                    .filter(i -> i.mode.equalsIgnoreCase(selectedMode.displayName))
                    .sorted(Comparator.comparing(i -> i.year))
                    .toList();

            if (filtered.isEmpty()) {
                log.warn("No years found for mode={}", selectedMode.displayName);
                return;
            }

            for (YearInfo info : filtered) {
                downloadOne(callNorm, ensureTrailingSlash(info.url), info.year, selectedMode, outDir, maxRetries, overwriteMode, totalBytes, ok, failed, skipped);
            }

            log.info("DONE | successful: {} skipped: {} failed: {} total: {}B",
                    ok.get(), skipped.get(), failed.get(), totalBytes.get());
            return;
        }

        // A) call => všechny roky a módy (dle indexu)
        List<YearInfo> all = discoverYearLinksFromIndex(INDEX_URL);
        List<YearInfo> sorted = all.stream()
                .sorted(Comparator.comparing((YearInfo i) -> i.year).thenComparing(i -> i.mode))
                .toList();

        if (sorted.isEmpty()) {
            log.warn("No year categories found on index page: {}", INDEX_URL);
            return;
        }

        for (YearInfo info : sorted) {
            Mode m;
            try {
                m = Mode.parse(info.mode); // "CW"/"SSB"/"RTTY"
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping unsupported mode from index: {}", info.mode);
                continue;
            }
            downloadOne(callNorm, ensureTrailingSlash(info.url), info.year, m, outDir, maxRetries, overwriteMode, totalBytes, ok, failed, skipped);
        }

        log.info("DONE | successful: {} skipped: {} failed: {} total: {}B",
                ok.get(), skipped.get(), failed.get(), totalBytes.get());
    }

    private void downloadOne(
            String callNorm,
            String yearUrl,
            String year,
            Mode mode,
            Path outDir,
            int maxRetries,
            String overwriteMode,
            AtomicLong totalBytes,
            AtomicInteger ok,
            AtomicInteger failed,
            AtomicInteger skipped
    ) {
        log.info("Searching log | call={} url={}", callNorm, yearUrl);

        URI logUri;
        try {
            logUri = findLogUriForCall(yearUrl, callNorm);
        } catch (IOException e) {
            log.error("Cannot load/parse page: {}", yearUrl, e);
            failed.incrementAndGet();
            return;
        }

        if (logUri == null) {
            log.warn("Not found | call={} url={}", callNorm, yearUrl);
            return;
        }

        String forcedFileName = buildForcedFileName(year, mode, callNorm);

        try {
            new DownloadTask(logUri, outDir, maxRetries, overwriteMode, forcedFileName, totalBytes, ok, failed, skipped).call();
        } catch (Exception e) {
            log.error("Unexpected error during download: {}", logUri, e);
            failed.incrementAndGet();
        }
    }

    private String buildForcedFileName(String year, Mode mode, String callNorm) {
        // Formát: RRRR_MODE_CALL.log
        return year + "_" + mode.displayName + "_" + callNorm + ".log";
    }

    private String buildYearUrl(String year, Mode mode) {
        // veřejné URL: {year}cw / {year}ph / (pokud existuje) {year}rtty
        return INDEX_URL + year + mode.urlSuffix + "/";
    }

    private String ensureTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url : (url + "/");
    }

    private Optional<String> getSingleOption(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) return Optional.empty();
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) return Optional.empty();
        return Optional.ofNullable(values.get(0));
    }

    private URI findLogUriForCall(String yearUrl, String callNorm) throws IOException {
        final Document doc = Jsoup.connect(yearUrl)
                .userAgent("CQWW-Log-Downloader/2.1 (+Java 21 Virtual Threads; SpringBoot)")
                .timeout(20_000)
                .get();

        Elements links = doc.select("a[href]");
        Pattern p = Pattern.compile("(?i)^" + Pattern.quote(callNorm) + "([._-].*)?\\.log$");

        URI best = null;
        int bestLen = Integer.MAX_VALUE;

        for (Element a : links) {
            String abs = a.attr("abs:href");
            if (abs == null || abs.isBlank()) continue;
            if (!abs.toLowerCase(Locale.ROOT).endsWith(".log")) continue;

            URI uri;
            try {
                uri = new URI(abs);
            } catch (URISyntaxException ex) {
                continue;
            }

            String fileName = Path.of(uri.getPath()).getFileName().toString();
            if (!p.matcher(fileName).matches()) continue;

            // Pokud by existovalo víc variant, vezmeme "nejkratší" (typicky OK1K.LOG vs OK1K_foo.LOG)
            if (fileName.length() < bestLen) {
                best = uri;
                bestLen = fileName.length();
            }
        }
        return best;
    }

    private List<YearInfo> discoverYearLinksFromIndex(String indexUrl) {
        final Document doc;
        try {
            doc = Jsoup.connect(indexUrl)
                    .userAgent("CQWW-Log-Downloader/2.1 (+Java 21 Virtual Threads; SpringBoot)")
                    .timeout(20_000)
                    .get();
        } catch (IOException e) {
            log.error("IO error while loading index page: {}", e.getMessage(), e);
            return List.of();
        }

        Map<String, YearInfo> yearLinks = new LinkedHashMap<>();
        try {
            Elements links = doc.select("a[href]");
            for (Element a : links) {
                String href = a.attr("href");
                if (href == null || href.isBlank()) continue;

                Matcher m = YEAR_PATTERN.matcher(href);
                if (m.find()) {
                    String year = m.group(1);
                    String modeSuffix = m.group(2).toLowerCase(Locale.ROOT);
                    String fullUrl = a.attr("abs:href");

                    String key = year + modeSuffix;
                    if (!yearLinks.containsKey(key)) {
                        YearInfo info = new YearInfo();
                        info.year = year;
                        info.mode = switch (modeSuffix) {
                            case "ph" -> "SSB";
                            case "cw" -> "CW";
                            case "rtty" -> "RTTY";
                            default -> modeSuffix.toUpperCase(Locale.ROOT);
                        };
                        info.url = fullUrl;
                        info.dirName = year + "_CQWW" + info.mode + "_LOGS";
                        yearLinks.put(key, info);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error while parsing year links.", e);
            return List.of();
        }

        return new ArrayList<>(yearLinks.values());
    }

    private enum Mode {
        CW("CW", "cw"),
        SSB("SSB", "ph"),
        RTTY("RTTY", "rtty");

        final String displayName;
        final String urlSuffix;

        Mode(String displayName, String urlSuffix) {
            this.displayName = displayName;
            this.urlSuffix = urlSuffix;
        }

        static Mode parse(String mode) {
            if (mode == null) throw new IllegalArgumentException("mode is null");
            String m = mode.trim().toUpperCase(Locale.ROOT);
            return switch (m) {
                case "CW" -> CW;
                case "SSB", "PH" -> SSB;
                case "RTTY" -> RTTY;
                default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
            };
        }
    }

    /**
     * Process index page with all years
     */
    private void processIndexPage(String url, Path baseOutDir, int maxConcurrent, int maxRetries, String overwriteMode) {
        final Document doc;
        try {
            doc = Jsoup.connect(url)
                    .userAgent("CQWW-Log-Downloader/2.0 (+Java 21 Virtual Threads; SpringBoot)")
                    .timeout(20_000)
                    .get();
        } catch (IOException e) {
            log.error("IO error while loading index page: {}", e.getMessage(), e);
            return;
        }

        // Find all links to years (e.g. 2024ph, 2024cw, 2023ph, ...)
        Map<String, YearInfo> yearLinks = new LinkedHashMap<>();
        try {
            Elements links = doc.select("a[href]");
            for (Element a : links) {
                String href = a.attr("href");
                if (href == null || href.isBlank()) continue;

                Matcher m = YEAR_PATTERN.matcher(href);
                if (m.find()) {
                    String year = m.group(1);
                    String modeSuffix = m.group(2).toLowerCase(Locale.ROOT);
                    String fullUrl = a.attr("abs:href");

                    String key = year + modeSuffix;
                    if (!yearLinks.containsKey(key)) {
                        YearInfo info = new YearInfo();
                        info.year = year;
                        info.mode = switch (modeSuffix) {
                            case "ph" -> "SSB";
                            case "cw" -> "CW";
                            case "rtty" -> "RTTY";
                            default -> modeSuffix.toUpperCase(Locale.ROOT);
                        };
                        info.url = fullUrl;
                        info.dirName = year + "_CQWW" + info.mode + "_LOGS";
                        yearLinks.put(key, info);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error while parsing year links.", e);
            return;
        }

        if (yearLinks.isEmpty()) {
            log.warn("No year links found on index page.");
            return;
        }

        log.info("Found {} categories to download", yearLinks.size());

        // Process each category
        for (YearInfo info : yearLinks.values()) {
            log.info("═══════════════════════════════════════════════════════════");
            log.info("Starting download: {} {} to directory: {}", info.year, info.mode, info.dirName);
            log.info("═══════════════════════════════════════════════════════════");

            Path yearOutDir = baseOutDir.resolve(info.dirName);
            try {
                if (!Files.exists(yearOutDir)) {
                    Files.createDirectories(yearOutDir);
                }
            } catch (IOException e) {
                log.error("Cannot create directory: {}", yearOutDir, e);
                continue;
            }

            processSingleYearPage(info.url, yearOutDir, maxConcurrent, maxRetries, overwriteMode);
        }

        log.info("═══════════════════════════════════════════════════════════");
        log.info("ALL CATEGORIES DOWNLOAD COMPLETED");
        log.info("═══════════════════════════════════════════════════════════");
    }

    /**
     * Process page with logs for single year (original logic)
     */
    private void processSingleYearPage(String url, Path outDir, int maxConcurrent, int maxRetries, String overwriteMode) {
        final Document doc;
        try {
            doc = Jsoup.connect(url)
                    .userAgent("CQWW-Log-Downloader/2.0 (+Java 21 Virtual Threads; SpringBoot)")
                    .timeout(20_000)
                    .get();
        } catch (UnknownHostException e) {
            log.error("DNS error (UnknownHost): {}", e.getMessage(), e);
            return;
        } catch (HttpTimeoutException e) {
            log.error("Timeout while loading list: {}", e.getMessage(), e);
            return;
        } catch (IOException e) {
            log.error("IO error while loading list: {}", e.getMessage(), e);
            return;
        } catch (IllegalArgumentException e) {
            log.error("Invalid URL or connection parameters: {}", url, e);
            return;
        } catch (SecurityException e) {
            log.error("Security restriction during network communication: {}", e.getMessage(), e);
            return;
        }

        Set<URI> uris = new LinkedHashSet<>();
        try {
            Elements links = doc.select("a[href]");
            for (Element a : links) {
                String abs = a.attr("abs:href");
                if (abs == null || abs.isBlank()) continue;
                String lower = abs.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".log")) continue;
                try {
                    uris.add(new URI(abs));
                } catch (URISyntaxException ex) {
                    log.warn("Skipped invalid link: {}", abs, ex);
                }
            }
        } catch (Exception e) {
            log.error("Error while parsing HTML.", e);
            return;
        }

        if (uris.isEmpty()) {
            log.warn("No .log files found on page. url={}", url);
            return;
        }

        log.info("Found .log files: {}", uris.size());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            final Semaphore gate = new Semaphore(maxConcurrent);

            AtomicInteger ok = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            AtomicInteger skipped = new AtomicInteger(0);
            AtomicLong totalBytes = new AtomicLong(0);

            var futures = uris.stream().map(uri -> executor.submit(() -> {
                gate.acquireUninterruptibly();
                try {
                    new DownloadTask(uri, outDir, maxRetries, overwriteMode, totalBytes, ok, failed, skipped).call();
                } finally {
                    gate.release();
                }
                return null;
            })).toList();

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted while waiting for task.", e);
                } catch (ExecutionException e) {
                    log.debug("Exception from task (already logged).", e.getCause());
                }
            }

            log.info("DONE | successful: {} skipped: {} failed: {} total: {}B",
                    ok.get(), skipped.get(), failed.get(), totalBytes.get());
        } catch (Exception e) {
            log.error("Unexpected executor error.", e);
        }
    }

    private int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            log.warn("Cannot parse number '{}', using default {}.", s, def);
            return def;
        }
    }

    /**
     * Helper class for year information
     */
    static class YearInfo {
        String year;
        String mode;  // "SSB" or "CW" or "RTTY"
        String url;
        String dirName;
    }

    @Slf4j
    static class DownloadTask implements Callable<Void> {
        private final URI uri;
        private final Path outDir;
        private final int maxRetries;
        private final String overwriteMode;
        private final String forcedFileName;
        private final AtomicLong totalBytes;
        private final AtomicInteger ok, failed, skipped;

        private static final boolean COLOR = System.console() != null;
        private static final String GREEN = COLOR ? "\u001B[32m" : "";
        private static final String RED = COLOR ? "\u001B[31m" : "";
        private static final String YELLOW = COLOR ? "\u001B[33m" : "";
        private static final String RESET = COLOR ? "\u001B[0m" : "";

        DownloadTask(URI uri, Path outDir, int maxRetries, String overwriteMode,
                     AtomicLong totalBytes, AtomicInteger ok, AtomicInteger failed, AtomicInteger skipped) {
            this(uri, outDir, maxRetries, overwriteMode, null, totalBytes, ok, failed, skipped);
        }

        DownloadTask(URI uri, Path outDir, int maxRetries, String overwriteMode, String forcedFileName,
                     AtomicLong totalBytes, AtomicInteger ok, AtomicInteger failed, AtomicInteger skipped) {
            this.uri = uri;
            this.outDir = outDir;
            this.maxRetries = maxRetries;
            this.overwriteMode = overwriteMode;
            this.forcedFileName = forcedFileName;
            this.totalBytes = totalBytes;
            this.ok = ok;
            this.failed = failed;
            this.skipped = skipped;
        }

        @Override
        public Void call() {
            String cid = java.util.UUID.randomUUID().toString().substring(0, 8);
            org.slf4j.MDC.put("cid", "[" + cid + "]");

            String originalFileName = Path.of(uri.getPath()).getFileName().toString();
            String fileName = (forcedFileName != null && !forcedFileName.isBlank()) ? forcedFileName : originalFileName;

            try {
                Path targetPath = outDir.resolve(fileName);

                if (Files.exists(targetPath)) {
                    switch (overwriteMode) {
                        case "skip":
                            skipped.incrementAndGet();
                            log.info("Skipped (already exists): {}", fileName);
                            System.out.printf("%s⏭️  [SKIP]%s %s (already exists)%n", YELLOW, RESET, fileName);
                            return null;

                        case "new":
                            String newFileName = fileName.replaceFirst("(\\.[^.]+)$", "_new$1");
                            targetPath = outDir.resolve(newFileName);
                            log.info("File exists, downloading as: {}", newFileName);
                            break;

                        case "replace":
                            log.info("File exists, will be replaced: {}", fileName);
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
                        log.info("Downloaded: {} ({} B) -> {}", uri, bytes, targetPath.getFileName());
                        System.out.printf("%s⬇️  [OK]%s %s (%d B)%n", GREEN, RESET, targetPath.getFileName(), bytes);
                        break;
                    } catch (Exception e) {
                        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                        log.warn("Download error (attempt {}/{}): {} - {}", attempt, maxRetries + 1, uri, msg);
                        System.out.printf("%s❌ [ERR]%s %s (%s)%n", RED, RESET, fileName, msg);
                        if (attempt > maxRetries) {
                            failed.incrementAndGet();
                            log.error("Retries exhausted: {}", uri);
                            break;
                        }
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.error("Interrupted while waiting for retry", ie);
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