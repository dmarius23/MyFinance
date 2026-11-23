import { Fragment, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import i18n from "../../i18n";
import { legalPath, resolveLegalDocument, type LegalBlock, type LegalDocId } from "../../content/legal";

/**
 * Unfilled source placeholders, e.g. "[DE COMPLETAT]". The capture group makes `split` return the
 * markers alongside the surrounding text.
 *
 * Matching uses a separate anchored, non-global pattern. Sharing one /g regex between the split
 * and the test would still work today — `split` always alternates text and marker, so the
 * non-matching part in between resets `lastIndex` — but that is an accident of the input shape
 * rather than a property worth depending on.
 */
const PLACEHOLDER_SPLIT = /(\[(?:DE COMPLETAT|TBD|TODO)[^\]]*\])/;
const IS_PLACEHOLDER = /^\[(?:DE COMPLETAT|TBD|TODO)[^\]]*\]$/;

/**
 * Render legal copy as text, highlighting any unfilled placeholder so a draft can never be
 * mistaken for finished text. Content comes from a typed data module, so there is no HTML to
 * inject here — splitting on the placeholder is the only transformation applied.
 */
function LegalText({ text }: { text: string }): ReactNode {
  return text.split(PLACEHOLDER_SPLIT).map((part, i) =>
    IS_PLACEHOLDER.test(part)
      ? <mark key={i} className="legal-todo">{part}</mark>
      : <Fragment key={i}>{part}</Fragment>,
  );
}

function Block({ block }: { block: LegalBlock }) {
  if (block.kind === "bullets") {
    return (
      <ul className="legal-list">
        {block.items.map((item, i) => <li key={i}><LegalText text={item} /></li>)}
      </ul>
    );
  }
  if (block.kind === "callout") {
    return <p className="legal-callout"><LegalText text={block.text} /></p>;
  }
  return <p><LegalText text={block.text} /></p>;
}

/** Stable, readable anchor for a section heading ("10. Extragerea…" → "art-10"). */
function anchorFor(heading: string, index: number): string {
  const num = heading.match(/^(\d+)\./);
  return num ? `art-${num[1]}` : `sec-${index + 1}`;
}

/**
 * Terms & Conditions / Privacy Policy, rendered from the generated content modules.
 *
 * Deliberately a standalone full-page layout rather than a child of FirmLayout or RepHome: the
 * routes must stay public (readable before sign-in), and duplicating them inside each guarded
 * shell would mean three copies of the same route. Continuity comes from the back control, which
 * returns to wherever the reader came from.
 */
export function LegalPage({ doc }: { doc: LegalDocId }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const lang = i18n.language;
  const { document: legalDoc, translated } = resolveLegalDocument(doc, lang);

  // history.length > 1 means we were navigated to from inside the app, so going back is meaningful;
  // on a cold load (shared link, bookmark) fall back to the role-aware home.
  const canGoBack = window.history.length > 1;

  return (
    <div className="legal-page">
      <header className="legal-bar">
        {canGoBack ? (
          <button type="button" className="legal-back" onClick={() => navigate(-1)}>
            ← {t("common.back")}
          </button>
        ) : (
          <Link className="legal-back" to="/">← {t("legal.backHome")}</Link>
        )}
        <div className="legal-bar-brand">MyFinance</div>
        <div style={{ flex: 1 }} />
        <button
          type="button"
          className="legal-lang"
          onClick={() => void i18n.changeLanguage(lang.startsWith("ro") ? "en" : "ro")}
          aria-label={t("legal.switchLanguage")}
        >
          {lang.startsWith("ro") ? <span><b>RO</b> / EN</span> : <span>RO / <b>EN</b></span>}
        </button>
      </header>

      <main className="legal-main">
        <article className="legal-doc">
          <h1>{legalDoc.title}</h1>
          {legalDoc.subtitle && <p className="legal-subtitle">{legalDoc.subtitle}</p>}

          {!translated && (
            <p className="legal-banner legal-banner-info">{t("legal.onlyRomanian")}</p>
          )}
          {legalDoc.notice && (
            <p className="legal-banner legal-banner-warn" role="note">
              <LegalText text={legalDoc.notice} />
            </p>
          )}

          {legalDoc.intro.map((block, i) => <Block key={i} block={block} />)}

          {legalDoc.sections.length > 1 && (
            <nav className="legal-toc" aria-label={t("legal.contents")}>
              <h2>{t("legal.contents")}</h2>
              {/* Unnumbered: the section headings already carry their own "Art. n." numbering. */}
              <ul>
                {legalDoc.sections.map((s, i) => (
                  <li key={i}><a href={`#${anchorFor(s.heading, i)}`}>{s.heading}</a></li>
                ))}
              </ul>
            </nav>
          )}

          {legalDoc.sections.map((section, i) => {
            const id = anchorFor(section.heading, i);
            return (
              <section key={id} id={id} className="legal-section">
                <h2>{section.heading}</h2>
                {section.blocks.map((block, j) => <Block key={j} block={block} />)}
              </section>
            );
          })}

          <p className="legal-footnote">
            {t("legal.otherDocument")}{" "}
            {doc === "terms"
              ? <Link to={legalPath.privacy}>{t("legal.privacy")}</Link>
              : <Link to={legalPath.terms}>{t("legal.terms")}</Link>}
          </p>
        </article>
      </main>
    </div>
  );
}
