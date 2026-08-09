"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { DataTable, PageHeader, type Column } from "@/components/data-table";
import { PermissionGate } from "@/components/hub/permission-gate";
import { RequireSession } from "@/components/require-session";
import { api } from "@/lib/api";
import { hasRole, type Session } from "@/lib/auth";
import type { ChannelConnection } from "@/lib/types";

const POLL_MS = 5000;

export default function ChannelsPage() {
  return <RequireSession>{(session) => <Channels session={session} />}</RequireSession>;
}

function Channels({ session }: { session: Session }) {
  const channels = useQuery({ queryKey: ["channels"], queryFn: api.channels.list, refetchInterval: POLL_MS });

  const columns: Column<ChannelConnection>[] = [
    {
      key: "type",
      header: "Channel",
      render: (row) => (
        <Link href={`/channels/${row.id}`} className="underline hover:no-underline">
          {row.channel_type}
        </Link>
      ),
    },
    {
      key: "status",
      header: "Status",
      render: (row) => (
        <span
          data-testid={`channel-status-${row.id}`}
          className={row.status === "ACTIVE" ? "" : "font-medium text-destructive"}
        >
          {row.status}
        </span>
      ),
    },
    { key: "failures", header: "Failure streak", render: (row) => row.consecutive_failures },
    {
      key: "circuit",
      header: "Circuit until",
      render: (row) => (row.circuit_open_until ? new Date(row.circuit_open_until).toLocaleString() : "—"),
    },
    // CREDENTIALS_INVALID never reopens on its own (Plan Phase 4), so this is the line that
    // tells an operator whether waiting will help or whether they have to act.
    { key: "reason", header: "Last failure", render: (row) => row.last_failure_reason ?? "—" },
    {
      key: "sync",
      header: "Last order sync",
      render: (row) => (row.last_order_sync_at ? new Date(row.last_order_sync_at).toLocaleString() : "never"),
    },
  ];

  return (
    <>
      <div className="flex items-start justify-between gap-4">
        <PageHeader
          title="Channels"
          description="An open circuit reopens itself once its backoff elapses. Invalid credentials do not — someone has to re-authorise them."
        />
        <PermissionGate allowed={hasRole(session, "ADMIN")} reason="ADMIN required to connect a channel">
          <Button type="button" data-testid="connect-channel-link" render={<Link href="/channels/connect" />}>
            Connect channel
          </Button>
        </PermissionGate>
      </div>
      <DataTable
        rows={channels.data}
        columns={columns}
        empty="No channel connections."
        testId="channels-table"
        rowKey={(row) => row.id}
      />
    </>
  );
}
