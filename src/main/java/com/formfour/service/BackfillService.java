package com.formfour.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Historical backfill of Form 4 filings, used to seed the per-ticker anomaly
 * baselines with real history. Two modes, both on one shared background thread:
 * <ul>
 *   <li>{@link #start(String, Integer)} — one issuer's most-recent Form 4s
 *       (via the submissions JSON), and</li>
 *   <li>{@link #startRecent(int)} — every Form 4 filed in the last N days,
 *       walking EDGAR's daily index one day at a time.</li>
 * </ul>
 *
 * <p><b>Deliberately gentle on EDGAR.</b> The SEC fair-access policy caps
 * requests at 10/sec; exceeding it risks a temporary IP ban. Backfill:
 * <ul>
 *   <li>runs one filing at a time (no concurrency),</li>
 *   <li>sleeps between filings on top of the {@link EdgarClient} throttle,
 *       keeping the effective rate a few req/sec,</li>
 *   <li>skips already-persisted filings without re-fetching (so it is cheaply
 *       resumable after a restart), and</li>
 *   <li>refuses to start a second run while one is in progress.</li>
 * </ul>
 */
@Service
@Slf4j
public class BackfillService {

    private static final String SUBMISSIONS_URL = "https://data.sec.gov/submissions/CIK%010d.json";
    private static final String DAILY_INDEX_URL =
            "https://www.sec.gov/Archives/edgar/daily-index/%d/QTR%d/master.%s.idx";

    @Autowired
    private EdgarClient edgar;

    @Autowired
    private FormFourService formFour;

    private final ObjectMapper json = new ObjectMapper();

    /** Extra pause between filings, on top of EdgarClient's throttle. */
    @Value("${formfour.backfill.delay-ms:400}")
    private long delayMs;
    /** Default filings pulled per run when the caller doesn't specify. */
    @Value("${formfour.backfill.default-max:100}")
    private int defaultMax;
    /** Absolute ceiling per run, regardless of requested amount. */
    @Value("${formfour.backfill.hard-cap:500}")
    private int hardCap;
    /** Pause between filings during a recent-days backfill (ms). */
    @Value("${formfour.backfill.recent-delay-ms:250}")
    private long recentDelayMs;
    /** If > 0, backfill this many days of Form 4s automatically on startup. */
    @Value("${formfour.backfill.startup-days:0}")
    private int startupDays;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "edgar-backfill");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Kick off a backfill for one CIK. Returns immediately; work proceeds on a
     * background thread. Returns {@code false} if a backfill is already running.
     */
    public boolean start(String cik, Integer requestedMax) {
        int max = requestedMax == null ? defaultMax : requestedMax;
        max = Math.max(1, Math.min(max, hardCap));
        if (!running.compareAndSet(false, true)) {
            log.warn("Backfill already running; ignoring request for CIK {}", cik);
            return false;
        }
        final int cap = max;
        worker.submit(() -> {
            try {
                run(cik, cap);
            } catch (Exception e) {
                log.error("Backfill for CIK {} failed: {}", cik, e.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    /** Optionally kick off a recent-days backfill once the app is up. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(3)
    public void onStartup() {
        if (startupDays > 0) {
            log.info("Startup backfill enabled: last {} days of Form 4s", startupDays);
            startRecent(startupDays);
        }
    }

    /**
     * Kick off a backfill of every Form 4 filed in the last {@code days} days,
     * walking the EDGAR daily index. Returns immediately; {@code false} if a
     * backfill is already running.
     */
    public boolean startRecent(int days) {
        final int window = Math.max(1, days);
        if (!running.compareAndSet(false, true)) {
            log.warn("Backfill already running; ignoring recent({}d) request", window);
            return false;
        }
        worker.submit(() -> {
            try {
                runRecent(window);
            } catch (Exception e) {
                log.error("Recent backfill ({}d) failed: {}", window, e.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    private void runRecent(int days) {
        LocalDate today = LocalDate.now();
        log.info("Recent backfill: scanning last {} days of the EDGAR daily index "
                + "(already-saved filings are skipped)", days);
        int fetched = 0, skipped = 0, daysWithData = 0;

        for (int offset = 1; offset <= days; offset++) {
            LocalDate day = today.minusDays(offset);
            int qtr = (day.getMonthValue() - 1) / 3 + 1;
            String stamp = String.format("%04d%02d%02d", day.getYear(), day.getMonthValue(), day.getDayOfMonth());
            String url = String.format(DAILY_INDEX_URL, day.getYear(), qtr, stamp);

            String index;
            try {
                index = edgar.get(url);
            } catch (Exception e) {
                // Weekends/holidays have no daily index — expected, skip quietly.
                log.debug("No daily index for {} (weekend/holiday?)", day);
                continue;
            }
            daysWithData++;

            List<String[]> form4s = parseForm4Rows(index); // [cik, accessionNoDashes]
            log.info("Recent backfill: {} — {} Form 4 filings", day, form4s.size());

            for (String[] row : form4s) {
                String cik = row[0];
                String accNoDashes = row[1];
                String id = cik + "-" + accNoDashes;
                if (formFour.exists(id)) { // resumable: no re-fetch, no throttle sleep
                    skipped++;
                    continue;
                }
                try {
                    if (formFour.getFormFour(cik, accNoDashes) != null) fetched++;
                } catch (Exception e) {
                    log.warn("Recent backfill: failed {} — {}", id, e.getMessage());
                }
                sleep(recentDelayMs); // gentle pacing on real fetches only
            }
        }
        log.info("Recent backfill complete: {} days with data, {} fetched, {} already present",
                daysWithData, fetched, skipped);
    }

    /** Extract [cik, accessionNoDashes] for every Form 4 / 4-A row in a master.idx. */
    private static List<String[]> parseForm4Rows(String index) {
        List<String[]> rows = new ArrayList<>();
        for (String line : index.split("\n")) {
            String[] f = line.split("\\|");
            if (f.length < 5) continue;
            String form = f[2].trim();
            if (!"4".equals(form) && !"4/A".equals(form)) continue;
            String cik = f[0].trim();
            String path = f[4].trim(); // e.g. edgar/data/1001250/0001145766-26-000006.txt
            int slash = path.lastIndexOf('/');
            if (slash < 0) continue;
            String accDashed = path.substring(slash + 1).replace(".txt", "");
            rows.add(new String[]{cik, FormFourService.stripDashes(accDashed)});
        }
        return rows;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run(String cik, int max) throws Exception {
        int cikNum = Integer.parseInt(cik);
        String url = String.format(SUBMISSIONS_URL, cikNum);
        JsonNode root = json.readTree(edgar.get(url));

        JsonNode recent = root.path("filings").path("recent");
        JsonNode forms = recent.path("form");
        JsonNode accessions = recent.path("accessionNumber");
        if (!forms.isArray() || !accessions.isArray()) {
            log.warn("Backfill: no recent filings array for CIK {}", cik);
            return;
        }

        List<String> form4Accessions = new ArrayList<>();
        for (int i = 0; i < forms.size() && form4Accessions.size() < max; i++) {
            String form = forms.get(i).asText();
            if ("4".equals(form) || "4/A".equals(form)) {
                form4Accessions.add(accessions.get(i).asText()); // dashed form
            }
        }

        log.info("Backfill: CIK {} — {} Form 4 filings to fetch (cap {}), ~{}ms apart",
                cik, form4Accessions.size(), max, delayMs);

        int ok = 0;
        for (int i = 0; i < form4Accessions.size(); i++) {
            String accNoDashes = FormFourService.stripDashes(form4Accessions.get(i));
            var doc = formFour.getFormFour(cik, accNoDashes); // fetch+parse+score+persist (cached)
            if (doc != null) ok++;
            if (i % 25 == 24) {
                log.info("Backfill: CIK {} progress {}/{}", cik, i + 1, form4Accessions.size());
            }
            Thread.sleep(delayMs); // gentle pacing on top of EdgarClient's throttle
        }
        log.info("Backfill: CIK {} complete — {}/{} filings persisted", cik, ok, form4Accessions.size());
    }
}
