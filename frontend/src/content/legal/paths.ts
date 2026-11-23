import type { LegalDocId } from "./types";

/**
 * Route paths for the legal documents — the single place these URLs are spelled out.
 *
 * Deliberately its own module, separate from ./index: the link components live in the app shell,
 * and importing them from ./index would drag both (multi-thousand-word) document modules into the
 * main bundle, defeating the lazy route in App.tsx. Keep this file free of content imports.
 */
export const legalPath: Record<LegalDocId, string> = {
  terms: "/legal/terms",
  privacy: "/legal/privacy",
};
