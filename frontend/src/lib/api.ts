import { clearSession, readAccessToken, readRefreshToken, storeSession } from "./auth";
import type {
  ChannelConnection,
  ChannelPush,
  DlqRow,
  IntentRow,
  LoginResponse,
  MappingCandidate,
  OperatorQueueItem,
  OrderItem,
  OversellEvent,
  PageResponse,
  PriceDetail,
  RawEventRow,
  ReturnDetail,
  ReturnItemRow,
  ReturnSummary,
  SalesOrder,
  StockAdjustmentReason,
  StockDiscrepancy,
  TaskRow,
  VariantDetail,
  VariantRow,
} from "./types";

/** Plan v5 Faz 7 §7.2 point 5: every list endpoint now takes a page. */
export type ListParams = { page?: number; size?: number };

function query(params: Record<string, string | number | boolean | undefined>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== "");
  if (entries.length === 0) return "";
  return "?" + entries.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`).join("&");
}

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
    list: (params: ListParams = {}) => request<PageResponse<SalesOrder>>(`/internal/orders${query(params)}`),
    items: (salesOrderId: string) =>
      request<OrderItem[]>(`/internal/order-items?salesOrderId=${encodeURIComponent(salesOrderId)}`),
  },

  stock: {
    pushes: (params: ListParams = {}) => request<PageResponse<ChannelPush>>(`/internal/channel-pushes${query(params)}`),
    discrepancies: (params: ListParams = {}) =>
      request<PageResponse<StockDiscrepancy>>(`/internal/stock-discrepancies${query(params)}`),
    oversells: (params: ListParams = {}) => request<PageResponse<OversellEvent>>(`/internal/oversells${query(params)}`),
  },

  channels: {
    list: () => request<ChannelConnection[]>("/internal/channel-connections"),
  },

  matching: {
    candidates: (params: ListParams = {}) =>
      request<PageResponse<MappingCandidate>>(`/internal/mapping-candidates${query(params)}`),
    resolve: (candidateId: string, variantId: string) =>
      request<{ resolved: boolean }>(`/internal/mapping-candidates/${candidateId}/resolve`, {
        method: "POST",
        body: JSON.stringify({ variantId }),
      }),
    ignore: (candidateId: string) =>
      request<{ ignored: boolean }>(`/internal/mapping-candidates/${candidateId}/ignore`, {
        method: "POST",
      }),
  },

  operator: {
    queue: (params: ListParams = {}) =>
      request<PageResponse<OperatorQueueItem>>(`/internal/operator-queue${query(params)}`),
    dismiss: (id: string, reason: string) =>
      request<{ dismissed: boolean }>(`/internal/operator-queue/${id}/dismiss`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      }),
  },

  variants: {
    list: (params: ListParams & { q?: string; channelConnectionId?: string; stockStatus?: string; matchStatus?: string } = {}) =>
      request<PageResponse<VariantRow>>(`/internal/variants${query(params)}`),
    get: (id: string) => request<VariantDetail>(`/internal/variants/${id}`),
    adjustStock: (
      id: string,
      body: { expectedOnHand: number; newOnHand: number; reason: StockAdjustmentReason; note: string },
    ) =>
      request<{ adjusted: boolean }>(`/internal/variants/${id}/stock-adjustment`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    setBuffer: (id: string, channelConnectionId: string, buffer: number) =>
      request<{ buffer: number }>(`/internal/variants/${id}/buffer/${channelConnectionId}`, {
        method: "POST",
        body: JSON.stringify({ buffer }),
      }),
  },

  prices: {
    get: (variantId: string) => request<PriceDetail>(`/internal/prices?variantId=${encodeURIComponent(variantId)}`),
    setListPrice: (variantId: string, amount: string, currency: string, vatRate: string) =>
      request<{ variantId: string; listPrice: string }>(`/internal/prices/${variantId}/list-price`, {
        method: "POST",
        body: JSON.stringify({ amount, currency, vatRate }),
      }),
    setChannelPrice: (variantId: string, channelConnectionId: string, amount: string, discountedPrice: string | null) =>
      request<{ variantId: string; channelConnectionId: string; price: string }>(
        `/internal/prices/${variantId}/channel-price/${channelConnectionId}`,
        { method: "POST", body: JSON.stringify({ amount, discountedPrice }) },
      ),
    clearChannelPrice: (variantId: string, channelConnectionId: string) =>
      request<{ cleared: boolean }>(`/internal/prices/${variantId}/channel-price/${channelConnectionId}`, {
        method: "DELETE",
      }),
  },

  diagnostics: {
    tasks: (traceId?: string) => request<TaskRow[]>(`/internal/tasks${query({ traceId })}`),
    dlq: (params: ListParams & { traceId?: string } = {}) =>
      request<PageResponse<DlqRow>>(`/internal/dlq${query(params)}`),
    rawEvents: (traceId?: string) => request<RawEventRow[]>(`/internal/raw-events${query({ traceId })}`),
    intents: () => request<IntentRow[]>("/internal/intents"),
  },

  returns: {
    list: (params: ListParams = {}) => request<PageResponse<ReturnSummary>>(`/internal/returns${query(params)}`),
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
