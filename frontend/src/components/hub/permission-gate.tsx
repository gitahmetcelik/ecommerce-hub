import { cloneElement, isValidElement, type ReactElement } from "react";

/**
 * YetkiKalkani (ui-plani.md §5.3) — "gizleme değil, sebepli devre dışı" (disabled with a
 * reason, not hidden). Hiding a button a role can't use leaves the user asking "why don't
 * I have this" with no answer; a visible, disabled control with a reason is the answer.
 * The reason is rendered as visible text, not just a hover title — it has to work for
 * keyboard and touch users too.
 */
export function PermissionGate({
  allowed,
  reason,
  children,
}: {
  allowed: boolean;
  reason: string;
  children: ReactElement<{ disabled?: boolean }>;
}) {
  if (allowed) return children;

  const gated = isValidElement(children) ? cloneElement(children, { disabled: true }) : children;

  return (
    <span className="inline-flex items-center gap-2">
      {gated}
      <span className="text-xs text-muted-foreground">{reason}</span>
    </span>
  );
}
