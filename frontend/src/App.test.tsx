import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";

// Mocked before importing App: AuthProvider calls into the Supabase client on mount, and the real
// one needs VITE_SUPABASE_* env vars that tests must not depend on.
const getSession = vi.fn();
vi.mock("./lib/supabase", () => ({
  supabase: {
    auth: {
      getSession: () => getSession(),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe: () => {} } } }),
      signOut: vi.fn(),
    },
  },
}));
// Keeps the service-worker/push machinery out of a jsdom run.
vi.mock("./lib/push", () => ({
  unsubscribePushOnLogout: vi.fn(async () => {}),
  currentPushState: vi.fn(async () => "disabled"),
  enablePush: vi.fn(async () => "enabled"),
  disablePush: vi.fn(async () => "disabled"),
}));

const { default: App } = await import("./App");
const { AuthProvider } = await import("./auth/AuthProvider");
await import("./i18n");

/** Render the real route tree at `path`, with no signed-in user. */
function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[path]}>
          <App />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("legal routes", () => {
  beforeEach(() => {
    getSession.mockResolvedValue({ data: { session: null } });
  });

  // The whole point of these routes: a user must be able to read what they are agreeing to
  // before they have an account, and a rep must be able to reach them from the PWA.
  it.each([
    ["/legal/terms", /TERMENI ȘI CONDIȚII/i],
    ["/legal/privacy", /POLITICA DE CONFIDEN/i],
  ])("renders %s without a session", async (path, heading) => {
    renderAt(path);
    // findBy*: the page is a lazy route behind Suspense.
    expect(await screen.findByRole("heading", { level: 1, name: heading })).toBeInTheDocument();
  });

  it("does not redirect an anonymous visitor away from a legal route", async () => {
    renderAt("/legal/terms");
    expect(await screen.findByRole("heading", { level: 1 })).toBeInTheDocument();
    // The login form is what an unauthenticated user gets on a guarded route — it must not appear.
    expect(screen.queryByLabelText("Password")).not.toBeInTheDocument();
  });

  // Control case: proves the guard is real, so the test above is meaningful rather than
  // passing because nothing is guarded at all.
  it("still redirects an anonymous visitor away from a guarded route", async () => {
    renderAt("/dashboard");
    expect(await screen.findByLabelText("Password")).toBeInTheDocument();
  });
});
