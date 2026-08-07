import { AlertTriangle, Ban, CheckCircle2, Clock, HelpCircle, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * KanalDurumCipi (ui-plani.md §5.1) — the most expensive design decision in the plan:
 * every stock/price value has two realities, ours and the channel's, and this chip is
 * the only place that gap is ever rendered. Six states, one meaning each, used
 * identically on every screen that shows a per-channel value.
 */
export type ChannelSyncStatus = "SYNCED" | "PENDING" | "SENDING" | "ERROR" | "UNKNOWN" | "CIRCUIT_OPEN";

const CONFIG: Record<ChannelSyncStatus, { icon: typeof CheckCircle2; label: string; className: string; spin?: boolean }> = {
  SYNCED: { icon: CheckCircle2, label: "Synced", className: "text-durum-iyi" },
  PENDING: { icon: Clock, label: "Pending", className: "text-durum-uyari" },
  SENDING: { icon: Loader2, label: "Sending", className: "text-durum-uyari", spin: true },
  ERROR: { icon: AlertTriangle, label: "Error", className: "text-durum-kritik" },
  // Pushed, but reconcile has not confirmed it landed. Shown identically to "unknown"
  // rather than borrowing SYNCED's color — an unconfirmed push is not a synced one.
  UNKNOWN: { icon: HelpCircle, label: "Unconfirmed", className: "text-muted-foreground" },
  CIRCUIT_OPEN: { icon: Ban, label: "Circuit open", className: "text-durum-ciddi" },
};

export function ChannelStatusChip({
  status,
  detail,
  testId,
  className,
}: {
  status: ChannelSyncStatus;
  /** Short qualifier shown after the label, e.g. "gen 41" or "2m ago". */
  detail?: string;
  testId?: string;
  className?: string;
}) {
  const { icon: Icon, label, className: toneClass, spin } = CONFIG[status];
  return (
    <span
      data-testid={testId}
      title={detail ? `${label} — ${detail}` : label}
      className={cn("inline-flex items-center gap-1 text-xs font-medium", toneClass, className)}
    >
      <Icon className={cn("size-3.5", spin && "animate-spin")} aria-hidden="true" />
      <span>{label}</span>
      {detail && <span className="text-muted-foreground">· {detail}</span>}
    </span>
  );
}
