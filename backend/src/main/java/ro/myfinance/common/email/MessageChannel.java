package ro.myfinance.common.email;

/**
 * The delivery channel of a client message recorded in {@code email_history}. The table is shared across
 * channels so the module screens can show both a "last email" and a "last WhatsApp" from one place; the
 * per-channel views filter on this discriminator.
 */
public enum MessageChannel {
    EMAIL,
    WHATSAPP
}
