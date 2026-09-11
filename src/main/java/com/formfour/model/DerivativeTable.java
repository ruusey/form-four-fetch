package com.formfour.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DerivativeTable {
    @JsonProperty("derivativeTransaction")
    public List<DerivativeTransaction> derivativeTransaction;
    @JsonProperty("derivativeHolding")
    public List<DerivativeHolding> derivativeHolding;
}
