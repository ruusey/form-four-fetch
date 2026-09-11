package com.formfour.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * One-shot historical backfill of a single issuer's Form 4 filings, used to
 * seed the per-ticker anomaly baselines with real history.
 *
 * <p><b>Deliberately gentle on EDGAR.</b> The SEC fair-access policy caps
 * requests at 10/sec; exceeding it risks a temporary IP ban. This backfill:
 * <ul>
 *   <li>runs one filing at a time (no concurrency),</li>
 *   <li>sleeps {@code formfour.backfill.delay-ms} between filings on top of the
 *       {@link EdgarClient} throttle, keeping the effective rate a few req/sec,</li>
 *   <li>hard-caps how many filings a single run will pull, and</li>
 *   <li>refuses to start a second run while one is in progress.</li>
 * </ul>
 * It pulls only the issuer's most-recent filings page (no deep pagination), so
 * a run fetches a bounded, moderate number of documents.
 */
@Service
@Slf4j
public class BackfillService {

    private static final String SUBMISSIONS_URL = "https://data.sec.gov/submissions/CIK%010d.json";

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

    public boolean isRunning() {
        return running.get();
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
