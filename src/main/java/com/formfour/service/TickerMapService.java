package com.formfour.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads SEC's authoritative CIK → ticker/name/exchange map from
 * https://www.sec.gov/files/company_tickers_exchange.json and caches it.
 * Used to fill in / validate the trading symbol on Form 4 filings whose
 * &lt;issuerTradingSymbol&gt; element is blank or non-tradeable.
 */
@Service
@Slf4j
public class TickerMapService {

    private static final String URL = "https://www.sec.gov/files/company_tickers_exchange.json";

    @Autowired
    private EdgarClient edgar;

    private final ObjectMapper json = new ObjectMapper();
    private volatile Map<String, TickerEntry> byCik = Map.of();
    private volatile Map<String, TickerEntry> byTicker = Map.of();

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void load() {
        try {
            String body = edgar.get(URL);
            JsonNode root = json.readTree(body);
            JsonNode data = root.path("data");
            Map<String, TickerEntry> map = new HashMap<>(data.size() * 2);
            Map<String, TickerEntry> tickerMap = new HashMap<>(data.size() * 2);
            for (JsonNode row : data) {
                if (row.size() < 4) continue;
                String cik = row.get(0).asText();
                String name = row.get(1).asText();
                String ticker = row.get(2).asText();
                String exchange = row.get(3).asText();
                TickerEntry entry = new TickerEntry(cik, ticker, name, exchange);
                map.put(cik, entry);
                if (ticker != null && !ticker.isBlank()) {
                    tickerMap.put(ticker.toUpperCase(), entry);
                }
            }
            this.byCik = map;
            this.byTicker = tickerMap;
            log.info("Loaded SEC ticker map: {} tradeable issuers", map.size());
        } catch (Exception e) {
            log.error("Failed to load SEC ticker map: {}", e.getMessage());
        }
    }

    /** Refresh once a day. SEC updates this file periodically. */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000L, initialDelay = 24 * 60 * 60 * 1000L)
    public void refresh() {
        load();
    }

    public Optional<TickerEntry> byCik(String cik) {
        if (cik == null) return Optional.empty();
        // EDGAR submissions sometimes pad CIK to 10 digits; the map keys are unpadded.
        try {
            return Optional.ofNullable(byCik.get(String.valueOf(Integer.parseInt(cik))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Optional<TickerEntry> byTicker(String ticker) {
        if (ticker == null) return Optional.empty();
        return Optional.ofNullable(byTicker.get(ticker.toUpperCase()));
    }

    public boolean isTradeable(String cik) {
        return byCik(cik).isPresent();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TickerEntry {
        private String cik;
        private String ticker;
        private String name;
        private String exchange;
    }
}
