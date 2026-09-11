package com.formfour.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionCoding {
    @JsonProperty("transactionFormType") public String transactionFormType;
    @JsonProperty("transactionCode") public String transactionCode;
    @JsonProperty("equitySwapInvolved") public String equitySwapInvolved;
    @JsonProperty("footnoteId") public List<FootnoteId> footnoteId;
}
