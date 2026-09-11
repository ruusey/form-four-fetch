package com.formfour.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FilingFeedEntry {
    private String cik;
    private String accessionNoDashes;
    private String accessionDashed;
    private String formType;
    private String filedAt;
    private String title;
}
