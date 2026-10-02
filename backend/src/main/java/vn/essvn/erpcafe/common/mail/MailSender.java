package vn.essvn.erpcafe.common.mail;

/**
 * Class: MailSender
 * Purpose: Sends email on the application's behalf; the port an SMTP or provider adapter implements.
 */
public interface MailSender {

    void send(MailMessage message);
}
