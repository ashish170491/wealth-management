package com.example.trading.portfolio;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * Seeds a default {@link PortfolioProfileEntity} at startup if no active profile exists.
 * Idempotent: does nothing once a profile is present. See SPEC.md §5.
 */
@Component
@DependsOn("entityManagerFactory")
@RequiredArgsConstructor
@Slf4j
public class PortfolioBootstrapper {

    private final PortfolioProfileRepository profileRepository;
    private final PortfolioConfig config;

    @PostConstruct
    public void seedDefaultProfile() {
        if (!config.isEnabled() || !config.isSeedOnStartup()) {
            log.debug("Portfolio: bootstrap disabled by config");
            return;
        }
        if (profileRepository.findByActiveTrue().isPresent()) {
            log.debug("Portfolio: active profile already exists, skipping seed");
            return;
        }
        PortfolioProfileEntity p = PortfolioProfileEntity.builder()
                .name(config.getDefaultProfileName())
                .description("Seeded at first startup. Add target weights via PUT /api/portfolio/profile.")
                .active(true)
                .toleranceAbsolutePp(config.getDefaultTolerancePp())
                .toleranceRelative(config.getDefaultRelativeTolerance())
                .build();
        profileRepository.save(p);
        log.info("Portfolio: seeded default profile '{}' (tolerance ±{}pp or ±{}%)",
                p.getName(), p.getToleranceAbsolutePp(), p.getToleranceRelative() * 100);
    }
}
