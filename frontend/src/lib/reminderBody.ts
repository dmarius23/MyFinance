import type { BankTransaction } from "../api/bank";

const fmt = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

type T = (key: string, opts?: Record<string, unknown>) => string;

/**
 * The document-reminder message body — shared by the email (SendReminderModal) and WhatsApp channels so
 * both read identically. Greeting, then either "send the statement + docs" (no statement yet) or a list of
 * the transactions still missing a document (file-specific), then the portal note + sign-off.
 *
 * The sign-off is "closing, sender, firm". {@link firmName} is the tenant's own name (from `meApi.get()`)
 * and is always the last line, so the client sees which accounting firm is writing — never a hardcoded one.
 */
export function reminderBody(t: T, month: string, hasBankStatement: boolean,
                            missing: BankTransaction[], fromName: string | null,
                            firmName: string | null): string {
  const lines = [t("email.greeting")];
  if (!hasBankStatement) {
    lines.push("", t("email.needStatementAndDocs", { month }), "", t("email.uploadPortal"));
  } else if (missing.length > 0) {
    lines.push("", t("email.needDocsForTxns", { month }));
    for (const tx of missing) {
      lines.push(`• ${tx.txnDate} — ${tx.partnerName ?? "—"} — ${fmt(Math.abs(tx.amount))} RON`);
    }
    lines.push("", t("email.uploadPortal"));
  }
  lines.push("", t("email.signoff"));
  if (fromName) {
    lines.push(fromName);
  }
  if (firmName) {
    lines.push(firmName);
  }
  return lines.join("\n");
}
