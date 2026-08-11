/**
 * A "📎 label: file1, file2" suffix appended to a WhatsApp body to note the documents the email attaches
 * — the text channel can't carry the files, so we at least name them. Empty when there are none.
 */
export function attachmentsNote(label: string, files: string[]): string {
  const clean = files.filter(Boolean);
  return clean.length ? `\n\n📎 ${label}: ${clean.join(", ")}` : "";
}
