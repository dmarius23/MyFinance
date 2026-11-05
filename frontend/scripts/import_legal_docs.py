#!/usr/bin/env python3
"""Convert the legal source .docx files into typed content modules under src/content/legal/.

The legal text is authored in Word by the legal reviewer, not in the repo. Transcribing it by hand
would risk silent drift between what was reviewed and what the app shows, so the app content is
GENERATED from the .docx and committed. Re-run this whenever a reviewed version lands:

    python3 scripts/import_legal_docs.py \
        --terms   "~/Downloads/TERMENI SI CONDITII MyFinance_proiect.docx" \
        --privacy "~/Downloads/Politica_confidentialitate_MyFinance_proiect.docx"

Only Romanian is generated: Romanian is the authoritative language of both documents (see
src/content/legal/index.ts for how the EN locale is handled).

Mapping from Word to content blocks:
  Title / first paragraph  -> document title
  Heading1                 -> section heading
  paragraph with a border  -> draft notice (the "DOCUMENT DE LUCRU" banner)
  paragraph with shading   -> callout (the non-authoritative-amounts clause)
  list/numbered paragraph  -> bullet item
  anything else            -> paragraph
"""

import argparse
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
NS = {"w": W[1:-1]}

PLACEHOLDER = re.compile(r"\[(?:DE COMPLETAT|TBD|TODO)[^\]]*\]")


def paragraphs(docx_path):
    """Yield (kind, text) for every non-empty paragraph, in document order."""
    with zipfile.ZipFile(docx_path) as z:
        tree = ET.fromstring(z.read("word/document.xml"))

    for p in tree.iter(W + "p"):
        text = "".join(n.text or "" for n in p.iter(W + "t")).strip()
        if not text:
            continue

        style_el = p.find(".//w:pStyle", NS)
        style = style_el.get(W + "val") if style_el is not None else ""
        has_numbering = p.find(".//w:numPr", NS) is not None
        has_border = p.find(".//w:pBdr", NS) is not None
        shading = p.find(".//w:pPr/w:shd", NS)
        shaded = shading is not None and (shading.get(W + "fill") or "auto") not in ("auto", "FFFFFF")

        if style == "Title":
            kind = "title"
        elif style.startswith("Heading"):
            kind = "heading"
        # The draft banner is bordered in one source document and plain text in the other.
        elif has_border or text.startswith("DOCUMENT DE LUCRU"):
            kind = "notice"
        elif shaded:
            kind = "callout"
        elif has_numbering or style.startswith("List"):
            kind = "bullet"
        else:
            kind = "p"

        yield kind, text


def build_document(docx_path):
    """Fold the flat paragraph stream into {title, subtitle, notice, intro, sections}."""
    items = list(paragraphs(docx_path))
    if not items:
        raise SystemExit(f"{docx_path}: no readable paragraphs — is this a valid .docx?")

    doc = {"title": "", "subtitle": None, "notice": None, "intro": [], "sections": []}
    seen_heading = False

    for kind, text in items:
        if kind == "title" and not doc["title"]:
            doc["title"] = text
            continue
        # Not every source document uses Word's Title style: before the first heading, take the
        # opening paragraph as the title and the one after it as the subtitle.
        if kind == "p" and not seen_heading:
            if not doc["title"]:
                doc["title"] = text
                continue
            if doc["subtitle"] is None:
                doc["subtitle"] = text
                continue
        if kind == "notice" and doc["notice"] is None:
            doc["notice"] = text
            continue
        if kind == "heading":
            seen_heading = True
            doc["sections"].append({"heading": text, "blocks": []})
            continue

        target = doc["sections"][-1]["blocks"] if seen_heading else doc["intro"]
        if kind == "bullet":
            # Collapse runs of consecutive bullets into one list block.
            if target and target[-1]["kind"] == "bullets":
                target[-1]["items"].append(text)
            else:
                target.append({"kind": "bullets", "items": [text]})
        else:
            target.append({"kind": "callout" if kind == "callout" else "p", "text": text})

    if not doc["title"]:
        raise SystemExit(f"{docx_path}: could not determine a document title")
    return doc


def count_placeholders(doc):
    """How many unfilled [DE COMPLETAT] markers remain — surfaced in the UI as a draft warning."""
    blob = json.dumps(doc, ensure_ascii=False)
    return len(PLACEHOLDER.findall(blob))


def render_module(doc, slug, source_name):
    """Emit a TypeScript module. Content is data, never markup — the renderer escapes it."""
    placeholders = count_placeholders(doc)
    payload = json.dumps(doc, ensure_ascii=False, indent=2)
    # Re-indent the JSON body to sit inside the export.
    payload = "\n".join(("  " + line) if line.strip() else line for line in payload.splitlines()).lstrip()

    return f"""// GENERATED FILE — do not edit by hand.
// Source: {source_name}
// Regenerate with: python3 scripts/import_legal_docs.py (see that script's docstring)
//
// Unfilled [DE COMPLETAT] placeholders in this document: {placeholders}
import type {{ LegalDocument }} from "./types";

export const {slug}: LegalDocument = {payload};
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--terms", required=True, help="path to the Terms & Conditions .docx")
    ap.add_argument("--privacy", required=True, help="path to the Privacy Policy .docx")
    ap.add_argument("--out", default=None, help="output dir (default: <repo>/src/content/legal)")
    args = ap.parse_args()

    out_dir = pathlib.Path(args.out) if args.out else pathlib.Path(__file__).resolve().parent.parent / "src" / "content" / "legal"
    out_dir.mkdir(parents=True, exist_ok=True)

    for flag, slug in (("terms", "termsRo"), ("privacy", "privacyRo")):
        src = pathlib.Path(getattr(args, flag)).expanduser()
        if not src.is_file():
            raise SystemExit(f"not found: {src}")
        doc = build_document(src)
        target = out_dir / f"{flag}.ro.ts"
        target.write_text(render_module(doc, slug, src.name), encoding="utf-8")
        print(
            f"{target.relative_to(out_dir.parent.parent.parent)}: "
            f"{len(doc['sections'])} sections, {count_placeholders(doc)} placeholders",
            file=sys.stderr,
        )


if __name__ == "__main__":
    main()
