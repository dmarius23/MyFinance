import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

/**
 * Separate from vite.config.ts on purpose: the app config registers vite-plugin-pwa, which would
 * generate a service worker on every test run. Tests need only the React transform.
 *
 * `globals` stays false — tests import `describe`/`it`/`expect` explicitly, matching the project's
 * no-implicit-globals style. That means React Testing Library's automatic cleanup is not wired up
 * by the runner, so src/test/setup.ts registers it by hand.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    include: ["src/**/*.test.{ts,tsx}"],
    restoreMocks: true,
  },
});
