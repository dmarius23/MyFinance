/**
 * Shape of a legal document (Terms & Conditions, Privacy Policy).
 *
 * Content is structured DATA, never HTML: the renderer emits React elements, so there is no
 * `dangerouslySetInnerHTML` anywhere in this path (see frontend/CLAUDE.md security invariants).
 * The `*.ro.ts` modules are generated from the source .docx by scripts/import_legal_docs.py.
 */

export type LegalBlock =
  | { kind: "p"; text: string }
  /** Set apart visually — used for clauses that must not be skimmed past. */
  | { kind: "callout"; text: string }
  | { kind: "bullets"; items: string[] };

export type LegalSection = {
  heading: string;
  blocks: LegalBlock[];
};

export type LegalDocument = {
  title: string;
  subtitle: string | null;
  /** The "DOCUMENT DE LUCRU" banner from the source file, when the document is still a draft. */
  notice: string | null;
  /** Blocks appearing before the first numbered section. */
  intro: LegalBlock[];
  sections: LegalSection[];
};

/** Which legal document a route renders. */
export type LegalDocId = "terms" | "privacy";
