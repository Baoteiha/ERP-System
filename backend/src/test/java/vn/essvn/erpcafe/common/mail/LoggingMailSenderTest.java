package vn.essvn.erpcafe.common.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Class: LoggingMailSenderTest
 * Purpose: Pins the logging mail adapter to the dev and test profiles, so no other profile writes mailed links to its log.
 */
class LoggingMailSenderTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LoggingMailSender.class);

    @Test
    void devAndTest_logTheirMail() {
        context.withPropertyValues("spring.profiles.active=dev")
                .run(started -> assertThat(started).hasSingleBean(MailSender.class));
        context.withPropertyValues("spring.profiles.active=test")
                .run(started -> assertThat(started).hasSingleBean(MailSender.class));
    }

    @Test
    void anyOtherProfile_hasNoLoggingAdapter() {
        context.withPropertyValues("spring.profiles.active=prod")
                .run(started -> assertThat(started).doesNotHaveBean(MailSender.class));
    }
}
