package com.formfour.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Lazy-loaded cache of per-CIK issuer info pulled from
 * https://data.sec.gov/submissions/CIK{padded}.json. Used to resolve the
 * issuer's SIC industry description (i.e. its "sector").
 */
@Service
@Slf4j
public class IssuerInfoService {

    private static final String URL_TEMPLATE = "https://data.sec.gov/submissions/CIK%010d.json";

    @Autowired
    private EdgarClient edgar;

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, IssuerInfo> cache = new ConcurrentHashMap<>();
    private static final IssuerInfo MISSING = new IssuerInfo(null, null, null);

    public Optional<IssuerInfo> byCik(String cik) {
        if (cik == null) return Optional.empty();
        long cikL;
        try { cikL = Long.parseLong(cik); } catch (NumberFormatException e) { return Optional.empty(); }
        IssuerInfo info = cache.computeIfAbsent(String.valueOf(cikL), k -> fetch(cikL));
        return info == MISSING ? Optional.empty() : Optional.of(info);
    }

    public String sectorOf(String cik) {
        return byCik(cik).map(IssuerInfo::getSicDescription).orElse(null);
    }

    private IssuerInfo fetch(long cik) {
        String url = String.format(URL_TEMPLATE, cik);
        try {
            JsonNode root = json.readTree(edgar.get(url));
            return new IssuerInfo(
                    root.path("sic").asText(null),
                    root.path("sicDescription").asText(null),
                    root.path("category").asText(null));
        } catch (Exception e) {
            log.debug("Issuer info fetch failed for CIK {}: {}", cik, e.getMessage());
            return MISSING;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IssuerInfo {
        private String sic;
        private String sicDescription;
        private String category;
    }
}
