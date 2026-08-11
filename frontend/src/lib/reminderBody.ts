import type { BankTransaction } from "../api/bank";

const fmt = (n: number) => n.toLocaleString("ro-RO", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

type T = (key: string, opts?: Record<string, unknown>) => string;

/**
 * The document-reminder message body — shared by the email (SendReminderModal) and WhatsApp channels so
 * both read identically. Greeting, then either "send the statement + docs" (no statement yet) or a list of
 * the transactions still missing a document (file-specific), then the portal note + sign-off.
 */
export function reminderBody(t: T, month: string, hasBankStatement: boolean,
                            missing: BankTransaction[], fromName: string | null): string {
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
  return lines.join("\n");
}
