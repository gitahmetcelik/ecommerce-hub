import type { ReactNode } from "react";
import { cn } from "@/lib/utils";
import { TONE_STYLE, type StatusTone } from "@/lib/status-tones";

export type TimelineEntry = {
  id: string;
  at: string;
  /** "webhook" / "reconcile" / "manual" — Plan §3's "each row shows its source". */
  source?: string;
  label: string;
  detail?: ReactNode;
  tone?: StatusTone;
};

/**
 * ZamanCizelgesi (ui-plani.md §4.4/§6) — event → hub transition → stock movement → push,
 * one vertical list. Plan §3's trace-propagation acceptance criterion ("a single trace_id
 * should be able to query a whole journey") is this component's reason to exist.
 */
export function Timeline({ entries, testId }: { entries: TimelineEntry[]; testId?: string }) {
  if (entries.length === 0) {
    return <p className="py-4 text-sm text-muted-foreground">No history yet.</p>;
  }

  return (
    <ol data-testid={testId} className="relative border-l pl-4">
      {entries.map((entry) => {
        const tone = entry.tone ?? "neutral";
        const dotClass = TONE_STYLE[tone].text;
        return (
          <li key={entry.id} className="mb-4 last:mb-0">
            <span className={cn("absolute -left-[3.5px] mt-1.5 size-[7px] rounded-full bg-current", dotClass)} />
            <div className="flex flex-wrap items-baseline gap-x-2 text-sm">
              <span className="font-medium">{entry.label}</span>
              {entry.source && <span className="text-xs text-muted-foreground">via {entry.source}</span>}
              <span className="text-xs text-muted-foreground">{new Date(entry.at).toLocaleString()}</span>
            </div>
            {entry.detail && <div className="mt-0.5 text-xs text-muted-foreground">{entry.detail}</div>}
          </li>
        );
      })}
    </ol>
  );
}
