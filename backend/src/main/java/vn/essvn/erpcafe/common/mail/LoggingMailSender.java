package vn.essvn.erpcafe.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Class: LoggingMailSender
 * Purpose: Writes each email to the application log instead of sending it, until a real mail adapter exists.
 *
 * <p>A mailed password-reset link is a credential, so whoever reads this log can reset that password.
 * Replace this adapter with one that sends mail before offering self-service reset to real users.
 */
@Component
@Profile({"dev", "test"})
public class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    @Override
    public void send(MailMessage message) {
        log.info("Mail to {}: {}\n{}", message.to(), message.subject(), message.body());
    }
}
