import Link from "next/link";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { TONE_STYLE, type StatusTone } from "@/lib/status-tones";

type BaseProps = {
  icon: ReactNode;
  /** "44s", "2d", "kalan 4 saat" — whatever is most useful for this row's urgency. */
  timeLabel: string;
  timeTone?: StatusTone;
  title: string;
  description?: string;
  testId?: string;
};

type DecisionAction = { label: string; onClick: () => void; variant?: "default" | "outline" | "destructive"; disabled?: boolean; pending?: boolean; testId?: string };

/**
 * KararKarti (ui-plani.md §4.1) — the operator queue's only building block. The type
 * enforces the plan's rule directly: a row is either a decision (buttons act in place)
 * or a navigation (one link to a workspace) — "asla ikisi birden" (never both).
 */
type Props = BaseProps & ({ kind: "decision"; actions: DecisionAction[] } | { kind: "navigate"; href: string; actionLabel: string });

export function DecisionCard(props: Props) {
  const { icon, timeLabel, timeTone = "neutral", title, description, testId } = props;
  const toneClass = TONE_STYLE[timeTone].text;

  return (
    <div
      data-testid={testId}
      className="flex items-center gap-4 border-b px-4 py-3 last:border-0 hover:bg-muted/30"
    >
      <span className={cn("shrink-0", toneClass)} aria-hidden="true">
        {icon}
      </span>
      <span className={cn("w-16 shrink-0 text-sm font-medium tabular-nums", toneClass)}>{timeLabel}</span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{title}</p>
        {description && <p className="truncate text-xs text-muted-foreground">{description}</p>}
      </div>
      <div className="flex shrink-0 gap-2">
        {props.kind === "decision"
          ? props.actions.map((action) => (
              <Button
                key={action.label}
                type="button"
                size="sm"
                variant={action.variant ?? "outline"}
                disabled={action.disabled || action.pending}
                onClick={action.onClick}
                data-testid={action.testId}
              >
                {action.pending ? "…" : action.label}
              </Button>
            ))
          : (
              <Button type="button" size="sm" variant="outline" render={<Link href={props.href} />}>
                {props.actionLabel}
              </Button>
            )}
      </div>
    </div>
  );
}
