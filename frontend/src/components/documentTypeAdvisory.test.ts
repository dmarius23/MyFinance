import { describe, expect, it } from "vitest";

import { typeAdvisory } from "./documentTypeAdvisory";

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
