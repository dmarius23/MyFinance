#!/usr/bin/env python3
"""Download the webfonts from Google Fonts and self-host them under public/fonts/.

Why self-host: loading fonts from fonts.googleapis.com / fonts.gstatic.com sends every visitor's
IP address to Google before they have even signed in — an international transfer of personal data
that we would otherwise have to declare and justify. Serving the files from our own origin removes
the transfer entirely. See docs/MyFinance-data-privacy-residency-v1.md.

Run from the frontend/ directory when the font list changes:

    python3 scripts/fetch_fonts.py

It rewrites public/fonts/*.woff2 and src/fonts.css. Both are committed, so a build never reaches
out to the network.

Only the `latin` and `latin-ext` subsets are kept: latin-ext carries the Romanian ă, ș and ț, and
the remaining subsets (cyrillic, vietnamese) are dead weight for this product.

Google serves some families as a single variable font reused across every requested weight. Those
are emitted once per subset with a `font-weight: <min> <max>` range instead of one identical copy
per weight.
"""

from __future__ import annotations  # PEP 604 unions in annotations on Python 3.9

import hashlib
import pathlib
import re
import shutil
import urllib.request

# Must stay in sync with the families actually used in src/index.css.
CSS_URL = (
    "https://fonts.googleapis.com/css2"
    "?family=Hanken+Grotesk:wght@400;500;600;700"
    "&family=IBM+Plex+Mono:wght@400;500;600"
    "&display=swap"
)
# Google serves woff2 only to browsers it recognises; a default urllib UA gets legacy TTF.
UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)
KEEP_SUBSETS = {"latin", "latin-ext"}

ROOT = pathlib.Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "public" / "fonts"
CSS_OUT = ROOT / "src" / "fonts.css"


def fetch(url: str, *, as_text: bool) -> bytes | str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read()
    return raw.decode("utf-8") if as_text else raw


def main() -> None:
    css = fetch(CSS_URL, as_text=True)
    blocks = re.findall(r"/\* ([a-z-]+) \*/\s*(@font-face \{.*?\})", css, re.S)
    if not blocks:
        raise SystemExit("could not parse any @font-face blocks — did the Google Fonts CSS change?")

    # (family, subset) -> list of (weight, url, unicode_range)
    groups: dict[tuple[str, str], list[tuple[int, str, str]]] = {}
    for subset, block in blocks:
        if subset not in KEEP_SUBSETS:
            continue
        family = re.search(r"font-family: '([^']+)'", block).group(1)
        weight = int(re.search(r"font-weight: (\d+)", block).group(1))
        url = re.search(r"url\((https://[^)]+)\)", block).group(1)
        urange = re.search(r"unicode-range: ([^;]+);", block).group(1)
        groups.setdefault((family, subset), []).append((weight, url, urange))

    if FONT_DIR.exists():
        shutil.rmtree(FONT_DIR)
    FONT_DIR.mkdir(parents=True)

    rules, written = [], 0
    for (family, subset), entries in sorted(groups.items()):
        entries.sort()
        payloads = {w: fetch(u, as_text=False) for w, u, _ in entries}
        digests = {hashlib.sha256(p).hexdigest() for p in payloads.values()}
        slug = family.lower().replace(" ", "-")
        urange = entries[0][2]

        if len(digests) == 1 and len(entries) > 1:
            # One variable font covering the whole range — store it once.
            name = f"{slug}-variable-{subset}.woff2"
            (FONT_DIR / name).write_bytes(next(iter(payloads.values())))
            written += 1
            weight_decl = f"{entries[0][0]} {entries[-1][0]}"
            rules.append(_rule(family, weight_decl, name, urange))
        else:
            for weight, _, _ in entries:
                name = f"{slug}-{weight}-{subset}.woff2"
                (FONT_DIR / name).write_bytes(payloads[weight])
                written += 1
                rules.append(_rule(family, str(weight), name, urange))

    CSS_OUT.write_text(
        "/* GENERATED FILE — do not edit by hand.\n"
        " * Regenerate with: python3 scripts/fetch_fonts.py\n"
        " *\n"
        " * Self-hosted webfonts. Nothing here requests fonts.googleapis.com or fonts.gstatic.com,\n"
        " * so no visitor IP is disclosed to Google. Subsets: latin + latin-ext (Romanian ă/ș/ț).\n"
        " */\n\n" + "\n\n".join(rules) + "\n",
        encoding="utf-8",
    )
    total = sum(f.stat().st_size for f in FONT_DIR.iterdir())
    print(f"{written} files ({total / 1024:.0f} KiB) -> {FONT_DIR.relative_to(ROOT)}")
    print(f"{len(rules)} @font-face rules -> {CSS_OUT.relative_to(ROOT)}")


def _rule(family: str, weight: str, filename: str, urange: str) -> str:
    return (
        "@font-face {\n"
        f"  font-family: '{family}';\n"
        "  font-style: normal;\n"
        f"  font-weight: {weight};\n"
        "  font-display: swap;\n"
        f"  src: url('/fonts/{filename}') format('woff2');\n"
        f"  unicode-range: {urange};\n"
        "}"
    )


if __name__ == "__main__":
    main()
