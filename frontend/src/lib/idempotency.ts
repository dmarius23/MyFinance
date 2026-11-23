/**
 * Idempotency keys for one composer. The same request (identical payload) keeps the same key while it is
 * retried — so a retry after a lost response is answered with the original message rather than queueing
 * a duplicate — and a changed request gets a fresh key (the server refuses a key reused for a different
 * message). Call `forget` after a confirmed success so a deliberate second send is a new message.
 */
export function createIdempotencyKeys() {
  const keys = new Map<string, string>();
  return {
    keyFor(payload: unknown): string {
      const id = JSON.stringify(payload);
      let key = keys.get(id);
      if (!key) {
        key = crypto.randomUUID();
        keys.set(id, key);
      }
      return key;
    },
    forget(payload: unknown): void {
      keys.delete(JSON.stringify(payload));
    },
  };
}
