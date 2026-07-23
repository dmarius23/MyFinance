import { supabase } from "./supabase";
import { getActiveCompanyId } from "./activeCompany";

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

/** Attach the rep's active company (the backend validates it against their assignments). */
function withCompany(headers: Headers): Headers {
  const cid = getActiveCompanyId();
  if (cid) headers.set("X-Company-Id", cid);
  return headers;
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public detail?: unknown,
  ) {
    super(message);
  }
}

/**
 * Thin fetch wrapper that attaches the Supabase access token as a Bearer header and parses
 * RFC-7807 problem responses. A typed client generated from the backend OpenAPI spec will
 * eventually supersede the ad-hoc calls in feature hooks — this stays the single transport.
 */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;

  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  withCompany(headers);

  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers });

  if (!res.ok) {
    let detail: unknown;
    try {
      detail = await res.json();
    } catch {
      detail = await res.text();
    }
    const message =
      detail && typeof detail === "object" && "detail" in detail
        ? String((detail as { detail: unknown }).detail)
        : `Request failed (${res.status})`;
    throw new ApiError(res.status, message, detail);
  }

  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

/**
 * Like {@link api} but also returns the raw response headers (and undefined on 204). Used where the
 * server carries out-of-band metadata in headers — e.g. the report's period-coverage `X-Report-*`.
 */
export async function apiWithHeaders<T>(
  path: string,
  init: RequestInit = {},
): Promise<{ data: T | undefined; headers: Headers }> {
  const token = await authToken();
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);
  withCompany(headers);

  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers });
  if (!res.ok) {
    let detail: unknown;
    try {
      detail = await res.json();
    } catch {
      detail = await res.text();
    }
    const message =
      detail && typeof detail === "object" && "detail" in detail
        ? String((detail as { detail: unknown }).detail)
        : `Request failed (${res.status})`;
    throw new ApiError(res.status, message, detail);
  }
  if (res.status === 204) {
    return { data: undefined, headers: res.headers };
  }
  return { data: (await res.json()) as T, headers: res.headers };
}

/** Resolves the current Supabase access token, or null. */
async function authToken(): Promise<string | null> {
  const { data } = await supabase.auth.getSession();
  return data.session?.access_token ?? null;
}

/** POST multipart/form-data (browser sets the boundary; do NOT set Content-Type). */
export async function upload<T>(path: string, form: FormData): Promise<T> {
  const token = await authToken();
  const headers = new Headers();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  withCompany(headers);

  const res = await fetch(`${BASE_URL}${path}`, { method: "POST", body: form, headers });
  if (!res.ok) {
    let detail: unknown;
    try {
      detail = await res.json();
    } catch {
      detail = await res.text();
    }
    const message =
      detail && typeof detail === "object" && "detail" in detail
        ? String((detail as { detail: unknown }).detail)
        : `Upload failed (${res.status})`;
    throw new ApiError(res.status, message, detail);
  }
  return (await res.json()) as T;
}

/** GET a binary resource as a Blob (for downloads). */
export async function download(path: string): Promise<Blob> {
  const token = await authToken();
  const headers = new Headers();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  withCompany(headers);

  const res = await fetch(`${BASE_URL}${path}`, { headers });
  if (!res.ok) {
    throw new ApiError(res.status, `Download failed (${res.status})`);
  }
  return res.blob();
}
