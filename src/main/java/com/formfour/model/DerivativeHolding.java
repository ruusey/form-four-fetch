package com.formfour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DerivativeHolding {
    @JsonProperty("securityTitle") public SecurityTitle securityTitle;
    @JsonProperty("conversionOrExercisePrice") public ConversionOrExercisePrice conversionOrExercisePrice;
    @JsonProperty("postTransactionAmounts") public PostTransactionAmounts postTransactionAmounts;
    @JsonProperty("ownershipNature") public OwnershipNature ownershipNature;
    @JsonProperty("exerciseDate") public ExerciseDate exerciseDate;
    @JsonProperty("expirationDate") public ExpirationDate expirationDate;
    @JsonProperty("underlyingSecurity") public UnderlyingSecurity underlyingSecurity;
}
