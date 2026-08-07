"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertCircle,
  AlertTriangle,
  Clock,
  HelpCircle,
  KeyRound,
  PackageX,
  SearchX,
  TimerReset,
  type LucideIcon,
} from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/hub/confirm-dialog";
import { DecisionCard } from "@/components/hub/decision-card";
import { EmptyState } from "@/components/hub/empty-state";
import { PageHeader } from "@/components/data-table";
import { RequireSession } from "@/components/require-session";
import { api } from "@/lib/api";
import { hasRole, type Session } from "@/lib/auth";
import { formatAge, formatRemaining } from "@/lib/utils";
import type { StatusTone } from "@/lib/status-tones";
import type { OperatorQueueItem } from "@/lib/types";

const POLL_MS = 5000;

const TYPE_ICON: Record<string, LucideIcon> = {
  RETURN_APPROVAL: Clock,
  RETURN_UNRESOLVABLE: AlertTriangle,
  UNMATCHED_CATALOG_ITEM: SearchX,
  DISPATCH_TIMEOUT: TimerReset,
  CHANNEL_CREDENTIALS_INVALID: KeyRound,
  INTENT_AMBIGUOUS: HelpCircle,
  ORDER_ITEM_ESCALATION: PackageX,
};

export default function QueuePage() {
  return <RequireSession>{(session) => <Queue session={session} />}</RequireSession>;
}

/**
 * ui-plani.md §4.1: the operator's home screen, and its own priority order — nearest
 * deadline first, then oldest first. Every row is either a decision made in place or a
 * single link to a workspace, never both (Plan §3: "gürültülü eskalasyon, sessiz kayıp
 * yok" — the same rule that keeps a dismiss from disappearing silently keeps a row from
 * offering two half-actions instead of one real one).
 */
function Queue({ session }: { session: Session }) {
  const queryClient = useQueryClient();
  const queue = useQuery({ queryKey: ["operator-queue"], queryFn: api.operator.queue, refetchInterval: POLL_MS });

  const canDecideReturns = hasRole(session, "OPERATOR");

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ["operator-queue"] });
  }

  const approveReturn = useMutation({
    mutationFn: (id: string) => api.returns.approve(id),
    onSuccess: () => {
      toast.success("Return approved");
      refresh();
    },
    onError: (error: Error) => toast.error(error.message),
  });

  const rejectReturn = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => api.returns.reject(id, reason),
    onSuccess: () => {
      toast.success("Return rejected");
      refresh();
    },
    onError: (error: Error) => toast.error(error.message),
  });

  const dismiss = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => api.operator.dismiss(id, reason),
    onSuccess: () => {
      toast.success("Dismissed");
      refresh();
    },
    onError: (error: Error) => toast.error(error.message),
  });

  const items = queue.data ?? [];

  return (
    <>
      <PageHeader
        title="Operator queue"
        description="Work that needs a person, ranked by how soon it needs one. Separate from the engine's DLQ, which holds tasks that failed rather than decisions nobody has made."
      />

      {items.length === 0 ? (
        queue.isLoading ? (
          <p className="py-8 text-sm text-muted-foreground">Loading…</p>
        ) : (
          <EmptyState
            variant="success"
            title="Nothing is waiting on a person."
            description="New work will show up here the moment something needs a decision."
            testId="queue-empty"
          />
        )
      ) : (
        <div data-testid="queue-list" className="overflow-hidden rounded-lg border bg-background">
          {items.map((item) => (
            <QueueRow
              key={item.id}
              item={item}
              canDecideReturns={canDecideReturns}
              onApprove={(id) => approveReturn.mutate(id)}
              onReject={(id, reason) => rejectReturn.mutate({ id, reason })}
              onDismiss={(id, reason) => dismiss.mutate({ id, reason })}
              approvePending={approveReturn.isPending && approveReturn.variables === item.reference_id}
            />
          ))}
        </div>
      )}
    </>
  );
}

function urgency(item: OperatorQueueItem): { timeLabel: string; timeTone: StatusTone } {
  if (item.deadline_at) {
    const remaining = formatRemaining(item.deadline_at);
    if (remaining.overdue) return { timeLabel: remaining.label, timeTone: "critical" };
    const hoursLeft = (new Date(item.deadline_at).getTime() - Date.now()) / 3_600_000;
    return { timeLabel: remaining.label, timeTone: hoursLeft < 2 ? "critical" : hoursLeft < 12 ? "warning" : "neutral" };
  }
  return { timeLabel: `${formatAge(item.created_at)} ago`, timeTone: "neutral" };
}

function QueueRow({
  item,
  canDecideReturns,
  onApprove,
  onReject,
  onDismiss,
  approvePending,
}: {
  item: OperatorQueueItem;
  canDecideReturns: boolean;
  onApprove: (id: string) => void;
  onReject: (id: string, reason: string) => void;
  onDismiss: (id: string, reason: string) => void;
  approvePending: boolean;
}) {
  const Icon = TYPE_ICON[item.type] ?? AlertCircle;
  const { timeLabel, timeTone } = urgency(item);
  const testId = `queue-row-${item.id}`;

  const base = {
    icon: <Icon className="size-5" />,
    timeLabel,
    timeTone,
    title: item.description,
    testId,
  } as const;

  if (item.type === "RETURN_APPROVAL" && item.reference_id) {
    const returnId = item.reference_id;
    return (
      <DecisionCard
        {...base}
        kind="decision"
        actions={[
          <Button
            key="approve"
            type="button"
            size="sm"
            disabled={!canDecideReturns || approvePending}
            onClick={() => onApprove(returnId)}
            data-testid={`queue-approve-${item.id}`}
          >
            {approvePending ? "…" : "Approve"}
          </Button>,
          <ConfirmDialog
            key="reject"
            trigger={
              <Button type="button" size="sm" variant="outline" disabled={!canDecideReturns}>
                Reject
              </Button>
            }
            title="Reject this return?"
            impact="The customer will see this return rejected on the channel. This cannot be undone from here."
            confirmLabel="Reject"
            requireText={{ label: "Reason", placeholder: "Why is this being rejected?" }}
            onConfirm={(reason) => onReject(returnId, reason ?? "")}
            testId={`queue-reject-${item.id}`}
          />,
        ]}
      />
    );
  }

  if (item.type === "UNMATCHED_CATALOG_ITEM") {
    return <DecisionCard {...base} kind="navigate" href="/matching" actionLabel="Resolve →" />;
  }

  return (
    <DecisionCard
      {...base}
      kind="decision"
      actions={[
        <ConfirmDialog
          key="dismiss"
          trigger={
            <Button type="button" size="sm" variant="outline">
              Dismiss
            </Button>
          }
          title="Dismiss this item?"
          impact="This removes it from the queue. Use this once you've handled it outside the dashboard — the reason is kept on the audit log."
          confirmLabel="Dismiss"
          destructive={false}
          requireText={{ label: "Reason", placeholder: "What did you do to resolve this?" }}
          onConfirm={(reason) => onDismiss(item.id, reason ?? "")}
          testId={`queue-dismiss-${item.id}`}
        />,
      ]}
    />
  );
}
