/** Shapes returned by the hub's internal endpoints. Kept loose where the backend returns raw rows. */

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

export type MappingCandidate = {
  id: string;
  channel_connection_id: string;
  channel_product_id: string;
  channel_variant_id: string;
  barcode: string | null;
  title: string | null;
  candidate_variant_ids: string | null;
  status: string;
  created_at: string;
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
