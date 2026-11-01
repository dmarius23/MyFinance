import { api } from "../lib/apiClient";

export const authApi = {
  /**
   * Request a password-reset email. Goes through OUR backend rather than
   * `supabase.auth.resetPasswordForEmail` so the message is delivered by the firm's own email provider,
   * from the firm's sender address — the same way invitations are.
   *
   * Unauthenticated by design (the caller is locked out); `api()` omits the Bearer header when there is no
   * session. Always resolves with 204 whether or not the address exists, so never branch on the result to
   * tell the user anything about the account.
   */
  forgotPassword: (email: string) =>
    api<void>("/api/v1/auth/forgot-password", { method: "POST", body: JSON.stringify({ email }) }),
};
