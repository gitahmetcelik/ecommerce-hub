import type { ReactNode } from "react";
import { CheckCircle2, Inbox, SearchX } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * BosDurum (ui-plani.md §4.1/§6): "boş durum bir başarı ekranıdır, boş tablo değil" — an
 * empty queue is not the same message as an empty search result, and neither is the same
 * as a screen that has never had data. Three variants, three different messages.
 */
export function EmptyState({
  variant,
  title,
  description,
  action,
  testId,
}: {
  variant: "success" | "filtered" | "no-data";
  title: string;
  description?: string;
  action?: ReactNode;
  testId?: string;
}) {
  const Icon = variant === "success" ? CheckCircle2 : variant === "filtered" ? SearchX : Inbox;
  return (
    <div
      data-testid={testId}
      className={cn(
        "flex flex-col items-center gap-2 rounded-lg border border-dashed py-12 text-center",
        variant === "success" && "text-durum-iyi",
      )}
    >
      <Icon className="size-8" aria-hidden="true" />
      <p className="text-sm font-medium text-foreground">{title}</p>
      {description && <p className="max-w-sm text-sm text-muted-foreground">{description}</p>}
      {action}
    </div>
  );
}
