import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { LegalLinks } from "./LegalLinks";
import { legalPath } from "../content/legal/paths";
import i18n from "../i18n";

function renderLinks(props: Parameters<typeof LegalLinks>[0] = {}) {
  return render(<MemoryRouter><LegalLinks {...props} /></MemoryRouter>);
}

describe("LegalLinks", () => {
  it("links to both legal documents", () => {
    renderLinks();
    // Substring match: each link's accessible name also carries the new-tab warning.
    expect(screen.getByRole("link", { name: new RegExp(i18n.t("legal.terms")) }))
      .toHaveAttribute("href", legalPath.terms);
    expect(screen.getByRole("link", { name: new RegExp(i18n.t("legal.privacy")) }))
      .toHaveAttribute("href", legalPath.privacy);
  });

  it("exposes the group as a labelled navigation landmark", () => {
    renderLinks();
    expect(screen.getByRole("navigation", { name: i18n.t("legal.linksLabel") })).toBeInTheDocument();
  });

  it("defaults to the light tone and honours an explicit dark tone", () => {
    const { unmount } = renderLinks();
    expect(screen.getByRole("navigation")).toHaveClass("legal-links-light");
    unmount();

    renderLinks({ tone: "dark" });
    expect(screen.getByRole("navigation")).toHaveClass("legal-links-dark");
  });

  it("renders the separator as decorative so screen readers skip it", () => {
    const { container } = renderLinks();
    expect(container.querySelector("span[aria-hidden='true']")).toBeInTheDocument();
  });

  describe("opening in a new tab", () => {
    it("opens both documents in a new tab", () => {
      const { container } = renderLinks();
      const links = [...container.querySelectorAll("a")];
      expect(links).toHaveLength(2);
      for (const a of links) expect(a).toHaveAttribute("target", "_blank");
    });

    it("sets rel=noopener on every new-tab link", () => {
      // Without noopener the opened document can reach window.opener and redirect the tab it
      // came from — the app tab, which may hold a signed-in session.
      const { container } = renderLinks();
      for (const a of container.querySelectorAll('a[target="_blank"]')) {
        expect(a.getAttribute("rel") ?? "").toContain("noopener");
      }
    });

    it("tells screen-reader users that focus moves to a new tab", () => {
      renderLinks();
      // The warning is part of each link's accessible name, not a separate element.
      const terms = screen.getByRole("link", {
        name: new RegExp(`${i18n.t("legal.terms")}.*${i18n.t("legal.newTab")}`),
      });
      expect(terms).toBeInTheDocument();
    });
  });
});
