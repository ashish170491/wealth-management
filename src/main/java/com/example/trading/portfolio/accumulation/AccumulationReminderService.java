package com.example.trading.portfolio.accumulation;

import com.example.trading.notification.EmailNotificationService;
import com.example.trading.notification.EmailTemplateService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.scheduler.MarketHoursService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scans active accumulation plans each trading morning and emails the investor
 * a reminder if any tranche is due today (SIP mode) or if the current price has
 * hit the ladder trigger (PRICE_LADDER mode). See SPEC §8 and §14.
 *
 * <p>Signal-gated mode is not reminded here — those fire off breakout / sector
 * reversal / market-direction signals, which already have their own emails.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccumulationReminderService {

    private static final Locale INDIA = Locale.of("en", "IN");
    private static final NumberFormat INR = NumberFormat.getCurrencyInstance(INDIA);

    private final AccumulationPlanRepository planRepository;
    private final AccumulationTrancheRepository trancheRepository;
    private final HoldingsRepository holdingsRepository;
    private final EmailTemplateService tpl;
    private final EmailNotificationService mail;
    private final MarketHoursService marketHoursService;

    @Value("${portfolio.reports.accumulation-reminder-enabled:true}")
    private boolean enabled;

    /** Daily check at 10:00 IST MON-FRI — sends email only if something is due. */
    @Scheduled(cron = "0 0 10 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyCheck() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        if (!enabled) return;
        try {
            sendIfAnyDue();
        } catch (Exception e) {
            log.error("AccumulationReminder: failed: {}", e.getMessage(), e);
        }
    }

    public int sendIfAnyDue() throws MessagingException {
        LocalDate today = LocalDate.now();
        List<AccumulationPlanEntity> active = planRepository.findByStatus(AccumulationService.STATUS_ACTIVE);

        List<DueItem> sipDue = new ArrayList<>();
        List<DueItem> ladderHit = new ArrayList<>();

        for (AccumulationPlanEntity plan : active) {
            List<AccumulationTrancheEntity> tranches = trancheRepository
                    .findByPlanIdAndStatus(plan.getId(), AccumulationService.TRANCHE_PENDING);
            double currentPrice = priceFor(plan.getSymbol());

            for (AccumulationTrancheEntity t : tranches) {
                if (AccumulationService.MODE_SIP.equals(plan.getMode())
                        && t.getTriggerDate() != null
                        && !t.getTriggerDate().isAfter(today)) {
                    sipDue.add(new DueItem(plan, t, currentPrice, "SIP date reached ("
                            + t.getTriggerDate() + ")"));
                } else if (AccumulationService.MODE_PRICE_LADDER.equals(plan.getMode())
                        && t.getTriggerPrice() != null
                        && currentPrice > 0
                        && currentPrice <= t.getTriggerPrice()) {
                    ladderHit.add(new DueItem(plan, t, currentPrice, String.format(
                            "Price %s hit ladder target %s", inr(currentPrice), inr(t.getTriggerPrice()))));
                } else if (AccumulationService.MODE_SIGNAL_GATED.equals(plan.getMode())) {
                    // Unreachable via the API since B-077 (creation is refused), but a row inserted
                    // directly would otherwise be skipped in silence for the life of the plan — which
                    // is the defect itself. Say so rather than dropping it.
                    log.warn("AccumulationReminder: plan {} ({}) is SIGNAL_GATED, a mode that never "
                            + "triggers and is no longer accepted (SPEC §8.2, B-077). Tranche {} will "
                            + "never be reminded; cancel the plan and recreate it as SIP or PRICE_LADDER.",
                            plan.getId(), plan.getSymbol(), t.getTrancheNumber());
                }
            }
        }

        if (sipDue.isEmpty() && ladderHit.isEmpty()) {
            log.debug("AccumulationReminder: no tranches due today");
            return 0;
        }

        String html = tpl.buildEmailTemplate(
                "Accumulation Plan — Action Today",
                "Tranches due today across your active purchase plans",
                renderBody(sipDue, ladderHit));
        mail.sendHtmlEmail("Portfolio — Accumulate today (" + (sipDue.size() + ladderHit.size())
                + " tranche" + (sipDue.size() + ladderHit.size() == 1 ? "" : "s") + ")", html);
        log.info("AccumulationReminder: sent email for {} SIP + {} ladder tranches",
                sipDue.size(), ladderHit.size());
        return sipDue.size() + ladderHit.size();
    }

    private String renderBody(List<DueItem> sipDue, List<DueItem> ladderHit) {
        StringBuilder b = new StringBuilder();

        b.append("<div style=\"background:#e7f3fe;border-left:4px solid #2196f3;"
                + "padding:10px 14px;margin:0 0 12px 0;border-radius:4px;font-size:14px;\">"
                + "<strong>&#128161; What this means:</strong> Your staged-purchase plans have one or more "
                + "chunks (<em>tranches</em>) due today. The app does <strong>not</strong> place any orders "
                + "automatically — review the list below and place each trade manually with your broker, "
                + "then mark the tranche as filled via "
                + "<code>POST /api/portfolio/accumulate/{planId}/tranche/{trancheId}/fill</code>."
                + "</div>");

        if (!sipDue.isEmpty()) {
            b.append(tpl.section("&#128197;", "Scheduled SIP tranches due today",
                    renderTable(sipDue)));
        }
        if (!ladderHit.isEmpty()) {
            b.append(tpl.section("&#128200;", "Buy-on-dips tranches hitting their price target",
                    renderTable(ladderHit)));
        }
        return b.toString();
    }

    private String renderTable(List<DueItem> items) {
        StringBuilder b = new StringBuilder();
        b.append(tpl.tableStart("Stock", "Tranche #", "Target amount",
                "Trigger", "Price now", "Suggested shares"));
        for (DueItem it : items) {
            int shares = it.currentPrice > 0 ? (int) Math.round(it.tranche.getAmount() / it.currentPrice) : 0;
            b.append(tpl.tableRow(
                    "<strong>" + it.plan.getSymbol() + "</strong>",
                    it.tranche.getTrancheNumber() + " of " + it.plan.getTranchesCount(),
                    inr(it.tranche.getAmount()),
                    it.reason,
                    it.currentPrice > 0 ? inr(it.currentPrice) : "—",
                    shares > 0 ? String.valueOf(shares) : "—"));
        }
        b.append(tpl.tableEnd());
        return b.toString();
    }

    private double priceFor(String symbol) {
        return holdingsRepository.findBySymbol(symbol)
                .map(HoldingsEntity::getCurrentPrice)
                .filter(p -> p != null && p > 0)
                .orElse(0.0);
    }

    private String inr(double value) {
        return INR.format(value).replace("INR", "\u20B9").replace("\u00A0", "");
    }

    private record DueItem(AccumulationPlanEntity plan, AccumulationTrancheEntity tranche,
                           double currentPrice, String reason) {}
}
