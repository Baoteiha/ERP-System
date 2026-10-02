package vn.essvn.erpcafe.identity.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.common.mail.MailMessage;
import vn.essvn.erpcafe.common.mail.MailSender;
import vn.essvn.erpcafe.common.persistence.SystemTransactions;
import vn.essvn.erpcafe.config.SecurityProperties;
import vn.essvn.erpcafe.identity.domain.AuthEventType;
import vn.essvn.erpcafe.identity.domain.RefreshToken;
import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.persistence.RefreshTokenRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.identity.security.Sha256;

/**
 * Class: PasswordResetService
 * Purpose: Lets a signed-out user set a new password through a single-use link mailed to their account's email.
 */
@Service
public class PasswordResetService {

    /** Open links one account may have; past it a request mails nothing, so a flood fills no table or inbox. */
    static final int MAX_OPEN_LINKS = 3;
    private static final String INVALID_LINK = "This password reset link is invalid, already used or expired";
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final MailSender mailSender;
    private final AuthVersions authVersions;
    private final AuthEventLog events;
    // Runs as the system, on the schema owner's connection: the user is signed out, so no company is in force
    // until the email or the token is looked up. Only a token's SHA-256 is stored.
    private final SystemTransactions system;
    private final boolean enabled;
    private final Duration ttl;
    private final String link;

    PasswordResetService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder, JdbcTemplate jdbc, ObjectProvider<MailSender> mailSenders,
            AuthVersions authVersions,
            AuthEventLog events, SystemTransactions system, SecurityProperties properties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.mailSender = mailSenders.getIfAvailable();
        this.authVersions = authVersions;
        this.events = events;
        this.system = system;
        this.enabled = properties.passwordReset().enabled();
        this.ttl = properties.passwordReset().ttl();
        this.link = properties.passwordReset().link();
        // switched on, it must be able to mail a link a user can open: refuse to start otherwise
        if (enabled && mailSender == null) {
            throw new IllegalStateException("app.security.password-reset.enabled is true but no MailSender adapter"
                    + " is configured (the logging one serves only the dev and test profiles): plug in a real"
                    + " adapter, or set app.security.password-reset.enabled=false");
        }
        if (enabled && !link.startsWith("https://") && !link.startsWith("http://")) {
            throw new IllegalStateException("app.security.password-reset.link must be an absolute URL of the"
                    + " frontend's reset page (set APP_PUBLIC_URL in prod), not: " + link);
        }
    }

    /**
     * Mails a reset link when the email is an active account's. Says nothing either way, and a mail that
     * fails to send is only logged, so the answer never reveals which emails have an account.
     */
    public void request(String rawEmail) {
        requireEnabled();
        String email = User.normalizeEmail(rawEmail);
        Optional<String> token = system.run(() -> userRepository.findByEmail(email)
                .filter(User::isActive)
                .filter(user -> openLinks(user.getId()) < MAX_OPEN_LINKS)
                .map(this::issue));
        token.ifPresent(raw -> {
            try {
                mailSender.send(resetMail(email, raw));
            } catch (RuntimeException ex) {
                log.error("Could not mail a password reset link", ex);
            }
        });
    }

    /**
     * Sets the password of the account the token was mailed for. The token works once, before it expires;
     * the account's other links close with it ({@link AuthVersions}), and its sessions and access tokens end.
     */
    public void confirm(String rawToken, String newPassword) {
        requireEnabled();
        String tokenHash = Sha256.hex(rawToken);
        // The endpoint is public: a token with no live link is refused before the password costs a hash.
        // The claim below still decides between confirmations racing for one live link.
        boolean live = system.run(() -> jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM password_reset_token"
                + " WHERE token_hash = ? AND used_at IS NULL AND expires_at > now())", Boolean.class, tokenHash));
        if (!live) {
            throw new BusinessRuleException(INVALID_LINK);
        }
        // hashed between the transactions, so neither holds an owner connection while it runs
        String passwordHash = passwordEncoder.encode(newPassword);
        system.run(() -> {
            // one statement claims the token: of two confirmations at once, the second finds it used
            UUID userId = jdbc.query("UPDATE password_reset_token SET used_at = now()"
                    + " WHERE token_hash = ? AND used_at IS NULL AND expires_at > now() RETURNING user_id",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tokenHash);
            if (userId == null) {
                throw new BusinessRuleException(INVALID_LINK);
            }
            User user = userRepository.findById(userId).orElseThrow(() -> new BusinessRuleException(INVALID_LINK));
            if (!user.isActive()) {
                throw new BusinessRuleException("Account is disabled");
            }
            user.setPasswordHash(passwordHash);
            refreshTokenRepository.findByUserIdAndRevokedFalse(userId).forEach(RefreshToken::revoke);
            authVersions.bump(user.getCompanyId(), List.of(userId));
            events.record(AuthEventType.PASSWORD_RESET, user.getCompanyId(), userId, Map.of("via", "EMAIL"));
        });
    }

    // every company's tokens, and no request to name one
    @Scheduled(cron = "0 45 3 * * *")
    public void purgeExpired() {
        int deleted = system.run(() -> jdbc.update(
                "DELETE FROM password_reset_token WHERE expires_at < now() - interval '1 day'"));
        log.info("Purged {} password reset tokens expired over a day ago", deleted);
    }

    // switched off, as in prod until a real mail adapter exists: the endpoints are as good as absent
    private void requireEnabled() {
        if (!enabled) {
            throw new ResourceNotFoundException("Password reset is not available");
        }
    }

    private int openLinks(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM password_reset_token"
                + " WHERE user_id = ? AND used_at IS NULL AND expires_at > now()", Integer.class, userId);
    }

    private String issue(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = HexFormat.of().formatHex(bytes);
        jdbc.update("INSERT INTO password_reset_token (id, user_id, token_hash, created_at, expires_at)"
                + " VALUES (?, ?, ?, now(), now() + ?::interval)",
                UUID.randomUUID(), user.getId(), Sha256.hex(raw), ttl.toSeconds() + " seconds");
        return raw;
    }

    private MailMessage resetMail(String email, String token) {
        return new MailMessage(email, "Reset your ERP-Cafe password", """
                Someone asked to reset the password of your ERP-Cafe account. Open this link within %d minutes \
                to choose a new one; it works once:

                %s?token=%s

                If it was not you, ignore this email: your password stays as it is.
                """.formatted(ttl.toMinutes(), link, token));
    }
}
