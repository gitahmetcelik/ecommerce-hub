"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { DataTable, PageHeader, type Column } from "@/components/data-table";
import { RequireSession } from "@/components/require-session";
import { api } from "@/lib/api";
import type { OrderItem, SalesOrder } from "@/lib/types";

// plan §12 Faz 6: "real-time (önce polling)". A websocket buys sub-second latency for a
// screen a human reads at human speed, at the cost of a second transport to operate.
const POLL_MS = 5000;

const columns = (onSelect: (id: string) => void): Column<SalesOrder>[] => [
  {
    key: "number",
    header: "Order",
    render: (row) => (
      <button type="button" onClick={() => onSelect(row.id)} className="underline hover:no-underline">
        {row.channel_order_number}
      </button>
    ),
  },
  { key: "status", header: "Status", render: (row) => row.derived_status },
  { key: "total", header: "Total", render: (row) => `${row.total} ${row.currency}` },
  { key: "updated", header: "Updated", render: (row) => new Date(row.updated_at).toLocaleString() },
];

export default function OrdersPage() {
  return <RequireSession>{() => <Orders />}</RequireSession>;
}

function Orders() {
  const [selected, setSelected] = useState<string | null>(null);

  const orders = useQuery({
    queryKey: ["orders"],
    queryFn: api.orders.list,
    refetchInterval: POLL_MS,
  });

  const items = useQuery({
    queryKey: ["order-items", selected],
    queryFn: () => api.orders.items(selected!),
    enabled: selected !== null,
    refetchInterval: POLL_MS,
  });

  const itemColumns: Column<OrderItem>[] = [
    { key: "variant", header: "Variant", render: (row) => row.variant_id.slice(0, 8) },
    { key: "qty", header: "Qty", render: (row) => row.quantity },
    { key: "status", header: "Status", render: (row) => row.status },
  ];

  return (
    <>
      <PageHeader
        title="Orders"
        description="Derived order status is recomputed from its items — plan §6 keeps the state machine at line level."
      />

      {orders.isError && <p className="mb-4 text-sm text-destructive">{(orders.error as Error).message}</p>}

      <DataTable
        rows={orders.data}
        columns={columns(setSelected)}
        empty="No orders yet."
        testId="orders-table"
        rowKey={(row) => row.id}
      />

      {selected && (
        <section className="mt-8">
          <h2 className="mb-3 text-lg font-medium">Items</h2>
          <DataTable
            rows={items.data}
            columns={itemColumns}
            empty="This order has no matched items — unmatched lines sit in Matching."
            testId="order-items-table"
            rowKey={(row) => row.id}
          />
        </section>
      )}
    </>
  );
}
