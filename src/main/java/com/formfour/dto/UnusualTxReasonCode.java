package com.formfour.dto;

public enum UnusualTxReasonCode {
    NORMAL,
    /** Dollar value of the trade is far from this ticker's baseline. */
    LARGE,
    /** Multiple insider trades clustered in a short window (reserved for Tier-2). */
    CLUSTERED,
    /** Trade moved an unusually large fraction of the insider's holdings. */
    SIGNIFICANT_CHANGE_IN_OWNERSHIP,
    /** Price/share deviates markedly from this ticker's baseline. */
    PRICE_OUTLIER,
    /** Not enough history for this ticker+direction to judge yet. */
    INSUFFICIENT_HISTORY
}
