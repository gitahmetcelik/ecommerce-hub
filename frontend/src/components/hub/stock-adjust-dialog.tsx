"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { api } from "@/lib/api";
import { STOCK_ADJUSTMENT_REASONS, type StockAdjustmentReason } from "@/lib/types";

const REASON_LABEL: Record<StockAdjustmentReason, string> = {
  COUNT_DISCREPANCY: "Count discrepancy",
  DAMAGE: "Damage",
  LOSS: "Loss",
  WAREHOUSE_RECEIPT: "Warehouse receipt",
  OTHER: "Other",
};

/**
 * Plan §U5 — the irreversible-action pattern: the impact is stated before commit, the
 * reason is a required enum plus a free note (enum alone loses the real reason, free
 * text alone cannot be reported on), and there is deliberately no undo strip — a
 * correction is undone by a second correction, not by reverting this one silently.
 */
export function StockAdjustDialog({
  variantId,
  currentOnHand,
  onClose,
  onDone,
}: {
  variantId: string;
  currentOnHand: number;
  onClose: () => void;
  onDone: () => void;
}) {
  const [newOnHand, setNewOnHand] = useState(currentOnHand);
  const [reason, setReason] = useState<StockAdjustmentReason | "">("");
  const [note, setNote] = useState("");

  const diff = newOnHand - currentOnHand;
  const canSubmit = reason !== "" && Number.isFinite(newOnHand);

  const adjust = useMutation({
    mutationFn: () =>
      api.variants.adjustStock(variantId, {
        expectedOnHand: currentOnHand,
        newOnHand,
        reason: reason as StockAdjustmentReason,
        note,
      }),
    onSuccess: () => {
      toast.success("Stock corrected");
      onDone();
    },
    onError: (error: Error) => toast.error(error.message),
  });

  return (
    <Dialog open onOpenChange={(next) => !next && !adjust.isPending && onClose()}>
      <DialogContent data-testid="stock-adjust-dialog">
        <DialogHeader>
          <DialogTitle>Correct stock</DialogTitle>
          <DialogDescription>Physical count right now: {currentOnHand}</DialogDescription>
        </DialogHeader>

        <div className="space-y-3">
          <div>
            <Label htmlFor="new-on-hand">New value</Label>
            <div className="mt-1 flex items-center gap-2">
              <Input
                id="new-on-hand"
                type="number"
                value={newOnHand}
                onChange={(e) => setNewOnHand(Number(e.target.value))}
                data-testid="stock-adjust-new-value"
                className="w-28"
              />
              <span className={"text-sm " + (diff >= 0 ? "text-durum-iyi" : "text-durum-kritik")} data-testid="stock-adjust-diff">
                {diff >= 0 ? "+" : ""}
                {diff}
              </span>
            </div>
          </div>

          <div>
            <Label>Reason (required)</Label>
            <div className="mt-1.5 grid grid-cols-2 gap-1.5">
              {STOCK_ADJUSTMENT_REASONS.map((r) => (
                <label key={r} className="flex items-center gap-1.5 text-sm">
                  <input
                    type="radio"
                    name="adjustment-reason"
                    value={r}
                    checked={reason === r}
                    onChange={() => setReason(r)}
                    data-testid={`stock-adjust-reason-${r}`}
                  />
                  {REASON_LABEL[r]}
                </label>
              ))}
            </div>
          </div>

          <Textarea
            placeholder="Optional note"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            data-testid="stock-adjust-note"
          />

          <p className="text-xs text-muted-foreground">
            This correction cannot be undone and will be pushed to every channel selling this variant. It is recorded
            in the audit log under your name.
          </p>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" disabled={adjust.isPending} onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="button"
            variant="destructive"
            disabled={!canSubmit || adjust.isPending}
            onClick={() => adjust.mutate()}
            data-testid="stock-adjust-confirm"
          >
            {adjust.isPending ? "Working…" : "Correct"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
