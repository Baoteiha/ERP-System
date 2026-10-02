package vn.essvn.erpcafe.common.mail;

/**
 * Class: MailMessage
 * Purpose: A plain-text email to one recipient.
 */
public record MailMessage(String to, String subject, String body) {
}
