package com.formfour.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.formfour.dto.UnusualTxReasonCode;
import com.formfour.model.NonDerivativeTransaction;
import com.formfour.model.OwnershipDocument;
import com.formfour.model.ReportingOwner;
import com.formfour.model.TickerBaseline;
import com.formfour.repo.TickerBaselineRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Tier-1 anomaly detection: scores each open-market buy (P) / sell (S) leg of a
 * Form 4 against a rolling per-ticker baseline (see {@link TickerBaseline}) and
 * flags transactions that fall outside that ticker's trained normal range.
 *
 * <p>Only P and S are modeled — gifts (G), tax withholding (F), grants (A),
 * option exercises (M), etc. are ignored, since they have entirely different
 * distributions and would pollute the baseline.
 *
 * <p>Anything outside the normal range is logged to the dedicated
 * {@code ABNORMAL_TX} logger (routed to its own file in logback.xml) so it can
 * be alerted on / grepped independently of normal INFO traffic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnomalyDetectionService {

    /** Dedicated channel for transactions outside the trained normal range. */
    private static final Logger ABNORMAL = LoggerFactory.getLogger("ABNORMAL_TX");

    private final TickerBaselineRepository baselines;

    /** |robust-Z| at or above this is considered outside the normal range. */
    @Value("${formfour.anomaly.z-threshold:3.5}")
    private double zThreshold;
    /** Minimum observations before a baseline is trusted enough to flag. */
    @Value("${formfour.anomaly.min-samples:8}")
    private int minSamples;
    /** Max recent observations retained per baseline for robust stats. */
    @Value("${formfour.anomaly.reservoir-size:200}")
    private int reservoirSize;

    // MAD floors so a degenerate (near-constant) baseline neither explodes the
    // Z-score nor goes blind. Units: log-dollars, fraction, log-dollars.
    private static final double MAD_FLOOR_LOG_VALUE = 0.5;
    private static final double MAD_FLOOR_PCT = 0.03;
    private static final double MAD_FLOOR_LOG_PRICE = 0.3;

    /**
     * Score every P/S non-derivative leg, set the document-level anomaly fields
     * to the most abnormal leg, and roll each ticker baseline forward.
     * Mutates {@code doc}; call before persisting.
     */
    public void score(OwnershipDocument doc) {
        if (doc.getNonDerivativeTable() == null
                || doc.getNonDerivativeTable().nonDerivativeTransaction == null) {
            return;
        }
        String ticker = tickerOf(doc);
        String owner = ownerOf(doc);

        double maxScore = 0;
        Set<UnusualTxReasonCode> reasons = EnumSet.noneOf(UnusualTxReasonCode.class);
        boolean scoredAny = false;

        for (NonDerivativeTransaction tx : doc.getNonDerivativeTable().nonDerivativeTransaction) {
            String code = code(tx);
            if (!"P".equals(code) && !"S".equals(code)) continue; // buys/sells only
            scoredAny = true;

            double value = tx.transactionValue == null ? 0 : tx.transactionValue.doubleValue();
            double shares = parse(tx.transactionAmounts != null && tx.transactionAmounts.transactionShares != null
                    ? tx.transactionAmounts.transactionShares.value : null);
            double price = parse(tx.transactionAmounts != null && tx.transactionAmounts.transactionPricePerShare != null
                    ? tx.transactionAmounts.transactionPricePerShare.value : null);
            double sharesAfter = parse(tx.postTransactionAmounts != null
                    && tx.postTransactionAmounts.sharesOwnedFollowingTransaction != null
                    ? tx.postTransactionAmounts.sharesOwnedFollowingTransaction.value : null);
            double pctHoldings = shares / Math.max(1.0, shares + sharesAfter);

            double logValue = Math.log1p(Math.max(0, value));
            double logPrice = Math.log1p(Math.max(0, price));

            String key = ticker + "|" + code;
            TickerBaseline b = baselines.findById(key)
                    .orElseGet(() -> new TickerBaseline(key, ticker, code));

            if (b.getCount() < minSamples) {
                // Cold start: record but don't flag — no trained range yet.
                reasons.add(UnusualTxReasonCode.INSUFFICIENT_HISTORY);
                log.debug("Anomaly: {} {} building baseline ({}/{} samples), not scored",
                        ticker, code, b.getCount(), minSamples);
                update(b, logValue, pctHoldings, logPrice);
                continue;
            }

            double zValue = robustZ(logValue, b.getMedianLogValue(), b.getMadLogValue(), MAD_FLOOR_LOG_VALUE);
            double zPct = robustZ(pctHoldings, b.getMedianPctHoldings(), b.getMadPctHoldings(), MAD_FLOOR_PCT);
            double zPrice = robustZ(logPrice, b.getMedianLogPrice(), b.getMadLogPrice(), MAD_FLOOR_LOG_PRICE);

            Set<UnusualTxReasonCode> legReasons = EnumSet.noneOf(UnusualTxReasonCode.class);
            if (Math.abs(zValue) >= zThreshold) legReasons.add(UnusualTxReasonCode.LARGE);
            if (Math.abs(zPct) >= zThreshold) legReasons.add(UnusualTxReasonCode.SIGNIFICANT_CHANGE_IN_OWNERSHIP);
            if (Math.abs(zPrice) >= zThreshold) legReasons.add(UnusualTxReasonCode.PRICE_OUTLIER);

            double maxZ = Math.max(Math.abs(zValue), Math.max(Math.abs(zPct), Math.abs(zPrice)));
            double legScore = Math.min(100.0, maxZ / zThreshold * 60.0);

            if (!legReasons.isEmpty()) {
                logAbnormal(ticker, owner, code, value, shares, price, pctHoldings,
                        legScore, legReasons, b, zValue, zPct, zPrice);
                reasons.addAll(legReasons);
                maxScore = Math.max(maxScore, legScore);
            }

            update(b, logValue, pctHoldings, logPrice);
        }

        if (!scoredAny) return;
        if (reasons.isEmpty()) reasons.add(UnusualTxReasonCode.NORMAL);
        doc.setAnomalyScore(maxScore);
        doc.setAnomalyReasons(new ArrayList<>(reasons));
        doc.setScoredAt(Instant.now().toEpochMilli());
    }

    private void logAbnormal(String ticker, String owner, String code, double value, double shares,
                             double price, double pctHoldings, double score,
                             Set<UnusualTxReasonCode> reasons, TickerBaseline b,
                             double zValue, double zPct, double zPrice) {
        double baselineValue = Math.expm1(b.getMedianLogValue());
        double valueMultiple = baselineValue > 0 ? value / baselineValue : 0;
        ABNORMAL.warn(
                "ABNORMAL {} {} | owner='{}' | value=${} ({}x baseline median ${}, z={}) | "
                        + "pctHoldings={}% (z={}) | price=${} (z={}) | score={} reasons={} | "
                        + "baseline[n={}]",
                "P".equals(code) ? "BUY" : "SELL", ticker, owner,
                fmt(value), fmt(valueMultiple), fmt(baselineValue), fmt(zValue),
                fmt(pctHoldings * 100), fmt(zPct), fmt(price), fmt(zPrice),
                fmt(score), reasons, b.getCount());
    }

    /** Modified Z-score using median/MAD; MAD floored to avoid divide-by-zero. */
    private static double robustZ(double x, double median, double mad, double madFloor) {
        return 0.6745 * (x - median) / Math.max(mad, madFloor);
    }

    /** Append an observation to the bounded reservoir and recompute robust stats. */
    private void update(TickerBaseline b, double logValue, double pctHoldings, double logPrice) {
        push(b.getRecentLogValues(), logValue);
        push(b.getRecentPctHoldings(), pctHoldings);
        push(b.getRecentLogPrices(), logPrice);

        b.setMedianLogValue(median(b.getRecentLogValues()));
        b.setMadLogValue(mad(b.getRecentLogValues(), b.getMedianLogValue()));
        b.setMedianPctHoldings(median(b.getRecentPctHoldings()));
        b.setMadPctHoldings(mad(b.getRecentPctHoldings(), b.getMedianPctHoldings()));
        b.setMedianLogPrice(median(b.getRecentLogPrices()));
        b.setMadLogPrice(mad(b.getRecentLogPrices(), b.getMedianLogPrice()));

        b.setCount(b.getCount() + 1);
        b.setUpdatedAt(Instant.now().toEpochMilli());
        baselines.save(b);
    }

    private void push(List<Double> reservoir, double v) {
        reservoir.add(v);
        while (reservoir.size() > reservoirSize) reservoir.remove(0);
    }

    private static double median(List<Double> xs) {
        if (xs.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    private static double mad(List<Double> xs, double median) {
        if (xs.isEmpty()) return 0;
        List<Double> dev = new ArrayList<>(xs.size());
        for (double x : xs) dev.add(Math.abs(x - median));
        return median(dev);
    }

    private static String tickerOf(OwnershipDocument doc) {
        if (doc.getFilingEntity() != null && !doc.getFilingEntity().isBlank()) {
            return doc.getFilingEntity();
        }
        if (doc.getIssuer() != null && doc.getIssuer().issuerTradingSymbol != null) {
            return doc.getIssuer().issuerTradingSymbol;
        }
        return doc.getId() == null ? "?" : doc.getId();
    }

    private static String ownerOf(OwnershipDocument doc) {
        List<ReportingOwner> owners = doc.getReportingOwner();
        if (owners != null && !owners.isEmpty() && owners.get(0).reportingOwnerId != null
                && owners.get(0).reportingOwnerId.rptOwnerName != null) {
            return owners.get(0).reportingOwnerId.rptOwnerName;
        }
        return "?";
    }

    private static String code(NonDerivativeTransaction tx) {
        return tx.transactionCoding == null ? null : tx.transactionCoding.transactionCode;
    }

    private static double parse(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return new BigDecimal(s).doubleValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String fmt(double d) {
        return String.format("%,.2f", d);
    }
}
