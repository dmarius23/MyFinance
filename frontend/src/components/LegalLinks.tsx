import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
// Narrow import on purpose — see the note in content/legal/paths.ts about bundle splitting.
import { legalPath } from "../content/legal/paths";

/**
 * Terms & Privacy links, shared by every placement (login screen, firm sidebar, rep PWA) so the
 * wording and the URLs live in one place.
 *
 * `tone` picks the palette: "light" on a white/grey surface, "dark" on the app chrome.
 */
export function LegalLinks({ tone = "light" }: { tone?: "light" | "dark" }) {
  const { t } = useTranslation();
  return (
    <nav className={`legal-links legal-links-${tone}`} aria-label={t("legal.linksLabel")}>
      <Link to={legalPath.terms}>{t("legal.terms")}</Link>
      <span aria-hidden="true">·</span>
      <Link to={legalPath.privacy}>{t("legal.privacy")}</Link>
    </nav>
  );
}
