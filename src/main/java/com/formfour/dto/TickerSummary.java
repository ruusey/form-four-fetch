package com.formfour.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Per-ticker rollup of how many buys/sells have been observed (from baselines). */
@Data
@AllArgsConstructor
public class TickerSummary {
    private String ticker;
    private long buys;
    private long sells;

    public long getTotal() {
        return buys + sells;
    }
}
