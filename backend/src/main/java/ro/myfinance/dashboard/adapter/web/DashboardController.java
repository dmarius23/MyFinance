package ro.myfinance.dashboard.adapter.web;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.dashboard.application.DashboardService;
import ro.myfinance.dashboard.application.DashboardService.StatusFilter;
import ro.myfinance.dashboard.domain.DashboardView;

/** MOD-11 — the monthly companies overview (tiles + per-company status rows). Firm staff only. */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/dashboard")
    public DashboardView get(
            @RequestParam("period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period,
            @RequestParam(value = "responsible", required = false) UUID responsible,
            @RequestParam(value = "status", required = false) StatusFilter status) {
        return dashboard.build(period, responsible, status);
    }
}
