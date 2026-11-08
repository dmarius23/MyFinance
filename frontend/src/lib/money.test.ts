import { describe, expect, it } from "vitest";

import { formatMoney } from "./money";

describe("formatMoney", () => {
  it("omits the suffix for RON so a RON-only client sees no new noise", () => {
    // The regression this guards: every amount on every screen suddenly growing a redundant "RON".
    expect(formatMoney(1190, "RON", "ro")).not.toContain("RON");
    expect(formatMoney(1190, "RON", "en")).not.toContain("RON");
  });

  it("shows the currency when it is not RON, so EUR is never read as RON", () => {
    expect(formatMoney(59.9, "EUR", "en")).toContain("EUR");
    expect(formatMoney(59.9, "EUR", "ro")).toContain("EUR");
  });

  it("always shows two decimals", () => {
    expect(formatMoney(1190, "RON", "en")).toContain(".00");
    expect(formatMoney(59.9, "EUR", "en")).toContain(".90");
  });

  it("renders a missing amount as a dash, never NaN", () => {
    expect(formatMoney(null, "RON", "en")).toBe("—");
    expect(formatMoney(undefined, "EUR", "en")).toBe("—");
    expect(formatMoney("not-a-number", "RON", "en")).toBe("—");
  });

  it("accepts a numeric string, as the API returns", () => {
    expect(formatMoney("59.90", "EUR", "en")).toContain("59.90");
  });

  it("treats a missing currency as RON rather than printing undefined", () => {
    expect(formatMoney(10, "", "en")).not.toContain("undefined");
  });
});
