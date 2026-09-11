package com.formfour.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormFourDto {
    private String id;
    private String filingEntity = "";

    private String reportingOwnerName;
    private String reportingOwnerCik;
    private String isDirector;
    private String isOfficer;
    private String isTenPercentOwner;
    private String isOther;
    private String officerTitle;
    private String otherTitle;

    private String nonDerivTableSecurityType;
    private String nonDerivTableTransactionDate;
    private String nonDerivTableTransactionFormType;
    private String nonDerivTableTransactionCode;
    private String nonDerivTableisEquitySwapsInvolved = "0.0";
    private String nonDerivTableNumberOfShares = "0.0";
    private String nonDerivTablePricePerShare = "0.0";
    private String nonDerivTableAquiredOrDisposedOfCode;
    private String nonDerivTableSharesOwnedAfterTransaction;
    private String nonDerivTableDirectOrIndirectOwnership;

    private String derivSecurityType;
    private String derivConversionOrExercisePrice = "0.0";
    private String derivTransactionDate;
    private String derivTableTransactionCode;
    private String derivIsEquitySwapsInvolved;
    private String derivNumberOfShares = "0.0";
    private String derivPricePerShare = "0.0";
    private String derivAquiredOrDisposedOfCode;
    private String derivSharesOwnedAfterTransaction;
    private String derivDirectOrIndirectOwnership;
    private String derivTableTransactionFormType;

    private Long transactionTimestamp;
    private Long collectedTimestamp;

    private BigDecimal transactionValue;
    private List<UnusualTxReasonCode> unusualTxCodes;

    public FormFourDto() {}

    public FormFourDto(String entity) {
        this.filingEntity = entity;
    }
}
