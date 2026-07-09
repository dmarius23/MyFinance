import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
      // Enable the generated SW in dev too, so Web Push can be tested on localhost.
      devOptions: { enabled: true, type: "module" },
      // App-shell precache; never cache sensitive API responses (handled by not matching /api).
      // push-sw.js (in public/) adds the `push` + `notificationclick` handlers to the generated SW.
      workbox: {
        navigateFallback: "/index.html",
        navigateFallbackDenylist: [/^\/api/],
        importScripts: ["push-sw.js"],
      },
      manifest: {
        name: "MyFinance",
        short_name: "MyFinance",
        description: "Accounting portal",
        theme_color: "#0D9488",
        background_color: "#ffffff",
        display: "standalone",
        start_url: "/",
        icons: [
          { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png" },
          { src: "/icons/icon-512-maskable.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
    }),
  ],
  server: {
    port: 5173,
  },
});
