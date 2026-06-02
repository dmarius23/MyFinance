package ro.myfinance.common.security;

/**
 * Platform roles. {@code SUPER_ADMIN} is cross-tenant (platform ops, no tenant-data
 * impersonation in MVP); the rest are scoped to a single tenant. {@code REPRESENTATIVE}
 * is additionally constrained to one company.
 */
public enum Role {
    SUPER_ADMIN,
    TENANT_ADMIN,
    EMPLOYEE,
    REPRESENTATIVE;

    /** Spring Security authority form, e.g. {@code ROLE_TENANT_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
