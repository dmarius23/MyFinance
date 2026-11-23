import { describe, expect, it } from "vitest";

import { isRecentUpload, RECENT_UPLOAD_WINDOW_MS, typeAdvisory } from "./documentTypeAdvisory";

describe("typeAdvisory", () => {
  it("asks for classification on an unrecognised document", () => {
    // Drive now imports what the classifier could not place instead of refusing it, so this is a normal
    // intake state and must read as "classify me", not as an error.
    expect(typeAdvisory("UNCLASSIFIED")).toBe("needsType");
  });

  it("shows nothing for the types this screen is about", () => {
    expect(typeAdvisory("BANK_STATEMENT")).toBeNull();
    expect(typeAdvisory("INVOICE")).toBeNull();
    expect(typeAdvisory("RECEIPT")).toBeNull();
  });

  it("still flags a type that does not belong on this screen", () => {
    expect(typeAdvisory("PAYROLL")).toBe("wrongType");
    expect(typeAdvisory("DECLARATION")).toBe("wrongType");
    expect(typeAdvisory("TRIAL_BALANCE")).toBe("wrongType");
  });

  it("badges a document uploaded just now, and stops once the window passes", () => {
    const now = Date.parse("2026-09-15T12:00:00Z");
    const at = (msAgo: number) => new Date(now - msAgo).toISOString();
    expect(isRecentUpload(at(0), now)).toBe(true);
    expect(isRecentUpload(at(5_000), now)).toBe(true);
    expect(isRecentUpload(at(RECENT_UPLOAD_WINDOW_MS - 1), now)).toBe(true);
    expect(isRecentUpload(at(RECENT_UPLOAD_WINDOW_MS + 1), now)).toBe(false);
    expect(isRecentUpload(at(24 * 60 * 60 * 1000), now)).toBe(false);
  });

  it("does not badge everything when the server clock runs ahead, or on a bad date", () => {
    const now = Date.parse("2026-09-15T12:00:00Z");
    // A future timestamp (clock skew) must not count as recent.
    expect(isRecentUpload(new Date(now + 60_000).toISOString(), now)).toBe(false);
    expect(isRecentUpload("not-a-date", now)).toBe(false);
    expect(isRecentUpload(null, now)).toBe(false);
    expect(isRecentUpload(undefined, now)).toBe(false);
  });

  it("never reports both advisories for the same document", () => {
    const types = [
      "BANK_STATEMENT", "INVOICE", "RECEIPT",
      "TRIAL_BALANCE", "DECLARATION", "PAYROLL", "UNCLASSIFIED",
    ];
    for (const t of types) {
      const a = typeAdvisory(t);
      expect(["needsType", "wrongType", null]).toContain(a);
    }
  });
});
