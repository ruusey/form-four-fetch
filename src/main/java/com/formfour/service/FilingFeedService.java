package com.formfour.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.formfour.dto.FilingFeedEntry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pulls the EDGAR "current" atom feed for Form 4 filings and extracts
 * (cik, accessionNumber) pairs from each entry's link href.
 *
 * <p>Feed URL pattern (still active as of 2025):
 * https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=4&company=&dateb=&owner=include&count=N&output=atom
 *
 * <p>Each entry's alternate link looks like:
 * https://www.sec.gov/Archives/edgar/data/{cik}/{accession-no-dashes}/{accession-with-dashes}-index.htm
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FilingFeedService {

    private static final String FEED_URL_TEMPLATE =
            "https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=4"
                    + "&company=&dateb=&owner=include&count=%d&output=atom";

    private static final Pattern LINK_PATTERN = Pattern.compile(
            "/Archives/edgar/data/(\\d+)/(\\d{18})/(\\d{10}-\\d{2}-\\d{6})-index\\.htm");

    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "<title>([^<]+)</title>");

    private static final Pattern UPDATED_PATTERN = Pattern.compile(
            "<updated>([^<]+)</updated>");

    // The atom entry carries the real form type here, e.g.
    // <category scheme="..." label="form type" term="4"/>. We must read this
    // rather than trust the feed URL: EDGAR's getcurrent treats type=4 as a
    // PREFIX match, so the feed also returns 424B2, 40-F, 497, 485BPOS, etc.
    private static final Pattern CATEGORY_TERM_PATTERN = Pattern.compile(
            "<category[^>]*\\bterm=\"([^\"]+)\"");

    private final EdgarClient edgar;

    @Value("${formfour.poll-count:40}")
    private int pollCount;

    public List<FilingFeedEntry> fetchRecent() {
        String url = String.format(FEED_URL_TEMPLATE, pollCount);
        try {
            String body = edgar.get(url);
            return parse(body);
        } catch (Exception e) {
            log.error("Failed to fetch EDGAR atom feed: {}", e.getMessage());
            return List.of();
        }
    }

    List<FilingFeedEntry> parse(String atom) {
        List<FilingFeedEntry> result = new ArrayList<>();
        // Crude split on <entry> — robust enough for the EDGAR feed shape.
        String[] entries = atom.split("<entry>");
        int skipped = 0;
        for (int i = 1; i < entries.length; i++) {
            String chunk = entries[i];
            Matcher link = LINK_PATTERN.matcher(chunk);
            if (!link.find()) continue;

            // Keep only genuine Form 4 / 4/A entries; drop the prefix-matched
            // noise (424B2, 40-F, ...) before it ever costs a submission fetch.
            String formType = firstGroup(CATEGORY_TERM_PATTERN, chunk);
            if (!"4".equals(formType) && !"4/A".equals(formType)) {
                skipped++;
                continue;
            }

            String cik = link.group(1);
            String accNoDashes = link.group(2);
            String accDashed = link.group(3);
            String title = firstGroup(TITLE_PATTERN, chunk);
            String updated = firstGroup(UPDATED_PATTERN, chunk);
            result.add(new FilingFeedEntry(cik, accNoDashes, accDashed, formType, updated, title));
        }
        if (skipped > 0) {
            log.debug("Feed: kept {} Form 4 entries, skipped {} non-Form-4 (prefix-matched) entries",
                    result.size(), skipped);
        }
        return result;
    }

    private String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
