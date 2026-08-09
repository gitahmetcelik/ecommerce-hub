"use client";

import { useEffect, useState } from "react";
import { useQuery, useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { api } from "@/lib/api";
import type { VariantChannelSummary } from "@/lib/types";

/**
 * Plan §U4 — §5.2's first real write path: a list price plus per-channel exceptions.
 * The success toast deliberately does not say "Saved" (ui-plani §5.2) — it says the
 * center write happened AND that channels are now catching up, because those are two
 * different facts and conflating them is what used to generate "why hasn't my price
 * changed on Trendyol" support tickets.
 */
export function PriceEditDialog({
  variantId,
  listPrice,
  currency,
  vatRate,
  channels,
  onClose,
  onDone,
}: {
  variantId: string;
  listPrice: string | number | null;
  currency: string | null;
  vatRate: string | number | null;
  channels: VariantChannelSummary[];
  onClose: () => void;
  onDone: () => void;
}) {
  const priceDetail = useQuery({ queryKey: ["price", variantId], queryFn: () => api.prices.get(variantId) });

  const [amount, setAmount] = useState(listPrice !== null ? String(listPrice) : "");
  const [curr, setCurr] = useState(currency ?? "USD");
  const [vat, setVat] = useState(vatRate !== null ? String(vatRate) : "0");
  const [overrides, setOverrides] = useState<Record<string, { enabled: boolean; amount: string }>>({});

  // Prefill overrides once the current channel prices load.
  useEffect(() => {
    if (!priceDetail.data) return;
    const next: Record<string, { enabled: boolean; amount: string }> = {};
    for (const c of channels) {
      const existing = priceDetail.data.channelPrices.find((cp) => cp.channel_connection_id === c.channelConnectionId);
      next[c.channelConnectionId] = { enabled: Boolean(existing), amount: existing?.price ?? amount };
    }
    setOverrides(next);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [priceDetail.data]);

  const save = useMutation({
    mutationFn: async () => {
      await api.prices.setListPrice(variantId, amount, curr, vat);
      for (const c of channels) {
        const state = overrides[c.channelConnectionId];
        if (!state) continue;
        if (state.enabled) {
          await api.prices.setChannelPrice(variantId, c.channelConnectionId, state.amount, null);
        } else if (c.hasChannelPriceOverride) {
          await api.prices.clearChannelPrice(variantId, c.channelConnectionId);
        }
      }
    },
    onSuccess: onDone,
  });

  return (
    <Dialog open onOpenChange={(next) => !next && !save.isPending && onClose()}>
      <DialogContent data-testid="price-edit-dialog">
        <DialogHeader>
          <DialogTitle>Edit price</DialogTitle>
          <DialogDescription>This change will be sent to {channels.length} channel{channels.length === 1 ? "" : "s"}.</DialogDescription>
        </DialogHeader>

        <div className="space-y-3">
          <div className="flex items-end gap-2">
            <div>
              <Label htmlFor="price-amount">List price</Label>
              <Input
                id="price-amount"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                data-testid="price-amount"
                className="mt-1 w-28"
              />
            </div>
            <Input value={curr} onChange={(e) => setCurr(e.target.value)} data-testid="price-currency" className="w-16" />
            <div>
              <Label htmlFor="price-vat">VAT %</Label>
              <Input id="price-vat" value={vat} onChange={(e) => setVat(e.target.value)} data-testid="price-vat" className="mt-1 w-20" />
            </div>
          </div>

          {channels.length > 0 && (
            <div>
              <Label>Channel exceptions</Label>
              <div className="mt-1.5 space-y-1.5">
                {channels.map((c) => {
                  const state = overrides[c.channelConnectionId] ?? { enabled: false, amount };
                  return (
                    <div key={c.channelConnectionId} className="flex items-center gap-2 text-sm">
                      <label className="flex w-32 items-center gap-1.5">
                        <input
                          type="checkbox"
                          checked={state.enabled}
                          data-testid={`price-override-toggle-${c.channelConnectionId}`}
                          onChange={(e) =>
                            setOverrides((current) => ({
                              ...current,
                              [c.channelConnectionId]: { ...state, enabled: e.target.checked },
                            }))
                          }
                        />
                        {c.channelType}
                      </label>
                      <Input
                        disabled={!state.enabled}
                        value={state.amount}
                        data-testid={`price-override-amount-${c.channelConnectionId}`}
                        onChange={(e) =>
                          setOverrides((current) => ({
                            ...current,
                            [c.channelConnectionId]: { ...state, amount: e.target.value },
                          }))
                        }
                        className="w-24"
                      />
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" disabled={save.isPending} onClick={onClose}>
            Cancel
          </Button>
          <Button type="button" disabled={save.isPending || !amount} onClick={() => save.mutate()} data-testid="price-save">
            {save.isPending ? "Working…" : "Save"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
