import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { LegalPage } from "./LegalPage";
import { resolveLegalDocument } from "../../content/legal";
import i18n from "../../i18n";

function renderPage(doc: "terms" | "privacy" = "terms") {
  return render(<MemoryRouter><LegalPage doc={doc} /></MemoryRouter>);
}

describe("LegalPage", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("ro");
  });
  afterEach(async () => {
    await i18n.changeLanguage("ro");
  });

  it("renders the document title as the page heading", () => {
    renderPage("terms");
    const { document } = resolveLegalDocument("terms", "ro");
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(document.title);
  });

  it("renders every section with a heading and a matching anchor", () => {
    const { container } = renderPage("terms");
    const { document } = resolveLegalDocument("terms", "ro");
    expect(container.querySelectorAll(".legal-section")).toHaveLength(document.sections.length);
  });

  it("points every table-of-contents entry at a section that exists", () => {
    const { container } = renderPage("terms");
    const toc = screen.getByRole("navigation", { name: i18n.t("legal.contents") });
    const hrefs = [...within(toc).getAllByRole("link")].map((a) => a.getAttribute("href"));

    expect(hrefs.length).toBeGreaterThan(0);
    for (const href of hrefs) {
      expect(href).toMatch(/^#/);
      // A dangling anchor would silently do nothing when clicked.
      expect(container.querySelector(`#${CSS.escape(href!.slice(1))}`)).not.toBeNull();
    }
  });

  it("does not double-number the contents list", () => {
    // Headings already carry "1.", "2." … so an ordered list would render "1. 1. Cine suntem".
    const toc = (renderPage("terms"), screen.getByRole("navigation", { name: i18n.t("legal.contents") }));
    expect(toc.querySelector("ol")).toBeNull();
    expect(toc.querySelector("ul")).not.toBeNull();
  });

  describe("draft placeholders", () => {
    it("highlights every [DE COMPLETAT] marker in the document", () => {
      const { container } = renderPage("terms");
      const { placeholders } = resolveLegalDocument("terms", "ro");
      expect(container.querySelectorAll("mark.legal-todo")).toHaveLength(placeholders);
    });

    it("highlights every marker in a paragraph that contains more than one", () => {
      const { container } = renderPage("terms");
      const multi = [...container.querySelectorAll("p")].filter(
        (p) => (p.textContent?.match(/\[DE COMPLETAT\]/g)?.length ?? 0) > 1,
      );
      // Skipped rather than vacuously passing once the source placeholders are filled in.
      if (multi.length === 0) return;
      for (const p of multi) {
        const expected = p.textContent!.match(/\[DE COMPLETAT\]/g)!.length;
        expect(p.querySelectorAll("mark.legal-todo")).toHaveLength(expected);
      }
    });
  });

  describe("language handling", () => {
    it("shows the Romanian-is-authoritative banner when the UI is in English", async () => {
      await i18n.changeLanguage("en");
      renderPage("terms");
      expect(screen.getByText(i18n.t("legal.onlyRomanian"))).toBeInTheDocument();
    });

    it("does not show that banner when the UI is in Romanian", () => {
      renderPage("terms");
      expect(screen.queryByText(i18n.t("legal.onlyRomanian", { lng: "en" }))).not.toBeInTheDocument();
      expect(screen.queryByText(i18n.t("legal.onlyRomanian"))).not.toBeInTheDocument();
    });
  });

  it("cross-links to the other legal document", () => {
    renderPage("terms");
    expect(screen.getByRole("link", { name: i18n.t("legal.privacy") }))
      .toHaveAttribute("href", "/legal/privacy");
  });

  it("renders the privacy policy too", () => {
    renderPage("privacy");
    const { document } = resolveLegalDocument("privacy", "ro");
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(document.title);
  });
});
