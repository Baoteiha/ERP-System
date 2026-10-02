package vn.essvn.erpcafe.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Class: LoggingMailSender
 * Purpose: Writes each email to the application log instead of sending it, in the dev and test profiles.
 */
@Component
// A mailed password-reset link is a credential: whoever reads a log holding one can reset that password.
// So no other profile gets this adapter, and one that sends mail must be plugged in to offer reset there.
@Profile({"dev", "test"})
public class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    @Override
    public void send(MailMessage message) {
        log.info("Mail to {}: {}\n{}", message.to(), message.subject(), message.body());
    }
}
