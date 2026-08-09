"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";
import { DataTable, PageHeader, type Column } from "@/components/data-table";
import { Pagination } from "@/components/hub/pagination";
import { RequireSession } from "@/components/require-session";
import { api } from "@/lib/api";
import { hasRole, type Session } from "@/lib/auth";
import type { ReturnItemRow, ReturnSummary } from "@/lib/types";

const POLL_MS = 5000;

export default function ReturnsPage() {
  return <RequireSession>{(session) => <Returns session={session} />}</RequireSession>;
}

function Returns({ session }: { session: Session }) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  const returns = useQuery({
    queryKey: ["returns", page],
    queryFn: () => api.returns.list({ page }),
    refetchInterval: POLL_MS,
    placeholderData: (previous) => previous,
  });

  const canDecide = hasRole(session, "OPERATOR");
  const canRefund = hasRole(session, "ADMIN");

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ["returns"] });
    queryClient.invalidateQueries({ queryKey: ["return-items"] });
  }

  /**
   * Every action reports the backend's own error text. The role checks live in the
   * services (Plan §7), so a refusal here is the real answer rather than a guess this
   * screen made — and hiding the buttons alone would leave the user with no explanation.
   */
  const act = useMutation({
    mutationFn: async ({ id, action }: { id: string; action: "approve" | "reject" | "shipment" | "refund" }) => {
      if (action === "approve") return api.returns.approve(id);
      if (action === "reject") return api.returns.reject(id, "Rejected from the dashboard");
      if (action === "shipment") return api.returns.createShipment(id);
      return api.returns.refund(id);
    },
    onSuccess: (_data, variables) => {
      toast.success(`Return ${variables.action} succeeded`);
      refresh();
    },
    onError: (error: Error) => toast.error(error.message),
  });

  const columns: Column<ReturnSummary>[] = [
    {
      key: "id",
      header: "Return",
      render: (row) => (
        <button type="button" onClick={() => setSelected(row.id)} className="underline hover:no-underline">
          {row.id.slice(0, 8)}
        </button>
      ),
    },
    {
      key: "status",
      header: "Status",
      render: (row) => <span data-testid={`return-status-${row.id}`}>{row.status}</span>,
    },
    { key: "channel", header: "Channel ref", render: (row) => row.channel_return_id ?? "—" },
    {
      key: "deadline",
      header: "Deadline",
      render: (row) => (row.timeout_at ? new Date(row.timeout_at).toLocaleString() : "—"),
    },
    {
      key: "actions",
      header: "Actions",
      render: (row) => (
        <div className="flex flex-wrap gap-2">
          {(row.status === "AWAITING_APPROVAL" || row.status === "TIMED_OUT") && (
            <>
              <button
                type="button"
                data-testid={`approve-${row.id}`}
                disabled={!canDecide || act.isPending}
                onClick={() => act.mutate({ id: row.id, action: "approve" })}
                className="rounded border px-2 py-1 text-xs disabled:opacity-40"
              >
                Approve
              </button>
              <button
                type="button"
                data-testid={`reject-${row.id}`}
                disabled={!canDecide || act.isPending}
                onClick={() => act.mutate({ id: row.id, action: "reject" })}
                className="rounded border px-2 py-1 text-xs disabled:opacity-40"
              >
                Reject
              </button>
            </>
          )}
          {row.status === "ACCEPTED" && (
            <button
              type="button"
              data-testid={`shipment-${row.id}`}
              disabled={act.isPending}
              onClick={() => act.mutate({ id: row.id, action: "shipment" })}
              className="rounded border px-2 py-1 text-xs disabled:opacity-40"
            >
              Create label
            </button>
          )}
          {row.status === "RETURN_RECEIVED" && (
            <button
              type="button"
              data-testid={`refund-${row.id}`}
              disabled={!canRefund || act.isPending}
              onClick={() => act.mutate({ id: row.id, action: "refund" })}
              className="rounded border px-2 py-1 text-xs disabled:opacity-40"
              title={canRefund ? undefined : "Refunds require the ADMIN role"}
            >
              Refund
            </button>
          )}
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Returns"
        description="A return past its 48-hour deadline is escalated, never auto-rejected (Plan §0) — it stays decidable."
      />

      <DataTable
        rows={returns.data?.items}
        columns={columns}
        empty="No returns."
        testId="returns-table"
        rowKey={(row) => row.id}
      />
      {returns.data && (
        <Pagination page={returns.data.page} size={returns.data.size} total={returns.data.total} onPageChange={setPage} itemLabel="return" />
      )}

      {selected && <ReceiptPanel returnRequestId={selected} onDone={refresh} />}
    </>
  );
}

/** The intact/damaged split, per line — Plan §3 keeps damaged units off sellable stock. */
function ReceiptPanel({ returnRequestId, onDone }: { returnRequestId: string; onDone: () => void }) {
  const items = useQuery({
    queryKey: ["return-items", returnRequestId],
    queryFn: () => api.returns.items(returnRequestId),
  });
  const [damaged, setDamaged] = useState<Record<string, number>>({});

  const record = useMutation({
    mutationFn: () => {
      const payload: Record<string, { intact: number; damaged: number }> = {};
      for (const item of items.data ?? []) {
        const damagedCount = damaged[item.id] ?? 0;
        payload[item.id] = { intact: item.quantity - damagedCount, damaged: damagedCount };
      }
      return api.returns.recordReceipt(returnRequestId, payload);
    },
    onSuccess: () => {
      toast.success("Receipt recorded");
      onDone();
    },
    onError: (error: Error) => toast.error(error.message),
  });

  const columns: Column<ReturnItemRow>[] = [
    { key: "item", header: "Order item", render: (row) => row.order_item_id.slice(0, 8) },
    { key: "qty", header: "Returned", render: (row) => row.quantity },
    {
      key: "damaged",
      header: "Damaged",
      render: (row) => (
        <input
          type="number"
          min={0}
          max={row.quantity}
          data-testid={`damaged-${row.id}`}
          value={damaged[row.id] ?? 0}
          onChange={(e) =>
            setDamaged((current) => ({ ...current, [row.id]: Math.max(0, Number(e.target.value) || 0) }))
          }
          className="w-20 rounded border bg-background px-2 py-1 text-sm"
        />
      ),
    },
  ];

  return (
    <section className="mt-8">
      <h2 className="mb-3 text-lg font-medium">Record receipt</h2>
      <DataTable
        rows={items.data}
        columns={columns}
        empty="This return has no lines."
        testId="return-items-table"
        rowKey={(row) => row.id}
      />
      <button
        type="button"
        data-testid="record-receipt"
        disabled={record.isPending || !items.data?.length}
        onClick={() => record.mutate()}
        className="mt-3 rounded-md bg-primary px-3 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50"
      >
        Record receipt
      </button>
    </section>
  );
}
