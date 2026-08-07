import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

function formatDuration(ms: number): string {
  const totalMinutes = Math.round(Math.abs(ms) / 60000);
  const days = Math.floor(totalMinutes / 1440);
  const hours = Math.floor((totalMinutes % 1440) / 60);
  const minutes = totalMinutes % 60;
  if (days > 0) return `${days}d${hours > 0 ? ` ${hours}h` : ""}`;
  if (hours > 0) return `${hours}h${minutes > 0 ? ` ${minutes}m` : ""}`;
  if (minutes > 0) return `${minutes}m`;
  return "<1m";
}

/** How long ago `iso` was — for rows with no deadline, this is the only urgency signal there is. */
export function formatAge(iso: string): string {
  return formatDuration(Date.now() - new Date(iso).getTime());
}

/** Time left until `iso`, or "Overdue by …" once it has passed. */
export function formatRemaining(iso: string): { label: string; overdue: boolean } {
  const diff = new Date(iso).getTime() - Date.now();
  if (diff <= 0) return { label: `Overdue by ${formatDuration(diff)}`, overdue: true };
  return { label: `${formatDuration(diff)} left`, overdue: false };
}
