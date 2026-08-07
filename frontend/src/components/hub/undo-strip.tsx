"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";

const WINDOW_MS = 12000;

type PendingAction = { id: string; label: string; commit: () => Promise<void> | void };

/**
 * GeriAlSeridi (ui-plani.md §4.2) — "an undo window beats a confirm dialog in a fast
 * flow; a dialog is a wall you hit on every decision." The commit is deferred client-side
 * until the window elapses, so this works with backend actions that have no separate
 * undo endpoint: the call simply has not happened yet while the strip is showing.
 *
 * One pending action at a time, matching the single strip in the mockup — starting a
 * second undoable action commits whatever was already waiting.
 */
export function useUndoable() {
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [remainingMs, setRemainingMs] = useState(WINDOW_MS);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const clearTimers = () => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    if (tickRef.current) clearInterval(tickRef.current);
  };

  const commitNow = useCallback((action: PendingAction) => {
    clearTimers();
    setPending(null);
    void action.commit();
  }, []);

  const enqueue = useCallback(
    (action: PendingAction) => {
      // Only one undoable action in flight — the one already waiting commits immediately.
      setPending((current) => {
        if (current) void current.commit();
        return action;
      });
      clearTimers();
      const startedAt = Date.now();
      setRemainingMs(WINDOW_MS);
      tickRef.current = setInterval(() => {
        setRemainingMs(Math.max(0, WINDOW_MS - (Date.now() - startedAt)));
      }, 200);
      timeoutRef.current = setTimeout(() => commitNow(action), WINDOW_MS);
    },
    [commitNow],
  );

  const undo = useCallback(() => {
    clearTimers();
    setPending(null);
  }, []);

  useEffect(() => clearTimers, []);

  return { pending, remainingMs, enqueue, undo };
}

export function UndoStrip({
  pending,
  remainingMs,
  onUndo,
}: {
  pending: { label: string } | null;
  remainingMs: number;
  onUndo: () => void;
}) {
  if (!pending) return null;

  return (
    <div
      data-testid="undo-strip"
      className="fixed bottom-4 left-1/2 z-40 flex -translate-x-1/2 items-center gap-3 rounded-lg bg-foreground px-4 py-2 text-sm text-background shadow-lg"
    >
      <span>{pending.label}</span>
      <Button
        type="button"
        size="sm"
        variant="secondary"
        onClick={onUndo}
        data-testid="undo-strip-undo"
      >
        Undo ({Math.ceil(remainingMs / 1000)}s)
      </Button>
    </div>
  );
}
