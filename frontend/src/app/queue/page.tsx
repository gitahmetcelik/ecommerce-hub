"use client";

import { useQuery } from "@tanstack/react-query";
import { DataTable, PageHeader, type Column } from "@/components/data-table";
import { RequireSession } from "@/components/require-session";
import { api } from "@/lib/api";
import type { OperatorQueueItem } from "@/lib/types";

const POLL_MS = 5000;

export default function QueuePage() {
  return <RequireSession>{() => <Queue />}</RequireSession>;
}

/**
 * plan §3: the operator queue is where work needing a human decision lands, and it is
 * deliberately separate from the engine's dead-letter queue. The DLQ holds tasks that
 * failed; this holds situations that no amount of retrying will resolve.
 */
function Queue() {
  const queue = useQuery({ queryKey: ["operator-queue"], queryFn: api.operator.queue, refetchInterval: POLL_MS });

  const columns: Column<OperatorQueueItem>[] = [
    { key: "type", header: "Type", render: (row) => row.type },
    { key: "description", header: "What happened", render: (row) => row.description },
    { key: "status", header: "Status", render: (row) => row.status },
    { key: "created", header: "Raised", render: (row) => new Date(row.created_at).toLocaleString() },
  ];

  return (
    <>
      <PageHeader
        title="Operator queue"
        description="Work that needs a person. Separate from the engine's DLQ, which holds tasks that failed rather than decisions nobody has made."
      />
      <DataTable
        rows={queue.data}
        columns={columns}
        empty="Nothing is waiting on a human."
        testId="queue-table"
        rowKey={(row) => row.id}
      />
    </>
  );
}
