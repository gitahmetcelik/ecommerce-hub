import { cn } from "@/lib/utils";

/**
 * IlerlemeSeridi (ui-plani.md §5.9/§6) — backfill, bulk push, reconcile. Shown with the
 * raw counts, not just a percentage: "3,200/5,000 products" tells an operator whether the
 * gap they're seeing is expected (backfill still running) or a real problem.
 */
export function ProgressBar({
  value,
  max,
  label,
  testId,
}: {
  value: number;
  max: number;
  label?: string;
  testId?: string;
}) {
  const pct = max > 0 ? Math.min(100, Math.round((value / max) * 100)) : 0;
  return (
    <div data-testid={testId} className="w-full">
      {label && (
        <div className="mb-1 flex justify-between text-xs text-muted-foreground">
          <span>{label}</span>
          <span>
            {value.toLocaleString()}/{max.toLocaleString()} · {pct}%
          </span>
        </div>
      )}
      <div
        role="progressbar"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        className="h-1.5 w-full overflow-hidden rounded-full bg-muted"
      >
        <div
          className={cn("h-full rounded-full bg-durum-uyari transition-all", pct >= 100 && "bg-durum-iyi")}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}
