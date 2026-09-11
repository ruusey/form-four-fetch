package com.formfour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Issuer {
    @JsonProperty("issuerCik")
    public String issuerCik;
    @JsonProperty("issuerName")
    public String issuerName;
    @JsonProperty("issuerTradingSymbol")
    public String issuerTradingSymbol;
}
