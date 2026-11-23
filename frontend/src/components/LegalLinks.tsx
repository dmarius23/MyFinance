import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
// Narrow import on purpose — see the note in content/legal/paths.ts about bundle splitting.
import { legalPath } from "../content/legal/paths";

/**
 * Terms & Privacy links, shared by every placement (login screen, firm sidebar, rep PWA) so the
 * wording and the URLs live in one place.
 *
 * `tone` picks the palette and the alignment: "light" centres on a white/grey surface, "dark" sits
 * flush with the sidebar's left-aligned navigation.
 *
 * Both open in a new tab so reading them never costs the user a half-filled form or an upload in
 * progress. `rel="noopener noreferrer"` is required with `target="_blank"`: without `noopener` the
 * opened document can reach back through `window.opener` and redirect the tab it came from.
 */
export function LegalLinks({ tone = "light" }: { tone?: "light" | "dark" }) {
  const { t } = useTranslation();

  const link = (to: string, label: string) => (
    <Link to={to} target="_blank" rel="noopener noreferrer">
      {label}
      {/* Announced by screen readers, which otherwise give no warning that focus will jump tabs. */}
      <span className="sr-only"> ({t("legal.newTab")})</span>
    </Link>
  );

  return (
    <nav className={`legal-links legal-links-${tone}`} aria-label={t("legal.linksLabel")}>
      {link(legalPath.terms, t("legal.terms"))}
      <span aria-hidden="true">·</span>
      {link(legalPath.privacy, t("legal.privacy"))}
    </nav>
  );
}
