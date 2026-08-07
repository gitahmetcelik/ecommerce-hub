"use client";

import { useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";

/**
 * OnayDiyalogu (ui-plani.md §5.4) — the pattern for irreversible actions (refund, return
 * acceptance, manual stock correction). `impact` is required, not optional: the dialog's
 * whole point is stating what will happen in terms of its real-world effect ("1,240 ₺
 * will be refunded — cannot be undone"), not asking "are you sure?" with no content.
 *
 * On confirm, the button moves to a pending state immediately and stays disabled until
 * the call resolves — a second click cannot fire a second refund.
 */
export function ConfirmDialog({
  trigger,
  title,
  impact,
  confirmLabel = "Confirm",
  destructive = true,
  onConfirm,
  testId,
  requireText,
}: {
  trigger: ReactNode;
  title: string;
  impact: string;
  confirmLabel?: string;
  destructive?: boolean;
  onConfirm: (text?: string) => Promise<void> | void;
  testId?: string;
  /** When set, the dialog collects a required free-text field (e.g. a dismiss reason) before confirm is enabled. */
  requireText?: { label: string; placeholder?: string };
}) {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [text, setText] = useState("");

  const textMissing = Boolean(requireText) && text.trim().length === 0;

  async function handleConfirm() {
    setPending(true);
    try {
      await onConfirm(requireText ? text.trim() : undefined);
      setOpen(false);
      setText("");
    } finally {
      setPending(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !pending && setOpen(next)}>
      <DialogTrigger render={trigger as React.ReactElement} />
      <DialogContent data-testid={testId}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription data-testid={testId ? `${testId}-impact` : undefined}>{impact}</DialogDescription>
        </DialogHeader>
        {requireText && (
          <Textarea
            autoFocus
            placeholder={requireText.placeholder}
            aria-label={requireText.label}
            value={text}
            onChange={(e) => setText(e.target.value)}
            data-testid={testId ? `${testId}-reason` : undefined}
          />
        )}
        <DialogFooter>
          <Button type="button" variant="outline" disabled={pending} onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            type="button"
            variant={destructive ? "destructive" : "default"}
            disabled={pending || textMissing}
            onClick={handleConfirm}
            data-testid={testId ? `${testId}-confirm` : undefined}
          >
            {pending ? "Working…" : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
