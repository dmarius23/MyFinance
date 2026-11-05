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
    expect(screen.getByRole("link", { name: i18n.t("legal.terms") }))
      .toHaveAttribute("href", legalPath.terms);
    expect(screen.getByRole("link", { name: i18n.t("legal.privacy") }))
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
});
