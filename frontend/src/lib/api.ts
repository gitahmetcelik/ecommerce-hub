import { clearSession, readAccessToken, readRefreshToken, storeSession } from "./auth";
import type {
  ChannelConnection,
  ChannelPush,
  LoginResponse,
  MappingCandidate,
  OperatorQueueItem,
  OrderItem,
  OversellEvent,
  ReturnDetail,
  ReturnItemRow,
  ReturnSummary,
  SalesOrder,
  StockDiscrepancy,
} from "./types";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/**
 * One 401 triggers exactly one refresh attempt, then the original request is retried.
 *
 * <p>Access tokens are deliberately short-lived (15 minutes, Plan §10), so a 401 in the
 * middle of ordinary use is expected rather than exceptional. Without this the user
 * would be bounced to the login screen every quarter of an hour; with an unbounded
 * retry, a genuinely dead session would loop forever.
 */
let refreshInFlight: Promise<boolean> | null = null;

async function refreshAccessToken(): Promise<boolean> {
  const refreshToken = readRefreshToken();
  if (!refreshToken) return false;

  // Shared promise: several queries failing at once must produce one refresh, not one
  // each — the refresh token rotates on use, so the second call would present an
  // already-revoked token and log the user out.
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const response = await fetch(`${API_URL}/auth/refresh`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken }),
        });
        if (!response.ok) return false;

        const body = (await response.json()) as LoginResponse;
        storeSession(body.accessToken, body.refreshToken, {
          userId: body.userId,
          organizationId: body.organizationId,
          roles: body.roles,
        });
        return true;
      } catch {
        return false;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

async function request<T>(path: string, options?: RequestInit, isRetry = false): Promise<T> {
  const token = readAccessToken();
  const response = await fetch(`${API_URL}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options?.headers,
    },
  });

  if (response.status === 401 && !isRetry) {
    if (await refreshAccessToken()) {
      return request<T>(path, options, true);
    }
    clearSession();
    if (typeof window !== "undefined" && window.location.pathname !== "/login") {
      window.location.href = "/login";
    }
    throw new Error("Session expired");
  }

  if (response.status === 403) {
    const body = await response.text().catch(() => "");
    throw new Error(body || "Your role does not permit this action");
  }

  if (!response.ok) {
    const body = await response.text().catch(() => "");
    throw new Error(`API error (${response.status}): ${body || response.statusText}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export const api = {
  auth: {
    login: (organizationId: string, email: string, password: string) =>
      request<LoginResponse>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ organizationId, email, password }),
      }),
    logout: (refreshToken: string) =>
      request<{ loggedOut: boolean }>("/auth/logout", {
        method: "POST",
        body: JSON.stringify({ refreshToken }),
      }),
  },

  orders: {
    list: () => request<SalesOrder[]>("/internal/orders"),
    items: (salesOrderId: string) =>
      request<OrderItem[]>(`/internal/order-items?salesOrderId=${encodeURIComponent(salesOrderId)}`),
  },

  stock: {
    pushes: () => request<ChannelPush[]>("/internal/channel-pushes"),
    discrepancies: () => request<StockDiscrepancy[]>("/internal/stock-discrepancies"),
    oversells: () => request<OversellEvent[]>("/internal/oversells"),
  },

  channels: {
    list: () => request<ChannelConnection[]>("/internal/channel-connections"),
  },

  matching: {
    candidates: () => request<MappingCandidate[]>("/internal/mapping-candidates"),
    resolve: (candidateId: string, variantId: string, userId: string) =>
      request<{ resolved: boolean }>(`/internal/mapping-candidates/${candidateId}/resolve`, {
        method: "POST",
        body: JSON.stringify({ variantId, userId }),
      }),
    ignore: (candidateId: string, userId: string) =>
      request<{ ignored: boolean }>(`/internal/mapping-candidates/${candidateId}/ignore`, {
        method: "POST",
        body: JSON.stringify({ userId }),
      }),
  },

  operator: {
    queue: () => request<OperatorQueueItem[]>("/internal/operator-queue"),
    dismiss: (id: string, reason: string) =>
      request<{ dismissed: boolean }>(`/internal/operator-queue/${id}/dismiss`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      }),
  },

  returns: {
    list: () => request<ReturnSummary[]>("/internal/returns"),
    get: (id: string) => request<ReturnDetail>(`/internal/returns/${id}`),
    items: (id: string) => request<ReturnItemRow[]>(`/internal/returns/${id}/items`),
    approve: (id: string) => request<ReturnDetail>(`/internal/returns/${id}/approve`, { method: "POST" }),
    reject: (id: string, reason: string) =>
      request<ReturnDetail>(`/internal/returns/${id}/reject`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      }),
    createShipment: (id: string) =>
      request<{ shipmentId: string; source: string; trackingNumber: string }>(
        `/internal/returns/${id}/shipment`,
        { method: "POST" },
      ),
    recordReceipt: (id: string, byReturnItemId: Record<string, { intact: number; damaged: number }>) =>
      request<ReturnDetail>(`/internal/returns/${id}/receipt`, {
        method: "POST",
        body: JSON.stringify({ byReturnItemId }),
      }),
    refund: (id: string) =>
      request<{ returnPaymentId: string; amount: string; currency: string; status: string }>(
        `/internal/returns/${id}/refund`,
        { method: "POST" },
      ),
  },
};
