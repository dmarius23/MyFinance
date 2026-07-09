/* eslint-disable */
/*
 * Web Push handlers, imported into the Workbox-generated service worker (see vite.config.ts
 * workbox.importScripts). Kept as a plain hand-written script so the precache SW stays generated.
 * On `push` it shows the notification; on click it focuses an existing PWA window or opens one.
 */
self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = { title: "MyFinance", body: event.data ? event.data.text() : "" };
  }
  const title = data.title || "MyFinance";
  const options = {
    body: data.body || "",
    icon: "/icons/icon-192.png",
    badge: "/icons/icon-192.png",
    lang: "ro",
    data: { url: data.url || "/" },
  };
  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || "/";
  event.waitUntil(
    (async () => {
      const all = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      for (const client of all) {
        if ("focus" in client) {
          try {
            await client.navigate(url);
          } catch (e) {
            /* cross-origin or not allowed — just focus */
          }
          return client.focus();
        }
      }
      if (self.clients.openWindow) return self.clients.openWindow(url);
    })(),
  );
});
