"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { PageHeader } from "@/components/data-table";
import { Button } from "@/components/ui/button";
import { ChannelStatusChip, type ChannelSyncStatus } from "@/components/hub/channel-status-chip";
import { PermissionGate } from "@/components/hub/permission-gate";
import { RequireSession } from "@/components/require-session";
import { StockAdjustDialog } from "@/components/hub/stock-adjust-dialog";
import { PriceEditDialog } from "@/components/hub/price-edit-dialog";
import { api } from "@/lib/api";
import { hasRole, type Session } from "@/lib/auth";
import type { StockMovementRow, VariantChannelSummary } from "@/lib/types";

function pushStatusToChip(status: string): ChannelSyncStatus {
  switch (status) {
    case "SENT":
      return "SYNCED";
    case "PENDING":
      return "PENDING";
    case "SENDING":
      return "SENDING";
    case "STUCK":
      return "ERROR";
    default:
      return "UNKNOWN";
  }
}

function formatMoney(value: string | number | null, currency: string | null): string {
  if (value === null) return "—";
  const amount = Number(value).toFixed(2).replace(".", ",");
  return currency ? `${amount} ${currency}` : amount;
}

export default function VariantDetailPage() {
  return <RequireSession>{(session) => <VariantDetail session={session} />}</RequireSession>;
}

function VariantDetail({ session }: { session: Session }) {
  const { id } = useParams<{ id: string }>();
  const queryClient = useQueryClient();
  const [showAdjust, setShowAdjust] = useState(false);
  const [showPrice, setShowPrice] = useState(false);

  const variant = useQuery({ queryKey: ["variant", id], queryFn: () => api.variants.get(id), refetchInterval: 5000 });

  const canOperate = hasRole(session, "OPERATOR");

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ["variant", id] });
    queryClient.invalidateQueries({ queryKey: ["variants"] });
  }

  if (!variant.data) {
    return <p className="py-8 text-sm text-muted-foreground">Loading…</p>;
  }

  const data = variant.data;
  const channels = data.channels ?? [];

  return (
    <>
      <PageHeader title={`${data.sku_is_generated ? data.barcode : data.sku} · ${data.title}`} />

      <div className="mb-6 flex gap-2">
        <PermissionGate allowed={canOperate} reason="OPERATOR role required">
          <Button type="button" variant="outline" onClick={() => setShowPrice(true)} data-testid="open-price-edit">
            Edit price
          </Button>
        </PermissionGate>
        <PermissionGate allowed={canOperate} reason="OPERATOR role required">
          <Button type="button" variant="outline" onClick={() => setShowAdjust(true)} data-testid="open-stock-adjust">
            Correct stock
          </Button>
        </PermissionGate>
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        <section className="rounded-lg border p-4">
          <h2 className="mb-3 text-sm font-medium tracking-wide text-muted-foreground uppercase">Center</h2>
          <dl className="space-y-1.5 text-sm">
            <Row label="Physical" value={data.on_hand} testId="detail-on-hand" />
            <Row label="Reserved" value={data.reserved} />
            <Row label="Damaged" value={data.damaged} />
            <Row label="Sellable" value={data.sellable} testId="detail-sellable" />
          </dl>
          <div className="mt-3 border-t pt-3 text-sm">
            <span className="font-medium">{formatMoney(data.list_price, data.currency)}</span>
            {data.vat_rate !== null && <span className="ml-2 text-muted-foreground">VAT {Number(data.vat_rate)}%</span>}
          </div>
        </section>

        <section className="rounded-lg border p-4">
          <h2 className="mb-3 text-sm font-medium tracking-wide text-muted-foreground uppercase">Channels</h2>
          {channels.length === 0 ? (
            <p className="text-sm text-muted-foreground">Not mapped to any channel yet.</p>
          ) : (
            <div className="space-y-3">
              {channels.map((c) => (
                <ChannelRow key={c.channelConnectionId} channel={c} />
              ))}
            </div>
          )}
        </section>
      </div>

      <section className="mt-8">
        <h2 className="mb-3 text-lg font-medium">Movements</h2>
        <MovementsTable movements={data.movements} />
      </section>

      {showAdjust && (
        <StockAdjustDialog
          variantId={id}
          currentOnHand={data.on_hand}
          onClose={() => setShowAdjust(false)}
          onDone={() => {
            setShowAdjust(false);
            refresh();
          }}
        />
      )}
      {showPrice && (
        <PriceEditDialog
          variantId={id}
          listPrice={data.list_price}
          currency={data.currency}
          vatRate={data.vat_rate}
          channels={channels}
          onClose={() => setShowPrice(false)}
          onDone={() => {
            setShowPrice(false);
            toast.success("Saved to the center · sending to channels");
            refresh();
          }}
        />
      )}
    </>
  );
}

function Row({ label, value, testId }: { label: string; value: number; testId?: string }) {
  return (
    <div className="flex justify-between">
      <dt className="text-muted-foreground">{label}</dt>
      <dd data-testid={testId} className="font-medium">
        {value}
      </dd>
    </div>
  );
}

function ChannelRow({ channel }: { channel: VariantChannelSummary }) {
  return (
    <div data-testid={`channel-row-${channel.channelConnectionId}`} className="text-sm">
      <div className="flex items-center justify-between">
        <span className="font-medium">{channel.channelType}</span>
        <span>{channel.quantity ?? "—"}</span>
        <ChannelStatusChip status={pushStatusToChip(channel.status)} />
      </div>
      <div className="mt-0.5 flex flex-wrap gap-2 text-xs text-muted-foreground">
        {channel.updatedAt && <span>{new Date(channel.updatedAt).toLocaleString()}</span>}
        {channel.hasChannelPriceOverride && <span>· has its own price</span>}
      </div>
      {channel.errorReason && (
        <p className="mt-0.5 text-xs text-durum-kritik" data-testid={`channel-error-${channel.channelConnectionId}`}>
          ⚠ {channel.errorReason}
        </p>
      )}
    </div>
  );
}

function MovementsTable({ movements }: { movements: StockMovementRow[] }) {
  if (movements.length === 0) {
    return <p className="py-4 text-sm text-muted-foreground">No stock movements yet.</p>;
  }
  return (
    <div className="overflow-x-auto rounded-lg border bg-background">
      <table data-testid="movements-table" className="w-full text-sm">
        <thead className="border-b bg-muted/40 text-left">
          <tr>
            <th className="px-3 py-2 font-medium">When</th>
            <th className="px-3 py-2 font-medium">Change</th>
            <th className="px-3 py-2 font-medium">Reason</th>
            <th className="px-3 py-2 font-medium">Reference</th>
          </tr>
        </thead>
        <tbody>
          {movements.map((m) => {
            const signed = m.reason.endsWith("DECREASE") ? -m.quantity : m.quantity;
            return (
              <tr key={m.id} data-testid="movement-row" className="border-b last:border-0">
                <td className="px-3 py-2">{new Date(m.created_at).toLocaleString()}</td>
                <td className={"px-3 py-2 font-medium " + (signed >= 0 ? "text-durum-iyi" : "text-durum-kritik")}>
                  {signed >= 0 ? "+" : ""}
                  {signed}
                </td>
                <td className="px-3 py-2">
                  {m.adjustment_reason ? `Manual correction — ${m.adjustment_reason}${m.note ? ` · "${m.note}"` : ""}` : m.reason}
                </td>
                <td className="px-3 py-2 font-mono text-xs text-muted-foreground">{m.reference_id?.slice(0, 8) ?? "—"}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
