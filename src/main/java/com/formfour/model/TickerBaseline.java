package com.formfour.model;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Rolling per-ticker, per-direction (buy=P / sell=S) baseline of what a
 * "normal" open-market transaction looks like for a given issuer. This is the
 * trained normal range a new transaction is scored against (Tier-1).
 *
 * <p>We keep a bounded reservoir of the most recent observations and recompute
 * robust statistics (median + MAD) from it on each update. Median/MAD are used
 * instead of mean/stddev so a single mega-trade can't blow up the baseline.
 *
 * <p>Id convention: {@code TICKER|DIRECTION}, e.g. {@code "AAPL|S"}.
 */
@Document("tickerBaselines")
@Data
@NoArgsConstructor
public class TickerBaseline {

    @Id
    private String id;
    private String ticker;
    private String direction; // "P" or "S"
    private long count;

    // Robust center/spread over log1p(transactionValue).
    private double medianLogValue;
    private double madLogValue;
    // Robust center/spread over fraction of holdings moved (0..1).
    private double medianPctHoldings;
    private double madPctHoldings;
    // Robust center/spread over log1p(pricePerShare).
    private double medianLogPrice;
    private double madLogPrice;

    // Bounded recent samples, newest appended last.
    private List<Double> recentLogValues = new ArrayList<>();
    private List<Double> recentPctHoldings = new ArrayList<>();
    private List<Double> recentLogPrices = new ArrayList<>();

    private long updatedAt;

    public TickerBaseline(String id, String ticker, String direction) {
        this.id = id;
        this.ticker = ticker;
        this.direction = direction;
    }
}
