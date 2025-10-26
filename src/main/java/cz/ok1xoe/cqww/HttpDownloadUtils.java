package cz.ok1xoe.cqww;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Slf4j
public final class HttpDownloadUtils {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private HttpDownloadUtils() {}

    public static long downloadToFile(URI uri, Path out, Duration timeout)
            throws IOException, InterruptedException, HttpTimeoutException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .header("User-Agent", "CQWW-Log-Downloader/1.3 (+Java 21 Virtual Threads; SpringBoot)")
                .timeout(timeout)
                .build();

        HttpResponse<InputStream> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());

        int code = resp.statusCode();
        if (code >= 400) {
            throw new IOException("HTTP " + code + " for " + uri);
        }

        try (InputStream in = resp.body()) {
            long bytes = Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return bytes;
        }
    }
}
