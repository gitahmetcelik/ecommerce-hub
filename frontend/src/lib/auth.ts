/**
 * plan §10 changed what a session is. The webhook platform stored one long-lived API
 * key; this platform has real users, a short-lived access token and a revocable
 * refresh token, so the two are kept separately and the access token is expected to
 * expire and be replaced rather than to last.
 */

const ACCESS_TOKEN_KEY = "hub_access_token";
const REFRESH_TOKEN_KEY = "hub_refresh_token";
const SESSION_KEY = "hub_session";

export type Session = {
  userId: string;
  organizationId: string;
  roles: string[];
};

export function readAccessToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function readRefreshToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function readSession(): Session | null {
  if (typeof window === "undefined") return null;
  const raw = window.localStorage.getItem(SESSION_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Session;
  } catch {
    return null;
  }
}

export function storeSession(accessToken: string, refreshToken: string, session: Session) {
  window.localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  window.localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
  window.localStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearSession() {
  window.localStorage.removeItem(ACCESS_TOKEN_KEY);
  window.localStorage.removeItem(REFRESH_TOKEN_KEY);
  window.localStorage.removeItem(SESSION_KEY);
}

/**
 * Notably absent: the organization id is never sent by the client. It is a claim inside
 * the access token, which the backend reads — see JwtService. Anything the browser could
 * put in a request is something the browser could change.
 */
export function hasRole(session: Session | null, required: "OBSERVER" | "OPERATOR" | "ADMIN"): boolean {
  if (!session) return false;
  const rank = { OBSERVER: 0, OPERATOR: 1, ADMIN: 2 } as const;
  const highest = session.roles.reduce((max, role) => {
    const value = rank[role as keyof typeof rank];
    return value !== undefined && value > max ? value : max;
  }, -1);
  return highest >= rank[required];
}
