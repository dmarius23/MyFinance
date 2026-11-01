package ro.myfinance.access.domain;

/**
 * Lifecycle of a user's access. {@code PENDING_INVITE} and {@code INVITED} both mean "has not signed in
 * yet", but they differ in whether we have actually emailed them: a rep provisioned by bulk CSV import is
 * {@code PENDING_INVITE} (the invite is deliberately deferred — the identity provider rate-limits invite
 * emails), and becomes {@code INVITED} only once "Send invite" has delivered the set-password link. Without
 * that distinction the accountant cannot tell who is still waiting to be contacted.
 */
public enum UserStatus {
    ACTIVE,
    INACTIVE,
    PENDING_INVITE,
    INVITED
}
