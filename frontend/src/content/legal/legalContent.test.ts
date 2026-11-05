import { describe, expect, it } from "vitest";
import { resolveLegalDocument } from "./index";
import { legalPath } from "./paths";
import type { LegalBlock, LegalDocId, LegalDocument } from "./types";

const DOC_IDS: LegalDocId[] = ["terms", "privacy"];

function allBlocks(doc: LegalDocument): LegalBlock[] {
  return [...doc.intro, ...doc.sections.flatMap((s) => s.blocks)];
}

describe("resolveLegalDocument", () => {
  it("returns the Romanian document and reports it as translated for 'ro'", () => {
    const { document, translated } = resolveLegalDocument("terms", "ro");
    expect(translated).toBe(true);
    expect(document.title).toMatch(/TERMENI ȘI CONDIȚII/i);
  });

  it("falls back to the Romanian original for a language with no version", () => {
    const { document, translated } = resolveLegalDocument("terms", "en");
    // The fallback must be flagged, so the page can explain which version governs.
    expect(translated).toBe(false);
    expect(document).toEqual(resolveLegalDocument("terms", "ro").document);
  });

  it.each(["en-GB", "RO", "ro-RO", "de"])("handles the region-tagged / cased locale %s", (lang) => {
    const { document } = resolveLegalDocument("privacy", lang);
    expect(document.title).toBe(resolveLegalDocument("privacy", "ro").document.title);
  });

  it("resolves both documents to distinct content", () => {
    const terms = resolveLegalDocument("terms", "ro").document;
    const privacy = resolveLegalDocument("privacy", "ro").document;
    expect(terms.title).not.toBe(privacy.title);
  });
});

describe("legal content integrity", () => {
  it.each(DOC_IDS)("%s has a title and sections", (id) => {
    const { document } = resolveLegalDocument(id, "ro");
    expect(document.title.trim().length).toBeGreaterThan(0);
    expect(document.sections.length).toBeGreaterThan(5);
  });

  it.each(DOC_IDS)("%s has no empty section or block", (id) => {
    const { document } = resolveLegalDocument(id, "ro");
    for (const section of document.sections) {
      expect(section.heading.trim()).not.toBe("");
      expect(section.blocks.length).toBeGreaterThan(0);
    }
    for (const block of allBlocks(document)) {
      if (block.kind === "bullets") {
        expect(block.items.length).toBeGreaterThan(0);
        for (const item of block.items) expect(item.trim()).not.toBe("");
      } else {
        expect(block.text.trim()).not.toBe("");
      }
    }
  });

  it.each(DOC_IDS)("%s carries no leftover FIXTER24 branding in its body", (id) => {
    // These documents were adapted from another product's policy. The privacy policy's own draft
    // banner still cites that provenance, which is expected; the operative text must not.
    const { document } = resolveLegalDocument(id, "ro");
    const body = allBlocks(document)
      .map((b) => (b.kind === "bullets" ? b.items.join(" ") : b.text))
      .join("\n");
    expect(body).not.toMatch(/FIXTER24/i);
  });

  /**
   * Publication-safety invariant. A document with unfilled [DE COMPLETAT] markers is not
   * publishable, so it must still carry its draft banner — otherwise draft text would render as
   * if it were final. Equally, once the banner is removed the placeholders must all be gone.
   */
  it.each(DOC_IDS)("%s warns the reader whenever placeholders remain", (id) => {
    const { document, placeholders } = resolveLegalDocument(id, "ro");
    if (placeholders > 0) {
      expect(document.notice, `${id} still has ${placeholders} placeholders but no draft notice`)
        .toBeTruthy();
    } else {
      expect(document.notice).toBeNull();
    }
  });
});

describe("legalPath", () => {
  it("maps every document id to a distinct absolute route", () => {
    const paths = DOC_IDS.map((id) => legalPath[id]);
    for (const p of paths) expect(p.startsWith("/")).toBe(true);
    expect(new Set(paths).size).toBe(paths.length);
  });
});
