package vn.essvn.erpcafe.identity.application;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import vn.essvn.erpcafe.common.persistence.SystemTransactions;
import vn.essvn.erpcafe.identity.persistence.RefreshTokenRepository;

/**
 * Class: RefreshTokenPurger
 * Purpose: Deletes refresh tokens that expired more than a week ago so the table stays bounded.
 */
@Component
public class RefreshTokenPurger {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenPurger.class);
    // kept a while past expiry so a replayed rotated token is still recognised as reuse
    private static final Duration RETENTION = Duration.ofDays(7);

    private final RefreshTokenRepository refreshTokenRepository;
    private final SystemTransactions system;

    public RefreshTokenPurger(RefreshTokenRepository refreshTokenRepository, SystemTransactions system) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.system = system;
    }

    // every company's tokens, and no request to name one
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpired() {
        int deleted = system.run(() -> refreshTokenRepository.deleteByExpiresAtBefore(Instant.now().minus(RETENTION)));
        log.info("Purged {} refresh tokens expired more than {} ago", deleted, RETENTION);
    }
}
