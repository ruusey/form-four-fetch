package com.formfour.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportingOwnerRelationship {
    @JsonProperty("isDirector") public String isDirector;
    @JsonProperty("isOfficer") public String isOfficer;
    @JsonProperty("isTenPercentOwner") public String isTenPercentOwner;
    @JsonProperty("isOther") public String isOther;
    @JsonProperty("officerTitle") public String officerTitle;
    @JsonProperty("otherText") public String otherText;
}
