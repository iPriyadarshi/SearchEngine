package com.searchengine.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;

/**
 * Downloads a large file to disk, resuming with HTTP {@code Range} requests
 * after a dropped connection instead of starting over.
 * <p>
 * Over a slow or flaky link a 1 GB Common Crawl file can take longer than a
 * connection survives; streaming it end to end would then never finish.
 */
public final class ResumableDownload {

    static final int MAX_RETRIES = 30;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ResumableDownload() {
    }

    /**
     * Download {@code url} to {@code target}, resuming any bytes already there.
     */
    public static void fetch(String url, Path target) throws IOException, InterruptedException {

        Long total = null;

        for (int attempt = 0; ; attempt++) {

            long have = Files.exists(target) ? Files.size(target) : 0;

            if (total != null && have >= total) {

                return;
            }

            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", CommonCrawlSource.USER_AGENT)
                    .GET();

            if (have > 0) {

                request.header("Range", "bytes=" + have + "-");
            }

            try {

                HttpResponse<InputStream> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());

                int status = response.statusCode();

                if (status == 416 && have > 0) {

                    // Range not satisfiable: we already have the whole file.
                    response.body().close();

                    return;
                }

                boolean resumed = status == 206;

                if (status != 200 && !resumed) {

                    response.body().close();

                    throw new IOException("HTTP " + status + " for " + url);
                }

                total = totalLength(response, resumed, have).orElse(total);

                // A 200 means the server ignored the range: start over.
                StandardOpenOption mode = resumed ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;

                try (InputStream in = response.body();
                     OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode)) {

                    in.transferTo(out);
                }

                long now = Files.size(target);

                if (total == null || now >= total) {

                    return;
                }

            } catch (IOException e) {

                if (attempt >= MAX_RETRIES) {

                    throw new IOException("Giving up on " + url + " after " + attempt + " retries", e);
                }
            }

            // Back off a little longer each time: 2 s, 4 s, ... up to a minute.
            Thread.sleep(Math.min(60_000, 2_000L * (attempt + 1)));
        }
    }

    private static Optional<Long> totalLength(HttpResponse<?> response, boolean resumed, long have) {

        if (resumed) {

            // Content-Range: bytes 1000-1999/123456
            return response.headers().firstValue("Content-Range")
                    .map(v -> v.substring(v.lastIndexOf('/') + 1).strip())
                    .filter(v -> v.matches("\\d+"))
                    .map(Long::parseLong);
        }

        return response.headers().firstValueAsLong("Content-Length").stream().boxed().findFirst();
    }
}
