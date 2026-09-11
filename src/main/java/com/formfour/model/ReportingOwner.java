package com.formfour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportingOwner {
    @JsonProperty("reportingOwnerId")
    public ReportingOwnerId reportingOwnerId;
    @JsonProperty("reportingOwnerAddress")
    public ReportingOwnerAddress reportingOwnerAddress;
    @JsonProperty("reportingOwnerRelationship")
    public ReportingOwnerRelationship reportingOwnerRelationship;
}
