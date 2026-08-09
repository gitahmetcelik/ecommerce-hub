"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { DataTable, PageHeader, type Column } from "@/components/data-table";
import { EmptyState } from "@/components/hub/empty-state";
import { Pagination } from "@/components/hub/pagination";
import { RequireSession } from "@/components/require-session";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { api } from "@/lib/api";
import { hasRole, type Session } from "@/lib/auth";
import type { DlqRow, IntentRow, RawEventRow, TaskRow } from "@/lib/types";

export default function DiagnosticsPage() {
  return <RequireSession>{(session) => <Diagnostics session={session} />}</RequireSession>;
}

/**
 * Plan U7, ADMIN-only. ui-plani §3's rule: "motor/kuyruk/görev" vocabulary is allowed to
 * surface only on this screen — everywhere else the operator sees orders, stock, returns,
 * never the engine underneath them. trace_id is the thread that ties a raw webhook to the
 * task, order line and channel push it produced.
 */
function Diagnostics({ session }: { session: Session }) {
  const [traceIdInput, setTraceIdInput] = useState("");
  const [traceId, setTraceId] = useState("");

  if (!hasRole(session, "ADMIN")) {
    return (
      <>
        <PageHeader title="Diagnostics" />
        <EmptyState
          variant="no-data"
          title="ADMIN only."
          description="This screen shows engine internals — task retries, the dead letter queue, raw webhook payloads. Ask an admin if you need something traced."
          testId="diagnostics-forbidden"
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Diagnostics"
        description="An event's journey from ingest to channel, on one page. Search by trace_id to narrow every tab to a single event."
      />

      <form
        className="mb-6 flex max-w-md gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          setTraceId(traceIdInput.trim());
        }}
      >
        <Input
          value={traceIdInput}
          onChange={(event) => setTraceIdInput(event.target.value)}
          placeholder="trace_id ara…"
          data-testid="diagnostics-trace-search"
        />
        {traceId && (
          <button
            type="button"
            className="text-sm text-muted-foreground underline hover:no-underline"
            onClick={() => {
              setTraceIdInput("");
              setTraceId("");
            }}
          >
            Clear
          </button>
        )}
      </form>

      <Tabs defaultValue="tasks">
        <TabsList>
          <TabsTrigger value="tasks" data-testid="diagnostics-tab-tasks">
            Tasks
          </TabsTrigger>
          <TabsTrigger value="dlq" data-testid="diagnostics-tab-dlq">
            DLQ
          </TabsTrigger>
          <TabsTrigger value="raw-events" data-testid="diagnostics-tab-raw-events">
            Raw events
          </TabsTrigger>
          <TabsTrigger value="intents" data-testid="diagnostics-tab-intents">
            Intents
          </TabsTrigger>
        </TabsList>

        <TabsContent value="tasks" className="mt-4">
          <TasksTab traceId={traceId} />
        </TabsContent>
        <TabsContent value="dlq" className="mt-4">
          <DlqTab traceId={traceId} />
        </TabsContent>
        <TabsContent value="raw-events" className="mt-4">
          <RawEventsTab traceId={traceId} />
        </TabsContent>
        <TabsContent value="intents" className="mt-4">
          <IntentsTab />
        </TabsContent>
      </Tabs>
    </>
  );
}

function TasksTab({ traceId }: { traceId: string }) {
  const tasks = useQuery({
    queryKey: ["diagnostics-tasks", traceId],
    queryFn: () => api.diagnostics.tasks(traceId || undefined),
  });

  const columns: Column<TaskRow>[] = [
    { key: "type", header: "Type", render: (row) => row.task_type },
    { key: "batch_status", header: "Batch status", render: (row) => row.work_batch_status },
    { key: "task_status", header: "Task status", render: (row) => row.task_status ?? "—" },
    { key: "attempts", header: "Attempts", render: (row) => row.deneme_sayisi ?? "—" },
    { key: "error", header: "Error", render: (row) => row.hata ?? "—" },
    { key: "trace", header: "trace_id", render: (row) => <code className="text-xs">{row.trace_id ?? "—"}</code> },
  ];

  return (
    <DataTable
      rows={tasks.data}
      columns={columns}
      empty={traceId ? "No tasks match this trace_id." : "No tasks yet."}
      testId="diagnostics-tasks-table"
      rowKey={(row) => row.work_batch_id}
    />
  );
}

function DlqTab({ traceId }: { traceId: string }) {
  const [page, setPage] = useState(0);
  const dlq = useQuery({
    queryKey: ["diagnostics-dlq", traceId, page],
    queryFn: () => api.diagnostics.dlq({ page, traceId: traceId || undefined }),
    placeholderData: (previous) => previous,
  });

  const columns: Column<DlqRow>[] = [
    { key: "type", header: "Type", render: (row) => row.task_type },
    { key: "error", header: "Last error", render: (row) => row.son_hata ?? "—" },
    { key: "entered", header: "Entered", render: (row) => new Date(row.giris_zamani).toLocaleString() },
    { key: "resent", header: "Resent", render: (row) => (row.yeniden_gonderildi_mi ? "Yes" : "No") },
    { key: "trace", header: "trace_id", render: (row) => <code className="text-xs">{row.trace_id ?? "—"}</code> },
  ];

  return (
    <>
      <DataTable
        rows={dlq.data?.items}
        columns={columns}
        empty={traceId ? "No DLQ entries match this trace_id." : "Nothing in the dead letter queue."}
        testId="diagnostics-dlq-table"
        rowKey={(row) => row.id}
      />
      {dlq.data && (
        <Pagination page={dlq.data.page} size={dlq.data.size} total={dlq.data.total} onPageChange={setPage} itemLabel="entry" testId="diagnostics-dlq-pagination" />
      )}
    </>
  );
}

function RawEventsTab({ traceId }: { traceId: string }) {
  const rawEvents = useQuery({
    queryKey: ["diagnostics-raw-events", traceId],
    queryFn: () => api.diagnostics.rawEvents(traceId || undefined),
  });

  const columns: Column<RawEventRow>[] = [
    { key: "channel_connection", header: "Channel connection", render: (row) => row.channel_connection_id.slice(0, 8) },
    { key: "channel_event_id", header: "Channel event id", render: (row) => row.channel_event_id },
    { key: "received", header: "Received", render: (row) => new Date(row.received_at).toLocaleString() },
    { key: "trace", header: "trace_id", render: (row) => <code className="text-xs">{row.trace_id ?? "—"}</code> },
  ];

  return (
    <DataTable
      rows={rawEvents.data}
      columns={columns}
      empty={traceId ? "No raw events match this trace_id." : "No raw events yet."}
      testId="diagnostics-raw-events-table"
      rowKey={(row) => row.id}
    />
  );
}

function IntentsTab() {
  const intents = useQuery({
    queryKey: ["diagnostics-intents"],
    queryFn: () => api.diagnostics.intents(),
  });

  const columns: Column<IntentRow>[] = [
    { key: "type", header: "Type", render: (row) => row.type },
    { key: "target", header: "Target", render: (row) => row.target_reference.slice(0, 8) },
    { key: "status", header: "Status", render: (row) => row.status },
    { key: "updated", header: "Updated", render: (row) => new Date(row.updated_at).toLocaleString() },
  ];

  return (
    <DataTable
      rows={intents.data}
      columns={columns}
      empty="No channel call intents yet."
      testId="diagnostics-intents-table"
      rowKey={(row) => row.id}
    />
  );
}
