package com.formfour.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.formfour.dto.TickerSummary;
import com.formfour.model.OwnershipDocument;
import com.formfour.model.TickerBaseline;
import com.formfour.repo.FormFourRepository;
import com.formfour.repo.TickerBaselineRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Form 4 fetch + parse + persist. Replaces the form-4 portions of
 * DataFetcherService from the parent project.
 */
@Service
@Slf4j
public class FormFourService {

    private static final String EDGAR_BASE = "https://www.sec.gov";

    @Autowired
    private EdgarClient edgar;

    @Autowired
    private FormFourRepository repo;

    @Autowired
    private AnomalyDetectionService anomaly;

    @Autowired
    private TickerBaselineRepository baselines;

    private final XmlMapper xmlMapper = new XmlMapper();
    private final ObjectMapper jsonMapper;

    public FormFourService() {
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
        this.jsonMapper.coercionConfigFor(LogicalType.POJO)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull);
        this.jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Fetch a Form 4 by CIK + accession (no dashes). Caches in Mongo.
     */
    public OwnershipDocument getFormFour(String cik, String accessionNoDashes) {
        // Key by accession alone — it's globally unique per filing. The same
        // Form 4 is indexed under the issuer AND every reporting owner's CIK, so
        // keying by cik+accession would store one filing many times over.
        String id = accessionNoDashes;
        Optional<OwnershipDocument> cached = repo.findById(id);
        if (cached.isPresent()) {
            log.debug("Form4 cache hit: {}", id);
            return cached.get();
        }

        long start = Instant.now().toEpochMilli();
        try {
            String submissionTxt = fetchSubmissionTxt(cik, accessionNoDashes);
            OwnershipDocument doc = parseFormFour(submissionTxt);
            if (doc == null) {
                log.warn("No <ownershipDocument> in {}", id);
                return null;
            }
            doc.setId(id);
            if (doc.getIssuer() != null) {
                doc.setFilingEntity(Optional.ofNullable(doc.getIssuer().issuerTradingSymbol).orElse(cik));
            }
            anomaly.score(doc); // scores P/S legs + rolls the ticker baseline forward
            repo.save(doc);
            log.info("Fetched + saved Form4 {} in {}ms", id, Instant.now().toEpochMilli() - start);
            return doc;
        } catch (Exception e) {
            log.error("Failed to fetch Form4 {}: {}", id, e.getMessage());
            return null;
        }
    }

    /** Fetch + parse without persisting. Useful for startup previews. */
    public OwnershipDocument fetchTransient(String cik, String accessionNoDashes) {
        try {
            String txt = fetchSubmissionTxt(cik, accessionNoDashes);
            OwnershipDocument doc = parseFormFour(txt);
            if (doc != null) {
                doc.setId(accessionNoDashes);
                if (doc.getIssuer() != null) {
                    doc.setFilingEntity(doc.getIssuer().issuerTradingSymbol == null
                            ? cik : doc.getIssuer().issuerTradingSymbol);
                }
            }
            return doc;
        } catch (Exception e) {
            log.warn("fetchTransient failed for {}-{}: {}", cik, accessionNoDashes, e.getMessage());
            return null;
        }
    }

    private String fetchSubmissionTxt(String cik, String accessionNoDashes) throws Exception {
        String accessionDashed = withDashes(accessionNoDashes);
        String url = EDGAR_BASE + "/Archives/edgar/data/" + Integer.parseInt(cik) + "/"
                + accessionNoDashes + "/" + accessionDashed + ".txt";
        return edgar.get(url);
    }

    public OwnershipDocument parseFormFour(String submission) throws IOException {
        String openTag = "<ownershipDocument>";
        String closeTag = "</ownershipDocument>";
        int start = submission.indexOf(openTag);
        int end = submission.indexOf(closeTag);
        if (start < 0 || end < 0) return null;
        String xml = submission.substring(start, end + closeTag.length());

        JsonNode node = xmlMapper.readTree(xml.getBytes());
        OwnershipDocument doc = jsonMapper.treeToValue(node, OwnershipDocument.class);
        rollUpTransactionValues(doc);
        return doc;
    }

    private void rollUpTransactionValues(OwnershipDocument doc) {
        BigDecimal total = BigDecimal.ZERO;
        if (doc.getNonDerivativeTable() != null
                && doc.getNonDerivativeTable().nonDerivativeTransaction != null) {
            for (var tx : doc.getNonDerivativeTable().nonDerivativeTransaction) {
                tx.calculateTxValue();
                if (tx.transactionValue != null) total = total.add(tx.transactionValue);
            }
        }
        if (doc.getDerivativeTable() != null
                && doc.getDerivativeTable().derivativeTransaction != null) {
            for (var tx : doc.getDerivativeTable().derivativeTransaction) {
                tx.calculateTxValue();
                if (tx.transactionValue != null) total = total.add(tx.transactionValue);
            }
        }
        doc.setTransactionValue(total);
    }

    public Page<OwnershipDocument> getSaved(int page, int size) {
        return repo.findAll(PageRequest.of(page, size, Sort.by("periodOfReport").descending()));
    }

    public Page<OwnershipDocument> getSavedWithValueGte(int page, BigDecimal value) {
        return repo.findWithValueGreaterThan(value, PageRequest.of(page, 25));
    }

    public java.util.List<OwnershipDocument> getSavedByEntity(String entity, int page) {
        Pageable p = PageRequest.of(page, 40, Sort.by("periodOfReport").descending());
        return repo.findAllByFilingEntity(entity, p);
    }

    public Page<OwnershipDocument> getAnomalies(double minScore, int page, int size) {
        return repo.findByAnomalyScoreGreaterThanEqualOrderByAnomalyScoreDesc(
                minScore, PageRequest.of(page, size));
    }

    /** Tickers that have observed buys/sells, most-active first (for search/dropdowns). */
    public List<TickerSummary> tickersWithData() {
        Map<String, long[]> agg = new LinkedHashMap<>();
        for (TickerBaseline b : baselines.findAll()) {
            long[] bs = agg.computeIfAbsent(b.getTicker(), k -> new long[2]);
            if ("P".equals(b.getDirection())) bs[0] += b.getCount();
            else if ("S".equals(b.getDirection())) bs[1] += b.getCount();
        }
        List<TickerSummary> out = new ArrayList<>(agg.size());
        agg.forEach((t, bs) -> out.add(new TickerSummary(t, bs[0], bs[1])));
        out.sort((a, b) -> Long.compare(b.getTotal(), a.getTotal()));
        return out;
    }

    /** Dashboard summary counts. */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalFilings", repo.count());
        m.put("tickersWithData", tickersWithData().size());
        m.put("flaggedAnomalies", repo.countByAnomalyScoreGreaterThan(0.0));
        return m;
    }

    public boolean exists(String id) {
        return repo.existsById(id);
    }

    public static String withDashes(String accessionNoDashes) {
        if (accessionNoDashes.contains("-")) return accessionNoDashes;
        StringBuilder sb = new StringBuilder(accessionNoDashes);
        sb.insert(10, '-');
        sb.insert(13, '-');
        return sb.toString();
    }

    public static String stripDashes(String accession) {
        return accession.replace("-", "");
    }
}
