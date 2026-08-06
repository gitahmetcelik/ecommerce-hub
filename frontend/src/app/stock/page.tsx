"use client";

import { useQuery } from "@tanstack/react-query";
import { DataTable, PageHeader, type Column } from "@/components/data-table";
import { RequireSession } from "@/components/require-session";
import { api } from "@/lib/api";
import type { ChannelPush, OversellEvent, StockDiscrepancy } from "@/lib/types";

const POLL_MS = 5000;

export default function StockPage() {
  return <RequireSession>{() => <Stock />}</RequireSession>;
}

function Stock() {
  const pushes = useQuery({ queryKey: ["pushes"], queryFn: api.stock.pushes, refetchInterval: POLL_MS });
  const discrepancies = useQuery({
    queryKey: ["discrepancies"],
    queryFn: api.stock.discrepancies,
    refetchInterval: POLL_MS,
  });
  const oversells = useQuery({ queryKey: ["oversells"], queryFn: api.stock.oversells, refetchInterval: POLL_MS });

  const pushColumns: Column<ChannelPush>[] = [
    { key: "variant", header: "Variant", render: (row) => row.variant_id.slice(0, 8) },
    { key: "value", header: "Target", render: (row) => <code className="text-xs">{row.target_value}</code> },
    // A high generation on a row that is still PENDING is the shape a broken push takes:
    // the value keeps changing and the channel keeps not confirming it.
    { key: "generation", header: "Gen", render: (row) => row.generation },
    { key: "status", header: "Status", render: (row) => row.status },
    {
      key: "attempt",
      header: "Last attempt",
      render: (row) => (row.last_attempt_at ? new Date(row.last_attempt_at).toLocaleString() : "—"),
    },
  ];

  const discrepancyColumns: Column<StockDiscrepancy>[] = [
    { key: "variant", header: "Variant", render: (row) => row.variant_id.slice(0, 8) },
    { key: "type", header: "Type", render: (row) => row.type },
    { key: "expected", header: "We say", render: (row) => row.expected },
    { key: "actual", header: "They say", render: (row) => row.actual },
    { key: "updated", header: "Updated", render: (row) => new Date(row.updated_at).toLocaleString() },
  ];

  const oversellColumns: Column<OversellEvent>[] = [
    { key: "variant", header: "Variant", render: (row) => row.variant_id.slice(0, 8) },
    { key: "requested", header: "Requested", render: (row) => row.requested },
    { key: "available", header: "Available", render: (row) => row.available },
    { key: "created", header: "When", render: (row) => new Date(row.created_at).toLocaleString() },
  ];

  return (
    <>
      <PageHeader
        title="Stock"
        description="Discrepancies are reported and never auto-corrected (Plan §0) — the correction is the action a human wants to see before it happens."
      />

      <section className="mb-10">
        <h2 className="mb-3 text-lg font-medium">Pending pushes</h2>
        <DataTable
          rows={pushes.data}
          columns={pushColumns}
          empty="Nothing queued — every channel is up to date."
          testId="pushes-table"
          rowKey={(row) => row.id}
        />
      </section>

      <section className="mb-10">
        <h2 className="mb-3 text-lg font-medium">Open discrepancies</h2>
        <DataTable
          rows={discrepancies.data}
          columns={discrepancyColumns}
          empty="No drift reported."
          testId="discrepancies-table"
          rowKey={(row) => row.id}
        />
      </section>

      <section>
        <h2 className="mb-3 text-lg font-medium">Oversells</h2>
        <DataTable
          rows={oversells.data}
          columns={oversellColumns}
          empty="No sale has outrun its stock."
          testId="oversells-table"
          rowKey={(row) => row.id}
        />
      </section>
    </>
  );
}
