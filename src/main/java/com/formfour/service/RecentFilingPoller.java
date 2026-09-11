package com.formfour.service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.formfour.dto.FilingFeedEntry;
import com.formfour.model.OwnershipDocument;

import lombok.extern.slf4j.Slf4j;

/**
 * Replaces the old RecentFilingCollector + InsiderTransactionHandler. Polls
 * the EDGAR atom feed on a fixed delay; for each newly-seen filing fetches
 * the Form 4 doc, persists it, and broadcasts to the STOMP topic.
 */
@Component
@Slf4j
public class RecentFilingPoller {

    private static final int SEEN_HORIZON = 1000;

    @Autowired
    private FilingFeedService feed;

    @Autowired
    private FormFourService formFour;

    @Autowired
    private SimpMessagingTemplate broker;

    @Autowired
    private TickerMapService tickerMap;

    @Autowired
    private IssuerInfoService issuerInfo;

    @Autowired
    private DiscordNotifier discord;

    private final Set<String> seen = new LinkedHashSet<>();
    private boolean primed = false;

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void prime() {
        log.info("Priming RecentFilingPoller with current feed snapshot...");
        var entries = feed.fetchRecent();
        for (FilingFeedEntry entry : entries) {
            seen.add(key(entry));
        }

        log.info("=== Last {} Form 4 filings (preview, not persisted) ===",
                Math.min(5, entries.size()));
        int previewed = 0;
        for (FilingFeedEntry entry : entries) {
            if (previewed >= 5) break;
            OwnershipDocument doc = formFour.fetchTransient(entry.getCik(), entry.getAccessionNoDashes());
            if (doc != null) {
                logTransactions(doc);
                previewed++;
            }
        }
        log.info("=== End preview ===");

        primed = true;
        log.info("Primed with {} filings; will poll for new entries", seen.size());
    }

    @Scheduled(fixedDelayString = "${formfour.poll-interval-ms:30000}")
    public void poll() {
        if (!primed) return;
        var entries = feed.fetchRecent();
        if (entries.isEmpty()) return;

        Set<FilingFeedEntry> fresh = new HashSet<>();
        for (FilingFeedEntry e : entries) {
            String k = key(e);
            if (!seen.contains(k)) {
                fresh.add(e);
                seen.add(k);
            }
        }
        trimSeen();

        if (fresh.isEmpty()) {
            log.info("Poll: feed had {} entries, no new Form 4s since last poll", entries.size());
            return;
        }
        log.info("Poll: feed had {} entries, {} new Form 4s", entries.size(), fresh.size());

        java.util.List<OwnershipDocument> posted = new java.util.ArrayList<>();
        for (FilingFeedEntry e : fresh) {
            try {
                OwnershipDocument doc = formFour.getFormFour(e.getCik(), e.getAccessionNoDashes());
                if (doc != null) {
                    broker.convertAndSend("/topic/filings", doc);
                    logTransactions(doc);
                    posted.add(doc);
                }
            } catch (Exception ex) {
                log.error("Failed to handle filing {}: {}", e.getAccessionDashed(), ex.getMessage());
            }
        }
        discord.notifyFilings(posted); // batched + throttled; abnormal legs flagged
    }

    private String key(FilingFeedEntry e) {
        return e.getCik() + "-" + e.getAccessionNoDashes();
    }

