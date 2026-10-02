package vn.essvn.erpcafe.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import vn.essvn.erpcafe.common.exception.TooManyRequestsException;
import vn.essvn.erpcafe.common.persistence.SystemTransactions;
import vn.essvn.erpcafe.config.SecurityProperties;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: LoginAttemptServiceTest
 * Purpose: Pins the throttle's window in the database: a lapsed window blocks no more; the purge drops only lapsed rows.
 */
class LoginAttemptServiceTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    // A day ahead: the app's own purge runs on the real clock every quarter hour, and must not reach this class's
    // rows mid-test. (The purge this class runs, on its own clock, may drop real rows: that only lowers counts.)
    private static final Instant START = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SystemTransactions system;
    @Autowired private SecurityProperties security;
    @Autowired @Qualifier("ownerDataSource") private DataSource ownerDataSource;

    private Instant now = START;
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private LoginAttemptService throttle() {
        return new LoginAttemptService(jdbc, system, security, clock);
    }

    private String email() {
        return "throttle%d@t.test".formatted(SEQ.incrementAndGet());
    }

    private String address() {
        return "10.37.0.%d".formatted(SEQ.incrementAndGet() % 250 + 1);
    }

    private long rowsStartedAt(Instant start) {
        return new JdbcTemplate(ownerDataSource).queryForObject("SELECT count(*) FROM login_attempt WHERE window_start = ?",
                Long.class, start.atOffset(ZoneOffset.UTC));
    }

    @Test
    void aLapsedWindow_blocksNoMore_andTheNextFailureStartsAFreshOne() {
        LoginAttemptService throttle = throttle();
        String email = email();
        String address = address();
        int limit = security.login().maxFailures();
        for (int i = 0; i < limit; i++) {
            throttle.recordFailure(email, address);
        }
        assertThatThrownBy(() -> throttle.checkNotBlocked(email, address)).isInstanceOf(TooManyRequestsException.class);

        now = now.plus(security.login().window()).plusSeconds(1);
        assertThatCode(() -> throttle.checkNotBlocked(email, address)).doesNotThrowAnyException();

        for (int i = 0; i < limit - 1; i++) {
            throttle.recordFailure(email, address);
        }
        assertThatCode(() -> throttle.checkNotBlocked(email, address)).doesNotThrowAnyException();
        throttle.recordFailure(email, address);
        assertThatThrownBy(() -> throttle.checkNotBlocked(email, address)).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void thePurge_dropsLapsedCounters_andKeepsLiveOnes() {
        LoginAttemptService throttle = throttle();
        now = START.minusSeconds(3600);
        Instant lapsed = now;
        throttle.recordFailure(email(), address());
        now = now.plus(security.login().window()).plusSeconds(1);
        Instant live = now;
        throttle.recordFailure(email(), address());

        throttle.purgeLapsed();

        assertThat(rowsStartedAt(lapsed)).isZero();
        assertThat(rowsStartedAt(live)).isEqualTo(2);
        new JdbcTemplate(ownerDataSource).update("DELETE FROM login_attempt WHERE window_start = ?",
                live.atOffset(ZoneOffset.UTC));
    }
}
