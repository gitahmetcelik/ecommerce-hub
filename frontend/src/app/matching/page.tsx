"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";
import { DataTable, PageHeader, type Column } from "@/components/data-table";
import { RequireSession } from "@/components/require-session";
import { api } from "@/lib/api";
import type { Session } from "@/lib/auth";
import type { MappingCandidate } from "@/lib/types";

const POLL_MS = 5000;

export default function MatchingPage() {
  return <RequireSession>{(session) => <Matching session={session} />}</RequireSession>;
}

/**
 * Plan Phase 3: channel items that could not be matched are queued here rather than
 * dropped. An unmatched line cannot have its stock deducted, so a silently discarded
 * one is an order that quietly never affected inventory.
 */
function Matching({ session }: { session: Session }) {
  const queryClient = useQueryClient();
  const [variantIds, setVariantIds] = useState<Record<string, string>>({});

  const candidates = useQuery({
    queryKey: ["mapping-candidates"],
    queryFn: api.matching.candidates,
    refetchInterval: POLL_MS,
  });

  const resolve = useMutation({
    mutationFn: ({ candidateId, variantId }: { candidateId: string; variantId: string }) =>
      api.matching.resolve(candidateId, variantId, session.userId),
    onSuccess: () => {
      toast.success("Mapping resolved");
      queryClient.invalidateQueries({ queryKey: ["mapping-candidates"] });
    },
    onError: (error: Error) => toast.error(error.message),
  });

  const columns: Column<MappingCandidate>[] = [
    { key: "title", header: "Channel item", render: (row) => row.title ?? row.channel_variant_id },
    { key: "barcode", header: "Barcode", render: (row) => row.barcode ?? "—" },
    {
      key: "candidates",
      header: "Suggested",
      render: (row) => <code className="text-xs">{row.candidate_variant_ids ?? "—"}</code>,
    },
    {
      key: "resolve",
      header: "Resolve to variant",
      render: (row) => (
        <div className="flex gap-2">
          <input
            data-testid={`variant-input-${row.id}`}
            value={variantIds[row.id] ?? ""}
            onChange={(e) => setVariantIds((current) => ({ ...current, [row.id]: e.target.value }))}
            placeholder="variant id"
            className="w-64 rounded border bg-background px-2 py-1 text-xs"
          />
          <button
            type="button"
            data-testid={`resolve-${row.id}`}
            disabled={!variantIds[row.id] || resolve.isPending}
            onClick={() => resolve.mutate({ candidateId: row.id, variantId: variantIds[row.id] })}
            className="rounded border px-2 py-1 text-xs disabled:opacity-40"
          >
            Resolve
          </button>
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Matching"
        description="Channel items the hub could not attach to a variant. Nothing here was dropped — that is the point."
      />
      <DataTable
        rows={candidates.data}
        columns={columns}
        empty="Everything the channels sent matched a variant."
        testId="candidates-table"
        rowKey={(row) => row.id}
      />
    </>
  );
}