    private void logTransactions(OwnershipDocument doc) {
        String ticker = doc.getIssuer() != null ? safe(doc.getIssuer().issuerTradingSymbol) : "?";
        String name = doc.getIssuer() != null ? safe(doc.getIssuer().issuerName) : "?";
        String exchange = "";
        if (doc.getIssuer() != null) {
            var lookup = tickerMap.byCik(doc.getIssuer().issuerCik);
            if (lookup.isPresent()) {
                if ("?".equals(ticker) || ticker.isBlank()) ticker = lookup.get().getTicker();
                if ("?".equals(name) || name.isBlank()) name = lookup.get().getName();
                exchange = " @" + lookup.get().getExchange();
            } else {
                exchange = " @UNLISTED";
            }
        }
        String seller = "?";
        if (doc.getReportingOwner() != null && !doc.getReportingOwner().isEmpty()
                && doc.getReportingOwner().get(0).reportingOwnerId != null) {
            seller = safe(doc.getReportingOwner().get(0).reportingOwnerId.rptOwnerName);
        }
        String sector = doc.getIssuer() != null
                ? Optional.ofNullable(issuerInfo.sectorOf(doc.getIssuer().issuerCik)).orElse("Unknown")
                : "Unknown";

        if (doc.getNonDerivativeTable() != null
                && doc.getNonDerivativeTable().nonDerivativeTransaction != null) {
            for (var tx : doc.getNonDerivativeTable().nonDerivativeTransaction) {
                String code = tx.transactionCoding != null ? tx.transactionCoding.transactionCode : null;
                String shares = tx.transactionAmounts != null && tx.transactionAmounts.transactionShares != null
                        ? tx.transactionAmounts.transactionShares.value : "?";
                String price = tx.transactionAmounts != null && tx.transactionAmounts.transactionPricePerShare != null
                        ? tx.transactionAmounts.transactionPricePerShare.value : "0";
                log.info("FORM4 {} | {}{} ({}) [{}] | {} | shares={} price={} value={} [code={}]",
                        directionLabel(code, txAcqDisp(tx)), ticker, exchange, name, sector, seller,
                        shares, price, tx.transactionValue, code);
            }
        }
        if (doc.getDerivativeTable() != null
                && doc.getDerivativeTable().derivativeTransaction != null) {
            for (var tx : doc.getDerivativeTable().derivativeTransaction) {
                String code = tx.transactionCoding != null ? tx.transactionCoding.transactionCode : null;
                String shares = tx.transactionAmounts != null && tx.transactionAmounts.transactionShares != null
                        ? tx.transactionAmounts.transactionShares.value : "?";
                String price = tx.transactionAmounts != null && tx.transactionAmounts.transactionPricePerShare != null
                        ? tx.transactionAmounts.transactionPricePerShare.value : "0";
                log.info("FORM4 {} (deriv) | {}{} ({}) [{}] | {} | shares={} price={} value={} [code={}]",
                        directionLabel(code, txAcqDispDeriv(tx)), ticker, exchange, name, sector, seller,
                        shares, price, tx.transactionValue, code);
            }
        }
    }

    private static String txAcqDisp(com.formfour.model.NonDerivativeTransaction tx) {
        return tx.transactionAmounts != null && tx.transactionAmounts.transactionAcquiredDisposedCode != null
                ? tx.transactionAmounts.transactionAcquiredDisposedCode.value : null;
    }

    private static String txAcqDispDeriv(com.formfour.model.DerivativeTransaction tx) {
        return tx.transactionAmounts != null && tx.transactionAmounts.transactionAcquiredDisposedCode != null
                ? tx.transactionAmounts.transactionAcquiredDisposedCode.value : null;
    }

    /**
     * Map a Form 4 transaction code to a human label. Codes per SEC Form 4
     * General Instruction 8.
     */
    private static String directionLabel(String code, String acqDisp) {
        if (code == null) return "Unknown transaction";
        return switch (code) {
            case "P" -> "Purchased";
            case "S" -> "Sold";
            case "A" -> "Granted/Awarded";
            case "M" -> "Exercised derivative";
            case "X" -> "Exercised in-the-money option";
            case "C" -> "Converted derivative";
            case "F" -> "Withheld for tax/exercise";
            case "G" -> "Gifted";
            case "D" -> "Disposed to issuer";
            case "I" -> "Discretionary plan transaction";
            case "J" -> "Other transaction (see footnote)";
            case "K" -> "Equity swap transaction";
            case "V" -> "Voluntarily reported early";
            case "W" -> "Inherited";
            case "Z" -> "Voting-trust transfer";
            default -> "A".equals(acqDisp) ? "Acquired" : "Disposed";
        };
    }

    private static String safe(String s) {
        return s == null ? "?" : s;
    }

    private void trimSeen() {
        while (seen.size() > SEEN_HORIZON) {
            var it = seen.iterator();
            it.next();
            it.remove();
        }
    }
}
