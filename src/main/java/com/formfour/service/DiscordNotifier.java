package com.formfour.service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formfour.dto.UnusualTxReasonCode;
import com.formfour.model.NonDerivativeTransaction;
import com.formfour.model.OwnershipDocument;
import com.formfour.model.ReportingOwner;
import com.formfour.model.ReportingOwnerRelationship;

import lombok.extern.slf4j.Slf4j;

/**
 * Posts new Form 4 filings to a Discord channel via webhook. Each filing is a
 * rich embed; transactions flagged abnormal by {@link AnomalyDetectionService}
 * are rendered in red with a warning header, anomaly score, and reasons.
 *
 * <p>Sends run on a single background thread so polling is never blocked and
 * messages are serialized. Batches up to 10 embeds per message and honors
 * Discord's rate limit (429 + {@code retry_after}) so a burst of filings can't
 * trip the webhook.
 */
@Service
@Slf4j
public class DiscordNotifier {

    private static final int MAX_EMBEDS_PER_MESSAGE = 10;
    private static final int MAX_LEGS_SHOWN = 6;

    // Embed colors (decimal RGB).
    private static final int RED = 0xE74C3C;    // abnormal
    private static final int GREEN = 0x2ECC71;  // normal buy
    private static final int ORANGE = 0xE67E22; // normal sell
    private static final int BLUE = 0x3498DB;    // other/mixed

