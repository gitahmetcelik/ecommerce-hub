"use client";

import { Button } from "@/components/ui/button";

/**
 * Plan v5 Faz 7 §7.2 point 5 / §U2: "‹ 1 2 3 … 84 › 5.000 varyant · sayfa 1/84" — every
 * paginated list gets this same footer. Page is zero-based to match the API; shown
 * one-based, matching ui-plani §5.8's "the page number is in the URL" spirit (the caller
 * owns the URL sync, this component only owns prev/next + the count).
 */
export function Pagination({
  page,
  size,
  total,
  onPageChange,
  itemLabel = "item",
  testId = "pagination",
}: {
  page: number;
  size: number;
  total: number;
  onPageChange: (page: number) => void;
  itemLabel?: string;
  testId?: string;
}) {
  const pageCount = Math.max(1, Math.ceil(total / size));
  const current = page + 1;

  if (total === 0) return null;

  return (
    <div data-testid={testId} className="mt-4 flex items-center justify-between text-sm text-muted-foreground">
      <span data-testid={`${testId}-summary`}>
        {total.toLocaleString()} {itemLabel}
        {total === 1 ? "" : "s"} · page {current}/{pageCount}
      </span>
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={page <= 0}
          onClick={() => onPageChange(page - 1)}
          data-testid={`${testId}-prev`}
        >
          ‹ Prev
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={current >= pageCount}
          onClick={() => onPageChange(page + 1)}
          data-testid={`${testId}-next`}
        >
          Next ›
        </Button>
      </div>
    </div>
  );
}
