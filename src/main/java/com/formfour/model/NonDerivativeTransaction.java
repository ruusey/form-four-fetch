package com.formfour.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NonDerivativeTransaction {
    @JsonProperty("securityTitle") public SecurityTitle securityTitle;
    @JsonProperty("transactionDate") public TransactionDate transactionDate;
    @JsonProperty("deemedExecutionDate") public DeemedExecutionDate deemedExecutionDate;
    @JsonProperty("transactionCoding") public TransactionCoding transactionCoding;
    @JsonProperty("transactionTimeliness") public TransactionTimeliness transactionTimeliness;
    @JsonProperty("transactionAmounts") public TransactionAmounts transactionAmounts;
    @JsonProperty("postTransactionAmounts") public PostTransactionAmounts postTransactionAmounts;
    @JsonProperty("ownershipNature") public OwnershipNature ownershipNature;
    @JsonProperty("transactionValue") public BigDecimal transactionValue;

    public void calculateTxValue() {
        if (transactionAmounts == null || transactionAmounts.transactionShares == null) return;
        BigDecimal shares = new BigDecimal(transactionAmounts.transactionShares.value);
        BigDecimal price = new BigDecimal(
                transactionAmounts.transactionPricePerShare == null
                        || transactionAmounts.transactionPricePerShare.value == null
                                ? "0"
                                : transactionAmounts.transactionPricePerShare.value);
        this.transactionValue = shares.multiply(price);
    }
}
