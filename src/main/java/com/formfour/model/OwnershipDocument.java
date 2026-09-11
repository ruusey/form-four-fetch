package com.formfour.model;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@Document("formfour")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OwnershipDocument {
    @Id
    private String id;
    @JsonProperty("schemaVersion")
    private String schemaVersion;
    @JsonProperty("documentType")
    private String documentType;
    @JsonProperty("periodOfReport")
    private String periodOfReport;
    @JsonProperty("notSubjectToSection16")
    private String notSubjectToSection16;
    @JsonProperty("noSecuritiesOwned")
    private Double noSecuritiesOwned;
    @JsonProperty("issuer")
    private Issuer issuer;
    @JsonProperty("reportingOwner")
    private List<ReportingOwner> reportingOwner;
    @JsonProperty("nonDerivativeTable")
    private NonDerivativeTable nonDerivativeTable;
    @JsonProperty("derivativeTable")
    private DerivativeTable derivativeTable;
    @JsonProperty("ownerSignature")
    private List<OwnerSignature> ownerSignature;
    @JsonProperty("footnotes")
    private Footnotes footnotes;

    private BigDecimal transactionValue;
    private String filingEntity;
}