    @Value("${formfour.discord.enabled:true}")
    private boolean enabled;
    @Value("${formfour.discord.webhook-url:}")
    private String webhookUrl;
    /** Minimum spacing between webhook messages (ms). ~30 msg/min per hook. */
    @Value("${formfour.discord.min-interval-ms:1200}")
    private long minIntervalMs;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "discord-notifier");
        t.setDaemon(true);
        return t;
    });

    private boolean active() {
        return enabled && webhookUrl != null && !webhookUrl.isBlank();
    }

    @PostConstruct
    void logState() {
        if (active()) {
            log.info("Discord notifications ENABLED — new Form 4s will be posted to the webhook");
        } else {
            log.warn("Discord notifications DISABLED — set formfour.discord.webhook-url "
                    + "(e.g. in local.yml or the DISCORD_WEBHOOK_URL env var) to enable");
        }
    }

    /**
     * Send a one-off test message. Returns the Discord HTTP status (2xx = ok),
     * -2 if no webhook is configured, or -1 if the request threw.
     */
    public int sendTest() {
        if (!active()) return -2;
        try {
            ObjectNode p = json.createObjectNode();
            p.put("content", "✅ form-four-fetch webhook test — notifications are working.");
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(p)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("Discord test -> {} : {}", resp.statusCode(),
                        resp.body().substring(0, Math.min(200, resp.body().length())));
            }
            return resp.statusCode();
        } catch (Exception e) {
            log.warn("Discord test failed: {}", e.getMessage());
            return -1;
        }
    }

    /** Enqueue a batch of new filings for posting. Returns immediately. */
    public void notifyFilings(List<OwnershipDocument> docs) {
        if (!active() || docs == null || docs.isEmpty()) return;
        List<OwnershipDocument> snapshot = new ArrayList<>(docs);
        sender.submit(() -> {
            try {
                post(snapshot);
            } catch (Exception e) {
                log.warn("Discord post failed: {}", e.getMessage());
            }
        });
    }

    private void post(List<OwnershipDocument> docs) throws Exception {
        for (int i = 0; i < docs.size(); i += MAX_EMBEDS_PER_MESSAGE) {
            List<OwnershipDocument> chunk = docs.subList(i, Math.min(i + MAX_EMBEDS_PER_MESSAGE, docs.size()));
            sendMessage(chunk);
        }
    }

    private void sendMessage(List<OwnershipDocument> chunk) throws Exception {
        ObjectNode payload = json.createObjectNode();
        ArrayNode embeds = payload.putArray("embeds");

        long abnormal = chunk.stream().filter(DiscordNotifier::isAbnormal).count();
        if (abnormal > 0) {
            payload.put("content", "⚠️ **" + abnormal + " abnormal insider transaction"
                    + (abnormal == 1 ? "" : "s") + "** detected — review below.");
        }
        for (OwnershipDocument doc : chunk) {
            embeds.add(buildEmbed(doc));
        }

        String body = json.writeValueAsString(payload);
        HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        // Retry once on Discord's 429 using the server-provided backoff.
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status / 100 == 2) break;
            if (status == 429 && attempt == 0) {
                long wait = retryAfterMs(resp.body());
                log.warn("Discord rate-limited, waiting {}ms", wait);
                Thread.sleep(wait);
                continue;
            }
            log.warn("Discord webhook returned {} : {}", status,
                    resp.body().substring(0, Math.min(200, resp.body().length())));
            break;
        }
        Thread.sleep(minIntervalMs); // steady spacing between messages
    }

    private ObjectNode buildEmbed(OwnershipDocument doc) {
        boolean abnormal = isAbnormal(doc);
        String ticker = tickerOf(doc);
        String issuer = doc.getIssuer() != null && doc.getIssuer().issuerName != null
                ? doc.getIssuer().issuerName : ticker;
        String owner = ownerOf(doc);
        String role = roleOf(doc);

        List<NonDerivativeTransaction> legs = doc.getNonDerivativeTable() != null
                && doc.getNonDerivativeTable().nonDerivativeTransaction != null
                ? doc.getNonDerivativeTable().nonDerivativeTransaction
                : List.of();

        ObjectNode embed = json.createObjectNode();
        String prefix = abnormal ? "⚠️ " : "";
        embed.put("title", prefix + ticker + " — " + issuer);
        embed.put("url", edgarUrl(doc));
        embed.put("color", colorFor(doc, legs));

        StringBuilder desc = new StringBuilder();
        desc.append("**").append(owner).append("**");
        if (!role.isBlank()) desc.append(" · ").append(role);
        if (doc.getTransactionValue() != null && doc.getTransactionValue().signum() != 0) {
            desc.append("\nTotal transaction value: **").append(money(doc.getTransactionValue())).append("**");
        }
        embed.put("description", desc.toString());

        ArrayNode fields = embed.putArray("fields");

        if (abnormal) {
            String reasons = doc.getAnomalyReasons() == null ? "" : doc.getAnomalyReasons().stream()
                    .filter(r -> r != UnusualTxReasonCode.NORMAL && r != UnusualTxReasonCode.INSUFFICIENT_HISTORY)
                    .map(DiscordNotifier::humanReason)
                    .collect(Collectors.joining(", "));
            ObjectNode f = fields.addObject();
            f.put("name", "⚠️ Anomaly score: " + Math.round(doc.getAnomalyScore()) + "/100");
            f.put("value", reasons.isBlank() ? "Outside this ticker's normal range" : reasons);
            f.put("inline", false);
        }

        int shown = 0;
        for (NonDerivativeTransaction tx : legs) {
            String code = tx.transactionCoding != null ? tx.transactionCoding.transactionCode : null;
            if (code == null) continue;
            if (shown >= MAX_LEGS_SHOWN) {
                ObjectNode more = fields.addObject();
                more.put("name", "…");
                more.put("value", "+" + (countCodedLegs(legs) - shown) + " more transaction(s)");
                more.put("inline", false);
                break;
            }
            String shares = value(tx.transactionAmounts != null && tx.transactionAmounts.transactionShares != null
                    ? tx.transactionAmounts.transactionShares.value : null);
            String price = tx.transactionAmounts != null && tx.transactionAmounts.transactionPricePerShare != null
                    ? tx.transactionAmounts.transactionPricePerShare.value : null;
            ObjectNode f = fields.addObject();
            f.put("name", verb(code) + "  " + shares + (price != null ? " @ " + money(price) : ""));
            f.put("value", tx.transactionValue != null && tx.transactionValue.signum() != 0
                    ? money(tx.transactionValue) : "​");
            f.put("inline", true);
            shown++;
        }

        ObjectNode footer = embed.putObject("footer");
        footer.put("text", abnormal ? "Form 4 · flagged abnormal" : "Form 4");
        String ts = isoTimestamp(doc.getPeriodOfReport());
        if (ts != null) embed.put("timestamp", ts);

        return embed;
    }

    // --- helpers ---

    static boolean isAbnormal(OwnershipDocument d) {
        return d.getAnomalyScore() != null && d.getAnomalyScore() > 0;
    }

    private static int colorFor(OwnershipDocument doc, List<NonDerivativeTransaction> legs) {
        if (isAbnormal(doc)) return RED;
        boolean hasBuy = false, hasSell = false;
        for (NonDerivativeTransaction tx : legs) {
            String c = tx.transactionCoding != null ? tx.transactionCoding.transactionCode : null;
            if ("P".equals(c)) hasBuy = true;
            if ("S".equals(c)) hasSell = true;
        }
        if (hasBuy && !hasSell) return GREEN;
        if (hasSell && !hasBuy) return ORANGE;
        return BLUE;
    }

    private static int countCodedLegs(List<NonDerivativeTransaction> legs) {
        int n = 0;
        for (NonDerivativeTransaction tx : legs) {
            if (tx.transactionCoding != null && tx.transactionCoding.transactionCode != null) n++;
        }
        return n;
    }

    private static String verb(String code) {
        return switch (code) {
            case "P" -> "🟢 Buy";
            case "S" -> "🔴 Sell";
            case "A" -> "Grant/Award";
            case "M", "X" -> "Option exercise";
            case "C" -> "Conversion";
            case "F" -> "Tax withholding";
            case "G" -> "Gift";
            case "D" -> "Disposed to issuer";
            default -> "Code " + code;
        };
    }

    private static String humanReason(UnusualTxReasonCode r) {
        return switch (r) {
            case LARGE -> "unusually large value";
            case SIGNIFICANT_CHANGE_IN_OWNERSHIP -> "large change in holdings";
            case PRICE_OUTLIER -> "off-baseline price";
            case CLUSTERED -> "clustered activity";
            default -> r.name().toLowerCase().replace('_', ' ');
        };
    }

    private static String tickerOf(OwnershipDocument doc) {
        if (doc.getFilingEntity() != null && !doc.getFilingEntity().isBlank()) return doc.getFilingEntity();
        if (doc.getIssuer() != null && doc.getIssuer().issuerTradingSymbol != null) {
            return doc.getIssuer().issuerTradingSymbol;
        }
        return "?";
    }

    private static String ownerOf(OwnershipDocument doc) {
        List<ReportingOwner> owners = doc.getReportingOwner();
        if (owners != null && !owners.isEmpty() && owners.get(0).reportingOwnerId != null
                && owners.get(0).reportingOwnerId.rptOwnerName != null) {
            return owners.get(0).reportingOwnerId.rptOwnerName;
        }
        return "Unknown filer";
    }

    private static String roleOf(OwnershipDocument doc) {
        List<ReportingOwner> owners = doc.getReportingOwner();
        if (owners == null || owners.isEmpty() || owners.get(0).reportingOwnerRelationship == null) return "";
        ReportingOwnerRelationship rel = owners.get(0).reportingOwnerRelationship;
        List<String> parts = new ArrayList<>();
        if (isTrue(rel.isDirector)) parts.add("Director");
        if (isTrue(rel.isOfficer)) parts.add(rel.officerTitle != null && !rel.officerTitle.isBlank()
                ? rel.officerTitle : "Officer");
        if (isTrue(rel.isTenPercentOwner)) parts.add("10% Owner");
        if (isTrue(rel.isOther)) parts.add(rel.otherText != null && !rel.otherText.isBlank()
                ? rel.otherText : "Other");
        return String.join(", ", parts);
    }

    private static boolean isTrue(String s) {
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private static String edgarUrl(OwnershipDocument doc) {
        String acc = doc.getId(); // now the accession (no dashes)
        String cik = doc.getIssuer() != null ? doc.getIssuer().issuerCik : null;
        if (acc == null || acc.length() != 18 || cik == null || cik.isBlank()) {
            return "https://www.sec.gov/cgi-bin/browse-edgar";
        }
        return "https://www.sec.gov/Archives/edgar/data/" + Integer.parseInt(cik) + "/" + acc
                + "/" + FormFourService.withDashes(acc) + "-index.htm";
    }

    private static String isoTimestamp(String periodOfReport) {
        if (periodOfReport == null || !periodOfReport.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        return periodOfReport + "T00:00:00.000Z";
    }

    private static long retryAfterMs(String body) {
        try {
            double sec = new ObjectMapper().readTree(body).path("retry_after").asDouble(1.0);
            return Math.min((long) (sec * 1000) + 100, 10_000L);
        } catch (Exception e) {
            return 2_000L;
        }
    }

    private static String value(String s) {
        double d = parse(s);
        return d == 0 && (s == null || s.isBlank()) ? "?" : String.format("%,.0f sh", d);
    }

    private static String money(String s) {
        return "$" + String.format("%,.2f", parse(s));
    }

    private static String money(BigDecimal b) {
        return "$" + String.format("%,.2f", b);
    }

    private static double parse(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return new BigDecimal(s).doubleValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
