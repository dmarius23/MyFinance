import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from "react";

/** A tiny app-wide toast system: brief, auto-dismissing status messages in the corner. */
type Tone = "info" | "success" | "error";
type Toast = { id: number; message: string; tone: Tone };
type ToastApi = { toast: (message: string, tone?: Tone, ms?: number) => void };

const ToastContext = createContext<ToastApi | null>(null);

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error("useToast must be used within <ToastProvider>");
  return ctx;
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const idRef = useRef(0);

  const toast = useCallback((message: string, tone: Tone = "info", ms = 4000) => {
    const id = ++idRef.current;
    setToasts((prev) => [...prev, { id, message, tone }]);
    window.setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), ms);
  }, []);

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div style={{ position: "fixed", bottom: 20, right: 20, display: "flex", flexDirection: "column",
        gap: 8, zIndex: 10000, pointerEvents: "none" }}>
        {toasts.map((t) => (
          <div key={t.id} role="status" aria-live="polite" style={{
            background: "var(--chrome-bg, #0c1413)", color: "var(--chrome-text, #e8efed)",
            border: "1px solid var(--border, #2a3a37)", borderLeft: `3px solid ${toneColor(t.tone)}`,
            borderRadius: 8, padding: "10px 14px", fontSize: 13, lineHeight: 1.35,
            minWidth: 220, maxWidth: 380, boxShadow: "0 8px 24px rgba(0,0,0,0.28)",
            animation: "mf-toast-in 160ms ease-out",
          }}>{t.message}</div>
        ))}
      </div>
      <style>{"@keyframes mf-toast-in{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}"}</style>
    </ToastContext.Provider>
  );
}

function toneColor(tone: Tone): string {
  if (tone === "success") return "var(--primary, #16a34a)";
  if (tone === "error") return "var(--danger-fg, #dc2626)";
  return "var(--primary, #3b82f6)";
}
