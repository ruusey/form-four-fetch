package com.formfour.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.formfour.dto.FilingFeedEntry;
import com.formfour.model.OwnershipDocument;
import com.formfour.service.FilingFeedService;
import com.formfour.service.FormFourService;

@RestController
@RequestMapping("/api/form4")
public class FormFourController {

    @Autowired private FormFourService formFour;
    @Autowired private FilingFeedService feed;

    @GetMapping
    public Page<OwnershipDocument> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return formFour.getSaved(page, size);
    }

    @GetMapping("/{cik}/{accession}")
    public ResponseEntity<OwnershipDocument> one(
            @PathVariable String cik,
            @PathVariable String accession) {
        OwnershipDocument d = formFour.getFormFour(cik, accession.replace("-", ""));
        return d == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(d);
    }

    @GetMapping("/by-entity/{ticker}")
    public List<OwnershipDocument> byEntity(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "0") int page) {
        return formFour.getSavedByEntity(ticker.toUpperCase(), page);
    }

    @GetMapping("/recent")
    public List<FilingFeedEntry> recent() {
        return feed.fetchRecent();
    }
}
