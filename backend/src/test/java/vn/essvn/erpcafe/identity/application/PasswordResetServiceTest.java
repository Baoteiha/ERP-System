package vn.essvn.erpcafe.identity.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.common.mail.MailSender;
import vn.essvn.erpcafe.common.persistence.SystemTransactions;
import vn.essvn.erpcafe.config.SecurityProperties;
import vn.essvn.erpcafe.identity.persistence.RefreshTokenRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;

/**
 * Class: PasswordResetServiceTest
 * Purpose: Pins when password reset may run, and that a dead token is refused before any password is hashed.
 */
class PasswordResetServiceTest {

    private final SystemTransactions system = mock(SystemTransactions.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private static SecurityProperties reset(boolean enabled, String link) {
        return new SecurityProperties(new SecurityProperties.Jwt(
                List.of(new SecurityProperties.Key("k1", "a-signing-key-of-at-least-32-bytes-long", true)),
                Duration.ofMinutes(15), Duration.ofDays(30)), null, null,
                new SecurityProperties.PasswordReset(enabled, Duration.ofMinutes(30), link), null);
    }

    private static ObjectProvider<MailSender> senders(MailSender... senders) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < senders.length; i++) {
            beans.addBean("sender" + i, senders[i]);
        }
        return beans.getBeanProvider(MailSender.class);
    }

    private PasswordResetService service(boolean enabled, ObjectProvider<MailSender> senders) {
        return service(reset(enabled, "https://erp.example.test/reset-password"), senders);
    }

    private PasswordResetService service(SecurityProperties properties, ObjectProvider<MailSender> senders) {
        return new PasswordResetService(mock(UserRepository.class), mock(RefreshTokenRepository.class),
                passwordEncoder, jdbc, senders, mock(AuthVersions.class), mock(AuthEventLog.class), system,
                properties);
    }

    @Test
    void switchedOn_withNoMailAdapter_refusesToStart() {
        assertThatThrownBy(() -> service(true, senders()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.password-reset.enabled")
                .hasMessageContaining("MailSender");
    }

    @Test
    void switchedOn_withARelativeLink_refusesToStart() {
        assertThatThrownBy(() -> service(reset(true, "/reset-password"), senders(mock(MailSender.class))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_PUBLIC_URL");
    }

    @Test
    void switchedOn_withAMailAdapter_starts() {
        assertThatCode(() -> service(true, senders(mock(MailSender.class)))).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTokenWithNoLiveLink_isRefused_beforeAnyPasswordIsHashed() {
        when(system.run(any(Supplier.class))).thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(system).run(any(Runnable.class));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
        PasswordResetService on = service(true, senders(mock(MailSender.class)));

        assertThatThrownBy(() -> on.confirm("f".repeat(64), "new-pass-1")).isInstanceOf(BusinessRuleException.class);

        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void switchedOff_startsWithoutAMailAdapter_andAnswersNotFound_touchingNothing() {
        PasswordResetService off = service(false, senders());

        assertThatThrownBy(() -> off.request("someone@esscafe.vn")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> off.confirm("a".repeat(64), "new-pass-1"))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(system, jdbc, passwordEncoder);
    }
}
