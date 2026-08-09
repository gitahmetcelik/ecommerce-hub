"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { PageHeader } from "@/components/data-table";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { PermissionGate } from "@/components/hub/permission-gate";
import { RequireSession } from "@/components/require-session";
import { RotateCredentialsDialog } from "@/components/hub/rotate-credentials-dialog";
import { api } from "@/lib/api";
import { hasRole, type Session } from "@/lib/auth";

const POLL_MS = 5000;

export default function ChannelDetailPage() {
  return <RequireSession>{(session) => <ChannelDetail session={session} />}</RequireSession>;
}

/** Plan §8.2 point 6 / §U6's post-connect screen, generalised into a permanent detail view. */
function ChannelDetail({ session }: { session: Session }) {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const isAdmin = hasRole(session, "ADMIN");
  const [showRotate, setShowRotate] = useState(false);
  const [reconcileMinutes, setReconcileMinutes] = useState("");
  const [allocationPriority, setAllocationPriority] = useState("");

  const channel = useQuery({
    queryKey: ["channel", id],
    queryFn: () => api.channels.get(id),
    refetchInterval: POLL_MS,
  });

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ["channel", id] });
  }

  const triggerBackfill = useMutation({
    mutationFn: () => api.channels.triggerBackfill(id),
    onSuccess: () => {
      toast.success("Backfill triggered — the worker picks it up on its next sweep.");
      refresh();
    },
    onError: (error: Error) => toast.error(error.message),
  });

  const saveSettings = useMutation({
    mutationFn: () =>
      api.channels.updateSettings(
        id,
        reconcileMinutes ? Number(reconcileMinutes) : undefined,
        allocationPriority ? Number(allocationPriority) : undefined,
      ),
    onSuccess: () => {
      toast.success("Settings updated.");
      setReconcileMinutes("");
      setAllocationPriority("");
      refresh();
    },
    onError: (error: Error) => toast.error(error.message),
  });

  if (channel.isLoading || !channel.data) {
    return <p className="py-8 text-sm text-muted-foreground">Loading…</p>;
  }

  const c = channel.data;
  const backfillLabel = backfillProgressLabel(c.backfill_status);

  return (
    <>
      <PageHeader title={`${c.channel_type} · ${c.id.slice(0, 8)}`} />

      <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
        <section className="rounded-lg border bg-background p-4">
          <h2 className="mb-3 text-lg font-medium">Health</h2>
          <dl className="space-y-1.5 text-sm">
            <Row label="Status">
              <span className={c.status === "ACTIVE" ? "" : "font-medium text-destructive"}>{c.status}</span>
            </Row>
            <Row label="Failure streak">{c.consecutive_failures}</Row>
            <Row label="Circuit until">
              {c.circuit_open_until ? new Date(c.circuit_open_until).toLocaleString() : "—"}
            </Row>
            <Row label="Last failure">{c.last_failure_reason ?? "—"}</Row>
            <Row label="Last order sync">{c.last_order_sync_at ? new Date(c.last_order_sync_at).toLocaleString() : "never"}</Row>
            <Row label="Last return sync">{c.last_return_sync_at ? new Date(c.last_return_sync_at).toLocaleString() : "never"}</Row>
          </dl>

          {c.status === "CREDENTIALS_INVALID" && (
            <div className="mt-4">
              <PermissionGate allowed={isAdmin} reason="ADMIN required to re-authorise a channel">
                <Button type="button" size="sm" onClick={() => setShowRotate(true)} data-testid="open-rotate-credentials">
                  Re-authorise
                </Button>
              </PermissionGate>
            </div>
          )}
          {c.status !== "CREDENTIALS_INVALID" && (
            <div className="mt-4">
              <PermissionGate allowed={isAdmin} reason="ADMIN required to rotate credentials">
                <Button type="button" size="sm" variant="outline" onClick={() => setShowRotate(true)}>
                  Rotate credentials
                </Button>
              </PermissionGate>
            </div>
          )}
        </section>

        <section className="rounded-lg border bg-background p-4">
          <h2 className="mb-3 text-lg font-medium">Import</h2>
          <p className="text-sm text-muted-foreground" data-testid="backfill-progress">
            {backfillLabel}
          </p>
          <div className="mt-4">
            <PermissionGate allowed={isAdmin} reason="ADMIN required to trigger a backfill">
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={triggerBackfill.isPending}
                onClick={() => triggerBackfill.mutate()}
                data-testid="trigger-backfill"
              >
                {triggerBackfill.isPending ? "Triggering…" : "Trigger backfill"}
              </Button>
            </PermissionGate>
          </div>
        </section>

        <section className="rounded-lg border bg-background p-4">
          <h2 className="mb-3 text-lg font-medium">Settings</h2>
          <div className="flex items-end gap-3">
            <div>
              <Label htmlFor="reconcile-minutes">Reconcile every (min)</Label>
              <Input
                id="reconcile-minutes"
                placeholder={String(c.reconcile_interval_minutes)}
                value={reconcileMinutes}
                onChange={(e) => setReconcileMinutes(e.target.value)}
                data-testid="reconcile-minutes"
                className="mt-1 w-28"
                disabled={!isAdmin}
              />
            </div>
            <div>
              <Label htmlFor="allocation-priority">Allocation priority</Label>
              <Input
                id="allocation-priority"
                placeholder={String(c.allocation_priority)}
                value={allocationPriority}
                onChange={(e) => setAllocationPriority(e.target.value)}
                data-testid="allocation-priority"
                className="mt-1 w-28"
                disabled={!isAdmin}
              />
            </div>
            <PermissionGate allowed={isAdmin} reason="ADMIN required">
              <Button
                type="button"
                size="sm"
                disabled={saveSettings.isPending || (!reconcileMinutes && !allocationPriority)}
                onClick={() => saveSettings.mutate()}
                data-testid="save-settings"
              >
                Save
              </Button>
            </PermissionGate>
          </div>
        </section>

        <section className="rounded-lg border bg-background p-4">
          <h2 className="mb-3 text-lg font-medium">Rate budget</h2>
          {c.budgets.length === 0 ? (
            <p className="text-sm text-muted-foreground">No budget rows yet — created on first call.</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-left text-muted-foreground">
                <tr>
                  <th className="pb-1 font-medium">Class</th>
                  <th className="pb-1 font-medium">Tokens</th>
                  <th className="pb-1 font-medium">Backoff until</th>
                </tr>
              </thead>
              <tbody>
                {c.budgets.map((b) => (
                  <tr key={b.budget_class}>
                    <td>{b.budget_class}</td>
                    <td>{b.tokens}</td>
                    <td>{b.backoff_until ? new Date(b.backoff_until).toLocaleString() : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>

      {showRotate && (
        <RotateCredentialsDialog
          channelConnectionId={id}
          onClose={() => setShowRotate(false)}
          onDone={() => setShowRotate(false)}
        />
      )}
    </>
  );
}

/** No total item count exists anywhere in the cursor (Plan §8.2 point 6) — a fabricated percentage would be a lie, so this reports exactly what's known: which stage, which page, done or not. */
function backfillProgressLabel(status: { catalogPage: number; catalogDone: boolean; orderPage: number; ordersDone: boolean } | null): string {
  if (!status) {
    return "Not started yet — the worker picks up any connection without a cursor on its next sweep.";
  }
  if (status.catalogDone && status.ordersDone) {
    return "Import complete.";
  }
  if (!status.catalogDone) {
    return `Importing catalog — page ${status.catalogPage} so far.`;
  }
  return `Catalog imported. Importing orders — page ${status.orderPage} so far.`;
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-muted-foreground">{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}
