package com.formfour.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NonDerivativeTable {
    @JsonProperty("nonDerivativeTransaction")
    public List<NonDerivativeTransaction> nonDerivativeTransaction;
    @JsonProperty("nonDerivativeHolding")
    public List<NonDerivativeHolding> nonDerivativeHolding;
}
