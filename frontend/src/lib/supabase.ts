import { createClient } from "@supabase/supabase-js";

// Supabase issues the JWT; the SPA sends it as a Bearer token to the Spring API.
// The app never talks to the database directly — API only.
const url = import.meta.env.VITE_SUPABASE_URL;
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY;

if (!url || !anonKey) {
  // Surfaced loudly in dev; in prod these are baked at build time.
  console.warn("Supabase env not configured — set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY.");
}

export const supabase = createClient(url ?? "", anonKey ?? "", {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: true,
  },
});
