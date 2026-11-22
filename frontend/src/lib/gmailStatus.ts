import type { TFunction } from "i18next";
import type { GmailSyncResult } from "../api/gmail";

/** One-line summary of a finished Gmail sync/backfill for toasts and the post-OAuth banner. */
export function gmailFinishedNote(t: TFunction, r: GmailSyncResult): string {
  const parts = [
    t("gmail.resultSummary", {
      messages: r.messages,
      imported: r.imported,
      needsReview: r.needsReview,
    }),
  ];
  if (r.backfillRequired) {
    parts.push(t("gmail.resultBackfillRequired"));
  }
  return parts.join(" · ");
}
