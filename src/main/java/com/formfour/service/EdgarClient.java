package com.formfour.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Thin HTTP wrapper for SEC EDGAR. Uses java.net.http.HttpClient and the
 * descriptive User-Agent the SEC requires. Throttles requests to stay under
 * the 10-req/sec fair-access limit.
 */
@Component
@Slf4j
public class EdgarClient {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String userAgent;
    private final long requestDelayMs;
    private final int maxRetries;
    private final long retryBackoffMs;
    private long lastRequestAt;

    public EdgarClient(@Value("${formfour.user-agent}") String userAgent,
                       @Value("${formfour.request-delay-ms:150}") long requestDelayMs,
                       @Value("${formfour.max-retries:4}") int maxRetries,
                       @Value("${formfour.retry-backoff-ms:500}") long retryBackoffMs) {
        this.userAgent = userAgent;
        this.requestDelayMs = requestDelayMs;
        this.maxRetries = maxRetries;
        this.retryBackoffMs = retryBackoffMs;
        log.info("EdgarClient ready (UA='{}', delay={}ms, maxRetries={}, backoff={}ms)",
                userAgent, requestDelayMs, maxRetries, retryBackoffMs);
    }

    public String get(String url) throws Exception {
        return get(url, Map.of());
    }

    public synchronized String get(String url, Map<String, String> queryParams) throws Exception {
        throttle();
        String fullUrl = url;
        if (!queryParams.isEmpty()) {
            String qs = queryParams.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey()) + "=" + URLEncoder.encode(e.getValue()))
                    .collect(Collectors.joining("&"));
            fullUrl += (url.contains("?") ? "&" : "?") + qs;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(fullUrl))
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        // EDGAR intermittently sheds load with a 503 (and 429 if we exceed the
        // fair-access limit). These are transient, so retry with exponential
        // backoff before giving up, honoring a Retry-After header when present.
        int attempt = 0;
        while (true) {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status / 100 == 2) {
                return resp.body();
            }
            boolean retryable = (status == 503 || status == 429) && attempt < maxRetries;
            if (!retryable) {
                throw new RuntimeException("EDGAR " + status + " for " + fullUrl
                        + (attempt > 0 ? " (after " + attempt + " retries)" : "")
                        + " body=" + resp.body().substring(0, Math.min(200, resp.body().length())));
            }
            long wait = backoffMillis(resp, attempt);
            log.warn("EDGAR {} for {} — retry {}/{} in {}ms", status, fullUrl,
                    attempt + 1, maxRetries, wait);
            Thread.sleep(wait);
            attempt++;
        }
    }

    /** Exponential backoff, capped, preferring the server's Retry-After (seconds). */
    private long backoffMillis(HttpResponse<String> resp, int attempt) {
        long retryAfter = resp.headers().firstValue("Retry-After")
                .map(v -> {
                    try {
                        return Long.parseLong(v.trim()) * 1000L;
                    } catch (NumberFormatException e) {
                        return -1L;
                    }
                })
                .filter(ms -> ms > 0)
                .orElse(-1L);
        if (retryAfter > 0) {
            return Math.min(retryAfter, 10_000L);
        }
        return Math.min(retryBackoffMs * (1L << attempt), 10_000L);
    }

    private void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long since = now - lastRequestAt;
        if (since < requestDelayMs) {
            Thread.sleep(requestDelayMs - since);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private static final class URLEncoder {
        static String encode(String s) {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
