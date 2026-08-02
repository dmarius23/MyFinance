package ro.myfinance.access.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.outbox.OutboxHandler;
import ro.myfinance.common.outbox.OutboxMessage;
import ro.myfinance.common.outbox.OutboxWriter;

/**
 * Compensating cleanup for an orphaned auth user: when the local persistence that should follow a
 * {@link UserInviter#invite} fails, the created Supabase auth user must be removed so it isn't orphaned.
 * We can't do that reliably inline (the invite transaction is rolling back, and the delete can itself
 * fail), so we <b>durably schedule</b> it on the transactional outbox — {@link #scheduleDelete} writes the
 * job in its own committed transaction (surviving the rollback), and the worker relay drains it with
 * retries/DLQ via {@link #handle}. Idempotent: deleting an already-absent user is a success.
 */
@Service
public class AuthUserCleanup implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthUserCleanup.class);

    /** Outbox message type + aggregate for auth-user deletions. */
    public static final String TYPE = "DELETE_AUTH_USER";
    public static final String AGGREGATE = "auth_user";

    private final UserInviter inviter;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;

    public AuthUserCleanup(UserInviter inviter, OutboxWriter outbox, ObjectMapper mapper) {
        this.inviter = inviter;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    /** The serialized outbox payload: which external auth user to delete. */
    public record Payload(UUID externalUserId) {
    }

    /**
     * Durably schedule the deletion of an orphaned auth user. Runs in a <b>new</b> transaction so the
     * outbox row commits independently of the failed invite transaction that triggered it. Call from the
     * invite's catch block, then rethrow the original failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void scheduleDelete(UUID externalUserId) {
        outbox.enqueue(AGGREGATE, externalUserId.toString(), TYPE, serialize(new Payload(externalUserId)));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(OutboxMessage message) {
        Payload payload = parse(message);
        inviter.delete(payload.externalUserId()); // idempotent; throws → relay retries
    }

    @Override
    public void onExhausted(OutboxMessage message) {
        log.error("Could not delete orphaned auth user after retries (outbox message {}): {}",
                message.getId(), message.getError());
    }

    private String serialize(Payload payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize auth-user cleanup payload", e);
        }
    }

    private Payload parse(OutboxMessage message) {
        try {
            return mapper.readValue(message.getPayload(), Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed DELETE_AUTH_USER payload for message " + message.getId(), e);
        }
    }
}
