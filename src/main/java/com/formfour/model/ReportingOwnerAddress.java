package com.formfour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportingOwnerAddress {
    @JsonProperty("rptOwnerStreet1") public String rptOwnerStreet1;
    @JsonProperty("rptOwnerStreet2") public String rptOwnerStreet2;
    @JsonProperty("rptOwnerCity") public String rptOwnerCity;
    @JsonProperty("rptOwnerState") public String rptOwnerState;
    @JsonProperty("rptOwnerZipCode") public String rptOwnerZipCode;
    @JsonProperty("rptOwnerStateDescription") public String rptOwnerStateDescription;
}
