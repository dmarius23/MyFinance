import type { LegalDocId, LegalDocument } from "./types";
import { termsRo } from "./terms.ro";
import { privacyRo } from "./privacy.ro";

export type { LegalBlock, LegalDocId, LegalDocument, LegalSection } from "./types";
export { legalPath } from "./paths";

/**
 * Both legal documents exist only in Romanian, and Romanian is their authoritative language:
 * an unofficial translation would raise the question of which version governs. So the EN locale
 * shows the Romanian text with a banner explaining that (`legal.onlyRomanian`), rather than a
 * machine translation of a contract.
 *
 * When a reviewed English version exists, add `terms.en.ts` / `privacy.en.ts`, register them in
 * DOCUMENTS below, and `translated` turns true on its own — no change needed at the call site.
 */
const DOCUMENTS: Record<LegalDocId, Partial<Record<string, LegalDocument>>> = {
  terms: { ro: termsRo },
  privacy: { ro: privacyRo },
};

export type ResolvedLegalDocument = {
  document: LegalDocument;
  /** False when falling back to the Romanian original because the UI language has no version. */
  translated: boolean;
  /** How many `[DE COMPLETAT]` markers remain — non-zero means the text is not publishable. */
  placeholders: number;
};

const PLACEHOLDER = /\[(?:DE COMPLETAT|TBD|TODO)[^\]]*\]/g;

function countPlaceholders(doc: LegalDocument): number {
  const texts: string[] = [doc.title, doc.subtitle ?? "", doc.notice ?? ""];
  const walk = (blocks: LegalDocument["intro"]) => {
    for (const b of blocks) {
      if (b.kind === "bullets") texts.push(...b.items);
      else texts.push(b.text);
    }
  };
  walk(doc.intro);
  for (const s of doc.sections) {
    texts.push(s.heading);
    walk(s.blocks);
  }
  return texts.join("\n").match(PLACEHOLDER)?.length ?? 0;
}

/**
 * Resolve a legal document for the active UI language, falling back to the Romanian original.
 * `lang` may be a region-tagged tag such as "en-GB", so only the leading subtag is considered.
 */
export function resolveLegalDocument(id: LegalDocId, lang: string): ResolvedLegalDocument {
  const base = lang.split("-")[0].toLowerCase();
  const byLang = DOCUMENTS[id];
  const match = byLang[base];
  const document = match ?? byLang.ro;
  if (!document) throw new Error(`no Romanian source registered for legal document "${id}"`);
  return { document, translated: match !== undefined, placeholders: countPlaceholders(document) };
}
