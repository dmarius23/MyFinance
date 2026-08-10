import { useState } from "react";

/**
 * A lightweight hover tooltip: shows {@code lines} in a styled box that follows the cursor. Uses
 * position:fixed (from the pointer's viewport coordinates) so it is never clipped by a table's
 * horizontal-scroll / overflow container, and appears instantly (unlike the native title attribute).
 * With {@code emphasizeLast}, the final line is set off as a total/summary.
 */
export function InfoTip({ lines, emphasizeLast, children }:
  { lines: string[]; emphasizeLast?: boolean; children: React.ReactNode }) {
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null);
  return (
    <span
      style={{ cursor: "help" }}
      onMouseEnter={(e) => setPos({ x: e.clientX, y: e.clientY })}
      onMouseMove={(e) => setPos({ x: e.clientX, y: e.clientY })}
      onMouseLeave={() => setPos(null)}
    >
      {children}
      {pos && lines.length > 0 && (
        <div
          role="tooltip"
          style={{
            position: "fixed",
            left: Math.min(pos.x + 14, window.innerWidth - 280),
            top: pos.y + 16,
            zIndex: 1000,
            background: "var(--chrome-bg, #1f2a37)",
            color: "var(--chrome-text, #f3f8f7)",
            padding: "8px 11px",
            borderRadius: 8,
            fontSize: 12,
            lineHeight: 1.55,
            maxWidth: 340,
            boxShadow: "0 8px 26px rgba(0,0,0,0.30)",
            pointerEvents: "none",
            display: "grid",
            gap: 1,
          }}
        >
          {lines.map((l, i) => {
            const isLast = emphasizeLast && lines.length > 1 && i === lines.length - 1;
            return (
              <div key={i} style={isLast
                ? { borderTop: "1px solid rgba(255,255,255,0.18)", marginTop: 3, paddingTop: 4, fontWeight: 700 }
                : { overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{l}</div>
            );
          })}
        </div>
      )}
    </span>
  );
}
