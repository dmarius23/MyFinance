import { portalApi } from "../api/portal";

/**
 * Web Push (VAPID) opt-in for the representative PWA. The browser subscription is created against the
 * server's VAPID public key and posted to the backend, which then delivers a push for every in-app
 * notification. All calls are best-effort and never throw to the caller (they resolve to a state).
 */
export type PushState =
  | "unsupported" // browser has no push/SW support
  | "ios-install" // iOS Safari: push works only once the PWA is installed to the Home Screen
  | "server-off" // backend has no VAPID keys configured
  | "denied" // the user blocked notifications in the browser
  | "disabled" // supported + permitted, not subscribed
  | "enabled"; // subscribed and delivering

interface NavigatorStandalone {
  standalone?: boolean;
}

export function isPushSupported(): boolean {
  return (
    typeof navigator !== "undefined" &&
    "serviceWorker" in navigator &&
    typeof window !== "undefined" &&
    "PushManager" in window &&
    "Notification" in window
  );
}

function isIos(): boolean {
  return /iph|ipad|ipod/i.test(navigator.userAgent);
}

/** True when running as an installed PWA (standalone display), incl. iOS Safari's legacy flag. */
function isStandalone(): boolean {
  const media = window.matchMedia?.("(display-mode: standalone)").matches ?? false;
  const iosStandalone = (navigator as Navigator & NavigatorStandalone).standalone === true;
  return media || iosStandalone;
}

function urlBase64ToUint8Array(base64String: string): Uint8Array {
  const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(base64);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) out[i] = raw.charCodeAt(i);
  return out;
}

/** Current opt-in state, without prompting. */
export async function currentPushState(): Promise<PushState> {
  if (!isPushSupported()) {
    return isIos() && !isStandalone() ? "ios-install" : "unsupported";
  }
  if (Notification.permission === "denied") return "denied";
  const reg = await navigator.serviceWorker.ready;
  const sub = await reg.pushManager.getSubscription();
  return sub ? "enabled" : "disabled";
}

/** Prompt for permission (must be from a user gesture), subscribe, and register with the backend. */
export async function enablePush(): Promise<PushState> {
  if (!isPushSupported()) {
    return isIos() && !isStandalone() ? "ios-install" : "unsupported";
  }
  const cfg = await portalApi.pushConfig();
  if (!cfg.enabled || !cfg.publicKey) return "server-off";

  const permission = await Notification.requestPermission();
  if (permission !== "granted") return "denied";

  const reg = await navigator.serviceWorker.ready;
  let sub = await reg.pushManager.getSubscription();
  if (!sub) {
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(cfg.publicKey),
    });
  }
  const json = sub.toJSON();
  await portalApi.subscribePush({
    endpoint: sub.endpoint,
    p256dh: json.keys?.p256dh ?? "",
    auth: json.keys?.auth ?? "",
  });
  return "enabled";
}

/** Unsubscribe locally and on the backend. */
export async function disablePush(): Promise<PushState> {
  if (!isPushSupported()) return "unsupported";
  const reg = await navigator.serviceWorker.ready;
  const sub = await reg.pushManager.getSubscription();
  if (sub) {
    try {
      await portalApi.unsubscribePush(sub.endpoint);
    } catch {
      /* backend prune is best-effort; still unsubscribe the browser */
    }
    await sub.unsubscribe();
  }
  return "disabled";
}

/**
 * Best-effort unsubscribe on logout — shared-device safety, so the next person on this browser does not
 * keep receiving the previous rep's push. Call BEFORE clearing the auth token (the backend DELETE needs it).
 */
export async function unsubscribePushOnLogout(): Promise<void> {
  try {
    if (!isPushSupported()) return;
    const reg = await navigator.serviceWorker.getRegistration();
    const sub = await reg?.pushManager.getSubscription();
    if (sub) {
      try {
        await portalApi.unsubscribePush(sub.endpoint);
      } catch {
        /* ignore */
      }
      await sub.unsubscribe();
    }
  } catch {
    /* logout must never fail because of push cleanup */
  }
}
