import { describe, expect, it } from "vitest";

import { formatMoney } from "./money";

describe("formatMoney", () => {
  it("shows the suffix for RON too, so a bare number never means 'currency unknown'", () => {
    // The regression this guards: suppressing RON again, which makes an extracted RON amount
    // indistinguishable from one whose currency was never extracted at all.
    expect(formatMoney(1190, "RON", "ro")).toContain("RON");
    expect(formatMoney(1190, "RON", "en")).toContain("RON");
  });

  it("puts the code after the amount, separated by one space", () => {
    expect(formatMoney(1190, "RON", "en")).toBe("1,190.00 RON");
    expect(formatMoney(59.9, "EUR", "en")).toBe("59.90 EUR");
  });

  it("upper-cases a lower-case currency rather than printing it raw", () => {
    expect(formatMoney(10, "eur", "en")).toBe("10.00 EUR");
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
    expect(formatMoney(10, "", "en")).toBe("10.00 RON");
  });

  // Formatting contract for currencies that DocumentStatus and InvoicePaymentsResponse now carry.
  // These tests exercise the formatMoney function only — component rendering is out of scope here.
  describe("formatMoney contract for non-RON currencies", () => {
    it("EUR from DocumentStatus.currency renders with EUR suffix — was bare before the gap fix", () => {
      // Simulates: formatMoney(st.total, st.currency, lang) where st.currency comes from
      // the new DocumentStatus.currency field populated from Invoice.getCurrency().
      const eurDocStatusTotal = 1190.5;
      const eurDocStatusCurrency = "EUR"; // what DocumentStatus.currency now returns for a EUR invoice
      expect(formatMoney(eurDocStatusTotal, eurDocStatusCurrency, "en")).toContain("EUR");
      expect(formatMoney(eurDocStatusTotal, eurDocStatusCurrency, "ro")).toContain("EUR");
    });

    it("EUR from InvoicePayments.currency renders with EUR suffix on invoice totals", () => {
      // Simulates: formatMoney(inv.totalAmount, inv.currency, lang) in InvoicePaymentsModal
      // where inv.currency comes from the new InvoicePaymentsResponse.currency field.
      const eurInvoiceTotal = 476.0;
      const eurInvoiceCurrency = "EUR";
      expect(formatMoney(eurInvoiceTotal, eurInvoiceCurrency, "en")).toContain("EUR");
    });

    it("transaction currency from InvoicePayment.txnCurrency renders on payment rows", () => {
      // Simulates: formatMoney(p.allocatedAmount, p.txnCurrency, lang) in InvoicePaymentsModal
      // where p.txnCurrency comes from the new PaymentResponse.txnCurrency field.
      const eurTxnCurrency = "EUR";
      expect(formatMoney(300, eurTxnCurrency, "en")).toContain("EUR");
    });

    it("RON invoice total renders its code too, so EUR and RON rows read the same way", () => {
      const ronInvoiceCurrency = "RON";
      expect(formatMoney(1190.5, ronInvoiceCurrency, "ro")).toContain("RON");
    });
  });
});
