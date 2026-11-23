-- Records which attachments of a WhatsApp message Meta already accepted. The template message id was
-- already stored in provider_message_id; together they let an outbox retry after a partial failure resume
-- where it stopped instead of re-sending the template (or an attachment) to the client.
ALTER TABLE whatsapp_message
    ADD COLUMN delivered_attachment_ids text NOT NULL DEFAULT '';
