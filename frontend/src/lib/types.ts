/** Shapes returned by the hub's internal endpoints. Kept loose where the backend returns raw rows. */

/** Plan v5 Faz 7 §7.2 point 5: every internal list endpoint returns this shape now. */
export type PageResponse<T> = {
  page: number;
  size: number;
  total: number;
  items: T[];
};

export type LoginResponse = {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
  userId: string;
  organizationId: string;
  roles: string[];
};

export type SalesOrder = {
  id: string;
  channel_order_number: string;
  derived_status: string;
  total: string | number;
  currency: string;
  created_at: string;
  updated_at: string;
};

export type OrderItem = {
  id: string;
  variant_id: string;
  quantity: number;
  status: string;
  updated_at: string;
};

export type ChannelPush = {
  id: string;
  channel_connection_id: string;
  variant_id: string;
  type: string;
  target_value: string;
  generation: number;
  status: string;
  last_attempt_at: string | null;
  updated_at: string;
};

export type StockDiscrepancy = {
  id: string;
  channel_connection_id: string | null;
  variant_id: string;
  type: string;
  expected: number;
  actual: number;
  resolved: boolean;
  updated_at: string;
};

export type OversellEvent = {
  id: string;
  channel_connection_id: string;
  variant_id: string;
  requested: number;
  available: number;
  created_at: string;
};

export type ChannelConnection = {
  id: string;
  channel_type: string;
  status: string;
  consecutive_failures: number;
  circuit_open_until: string | null;
  last_failure_reason: string | null;
  reconcile_interval_minutes: number;
  next_reconcile_at: string | null;
  last_order_sync_at: string | null;
  allocation_priority: number;
};

export type CandidateVariant = {
  variantId: string;
  sku: string;
  barcode: string | null;
  title: string | null;
  /** Order items on this variant not yet DELIVERED/CANCELLED/PAYMENT_TIMEOUT — what's riding on picking right. */
  openOrderItems: number;
};

export type MappingCandidate = {
  id: string;
  channel_connection_id: string;
  channel_product_id: string;
  channel_variant_id: string;
  barcode: string | null;
  title: string | null;
  status: string;
  created_at: string;
  /** Null/empty when nothing matched at all; 2+ when a barcode was ambiguous — never exactly 1 (that auto-resolves before reaching here). */
  candidates: CandidateVariant[] | null;
};

export type OperatorQueueItem = {
  id: string;
  type: string;
  description: string;
  reference_id: string | null;
  status: string;
  created_at: string;
  /** Only RETURN_APPROVAL carries one — return_request.timeout_at. Null for every other type. */
  deadline_at: string | null;
};

export type ReturnSummary = {
  id: string;
  status: string;
  sales_order_id: string;
  channel_return_id: string | null;
  reason: string | null;
  timeout_at: string | null;
  created_at: string;
};

export type ReturnDetail = {
  id: string;
  status: string;
  salesOrderId: string;
  channelReturnId: string;
};

export type ReturnItemRow = {
  id: string;
  order_item_id: string;
  quantity: number;
  intact_quantity: number | null;
  damaged_quantity: number | null;
};

/** One channel's view of a variant — Plan §U2/§U3's per-channel chip data. */
export type VariantChannelSummary = {
  channelConnectionId: string;
  channelType: string;
  channelVariantId: string;
  status: string;
  quantity: number | null;
  generation: number | null;
  updatedAt: string | null;
  hasChannelPriceOverride: boolean;
  consecutiveFailures: number;
  errorReason: string | null;
};

export type VariantRow = {
  id: string;
  sku: string;
  barcode: string | null;
  sku_is_generated: boolean;
  title: string;
  on_hand: number;
  reserved: number;
  sellable: number;
  list_price: string | number | null;
  currency: string | null;
  vat_rate: string | number | null;
  channels: VariantChannelSummary[] | null;
};

export type StockMovementRow = {
  id: string;
  quantity: number;
  reason: string;
  adjustment_reason: string | null;
  note: string | null;
  actor_user_id: string | null;
  reference_id: string | null;
  created_at: string;
};

export type StockBufferRow = {
  channel_connection_id: string;
  buffer: number;
  updated_at: string;
};

export type VariantDetail = VariantRow & {
  damaged: number;
  buffers: StockBufferRow[];
  movements: StockMovementRow[];
};

export const STOCK_ADJUSTMENT_REASONS = [
  "COUNT_DISCREPANCY",
  "DAMAGE",
  "LOSS",
  "WAREHOUSE_RECEIPT",
  "OTHER",
] as const;
export type StockAdjustmentReason = (typeof STOCK_ADJUSTMENT_REASONS)[number];

/** Plan U7 (diagnostics, ADMIN-only): work_batch joined with its engine task — the only screen where "task"/"queue" vocabulary is allowed to surface. */
export type TaskRow = {
  work_batch_id: string;
  task_type: string;
  work_batch_status: string;
  trace_id: string | null;
  task_id: string | null;
  task_status: string | null;
  deneme_sayisi: number | null;
  hata: string | null;
};

export type DlqRow = {
  id: string;
  gorev_id: string;
  son_hata: string | null;
  giris_zamani: string;
  yeniden_gonderildi_mi: boolean;
  task_type: string;
  trace_id: string | null;
};

export type RawEventRow = {
  id: string;
  channel_connection_id: string;
  channel_event_id: string;
  received_at: string;
  trace_id: string | null;
};

export type IntentRow = {
  id: string;
  type: string;
  target_reference: string;
  status: string;
  created_at: string;
  updated_at: string;
};

export type PriceDetail = {
  listPrice: { list_price: string; currency: string; vat_rate: string; effective_from: string } | null;
  channelPrices: {
    channel_connection_id: string;
    price: string;
    discounted_price: string | null;
    is_active: boolean;
    updated_at: string;
  }[];
};
