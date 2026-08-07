import { AlertTriangle, Ban, CheckCircle2, Circle, HelpCircle, type LucideIcon } from "lucide-react";

/**
 * The one status vocabulary every screen draws from (ui-plani.md §6: "durum renkleri
 * yalnızca durum için kullanılır" — a single dictionary, not one invented per screen).
 * Tied to the --durum-* tokens in globals.css, which are fixed across light/dark and
 * never repurposed as chart series colors.
 *
 * Colorblind safety is structural, not optional: every tone pairs a distinct icon shape
 * with its color, so removing color still leaves the status legible.
 */
export type StatusTone = "good" | "warning" | "serious" | "critical" | "neutral";

export const TONE_STYLE: Record<StatusTone, { icon: LucideIcon; text: string; bg: string; ring: string }> = {
  good: { icon: CheckCircle2, text: "text-durum-iyi", bg: "bg-durum-iyi/10", ring: "ring-durum-iyi/30" },
  warning: { icon: Circle, text: "text-durum-uyari", bg: "bg-durum-uyari/10", ring: "ring-durum-uyari/30" },
  serious: { icon: Ban, text: "text-durum-ciddi", bg: "bg-durum-ciddi/10", ring: "ring-durum-ciddi/30" },
  critical: { icon: AlertTriangle, text: "text-durum-kritik", bg: "bg-durum-kritik/10", ring: "ring-durum-kritik/30" },
  // Deliberately colorless. This is the state a value is in before reconcile has confirmed
  // it either way — coloring it like a validated status would claim more than is known.
  neutral: { icon: HelpCircle, text: "text-muted-foreground", bg: "bg-muted", ring: "ring-border" },
};
