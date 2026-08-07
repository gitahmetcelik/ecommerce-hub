import { cn } from "@/lib/utils";
import { TONE_STYLE, type StatusTone } from "@/lib/status-tones";

/**
 * DurumRozeti (ui-plani.md §4/§6): the single way any status string is rendered. Color
 * carries the tone, but the icon shape and the text label carry it too — never color alone.
 */
export function StatusBadge({
  tone,
  label,
  testId,
  className,
}: {
  tone: StatusTone;
  label: string;
  testId?: string;
  className?: string;
}) {
  const style = TONE_STYLE[tone];
  const Icon = style.icon;
  return (
    <span
      data-testid={testId}
      className={cn(
        "inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs font-medium ring-1",
        style.text,
        style.bg,
        style.ring,
        className,
      )}
    >
      <Icon className="size-3" aria-hidden="true" />
      {label}
    </span>
  );
}
