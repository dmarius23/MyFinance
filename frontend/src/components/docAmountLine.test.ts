import { describe, expect, it } from "vitest";

import { docAmountLine } from "./docAmountLine";

describe("docAmountLine", () => {
  it("labels a EUR invoice EUR, not RON — the regression that put a literal RON on every total", () => {
    const line = docAmountLine(476, "EUR", "2026-09-23", "ro");
    expect(line).toContain("EUR");
    expect(line).not.toContain("RON");
  });

  it("labels a RON invoice RON", () => {
    expect(docAmountLine(1190, "RON", "2026-10-01", "ro")).toContain("RON");
  });

  it("joins the amount and the issue date with a separator", () => {
    expect(docAmountLine(1190, "RON", "2026-10-01", "en")).toBe("1,190.00 RON · 2026-10-01");
  });

  it("falls back to RON when the amount is known but its currency is not, never 'undefined'", () => {
    const line = docAmountLine(1190, null, null, "en");
    expect(line).toBe("1,190.00 RON");
    expect(line).not.toContain("undefined");
  });

  it("shows the date alone for a document with no extracted total", () => {
    expect(docAmountLine(null, null, "2026-10-01", "en")).toBe("2026-10-01");
  });

  it("is empty when there is neither a total nor a date, so no stray separator is rendered", () => {
    expect(docAmountLine(null, null, null, "en")).toBe("");
    expect(docAmountLine(undefined, undefined, undefined, "en")).toBe("");
  });
});
