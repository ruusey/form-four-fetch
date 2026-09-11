package com.formfour.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.formfour.dto.FilingFeedEntry;
import com.formfour.model.OwnershipDocument;
import com.formfour.service.BackfillService;
import com.formfour.service.FilingFeedService;
import com.formfour.service.FormFourService;
import com.formfour.service.TickerMapService;

@RestController
@RequestMapping("/api/form4")
public class FormFourController {

    @Autowired private FormFourService formFour;
    @Autowired private FilingFeedService feed;
    @Autowired private BackfillService backfill;
    @Autowired private TickerMapService tickerMap;

    @GetMapping
    public Page<OwnershipDocument> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return formFour.getSaved(page, size);
    }

    @GetMapping("/{cik}/{accession}")
    public ResponseEntity<OwnershipDocument> one(
            @PathVariable String cik,
            @PathVariable String accession) {
        OwnershipDocument d = formFour.getFormFour(cik, accession.replace("-", ""));
        return d == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(d);
    }

    @GetMapping("/by-entity/{ticker}")
    public List<OwnershipDocument> byEntity(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "0") int page) {
        return formFour.getSavedByEntity(ticker.toUpperCase(), page);
    }

    @GetMapping("/recent")
    public List<FilingFeedEntry> recent() {
        return feed.fetchRecent();
    }

    /** Dashboard summary counts (total filings, tickers with data, flagged anomalies). */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return formFour.stats();
    }

    /** Tickers that have observed buys/sells, most-active first. */
    @GetMapping("/tickers")
    public List<com.formfour.dto.TickerSummary> tickers() {
        return formFour.tickersWithData();
    }

    /** Backfill/recompute progress for the dashboard header + monitoring. */
    @GetMapping("/backfill/status")
    public Map<String, Object> backfillStatus() {
        return backfill.status();
    }

    /** Rebuild baselines chronologically and re-score all filings (background). */
    @PostMapping("/anomaly/recompute")
    public ResponseEntity<Map<String, Object>> recompute() {
        boolean started = backfill.startRecompute();
        return ResponseEntity.ok(Map.of(
                "started", started,
                "message", started ? "Recompute started" : "A backfill/recompute is already running"));
    }

    /** Filings whose most abnormal P/S leg scored at or above {@code minScore}. */
    @GetMapping("/anomalies")
    public Page<OwnershipDocument> anomalies(
            @RequestParam(defaultValue = "60") double minScore,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return formFour.getAnomalies(minScore, page, size);
    }

    /**
     * Kick off a throttled backfill of every Form 4 filed in the last
     * {@code days} days (default 30) via the EDGAR daily index. Returns
     * immediately; already-saved filings are skipped.
     */
    @PostMapping("/backfill/recent")
    public ResponseEntity<Map<String, Object>> backfillRecent(
            @RequestParam(defaultValue = "30") int days) {
        boolean started = backfill.startRecent(days);
        return ResponseEntity.ok(Map.of(
                "started", started,
                "days", days,
                "message", started ? "Recent backfill started" : "A backfill is already running"));
    }

    /**
     * Kick off a throttled historical backfill for one issuer to seed its
     * anomaly baseline. Accepts a CIK or a ticker symbol. Returns immediately.
     */
    @PostMapping("/backfill/{cikOrTicker}")
    public ResponseEntity<Map<String, Object>> backfill(
            @PathVariable String cikOrTicker,
            @RequestParam(required = false) Integer max) {
        String cik = resolveCik(cikOrTicker);
        if (cik == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "started", false, "error", "Unknown ticker: " + cikOrTicker));
        }
        boolean started = backfill.start(cik, max);
        return ResponseEntity.ok(Map.of(
                "started", started,
                "cik", cik,
                "message", started ? "Backfill started" : "A backfill is already running"));
    }

    private String resolveCik(String cikOrTicker) {
        if (cikOrTicker.matches("\\d+")) return cikOrTicker;
        return tickerMap.byTicker(cikOrTicker)
                .map(TickerMapService.TickerEntry::getCik)
                .orElse(null);
    }
}
