package com.formfour.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Footnote {
    @JsonProperty("id") private String id;
    private String text;

    @JsonAnySetter
    private void addProps(String name, Object value) {
        if (name != null && name.isEmpty()) {
            this.text = String.valueOf(value);
        }
    }
}
