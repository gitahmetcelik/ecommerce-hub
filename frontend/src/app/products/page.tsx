"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { PageHeader } from "@/components/data-table";
import { Pagination } from "@/components/hub/pagination";
import { ChannelStatusChip, type ChannelSyncStatus } from "@/components/hub/channel-status-chip";
import { Input } from "@/components/ui/input";
import { RequireSession } from "@/components/require-session";
import { api } from "@/lib/api";
import type { VariantChannelSummary, VariantRow } from "@/lib/types";

const PAGE_SIZE = 50;

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

export default function ProductsPage() {
  return <RequireSession>{() => <Products />}</RequireSession>;
}

function Products() {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [channelConnectionId, setChannelConnectionId] = useState("");
  const [stockStatus, setStockStatus] = useState("");
  const [matchStatus, setMatchStatus] = useState("");
  const [expanded, setExpanded] = useState<string | null>(null);

  const channels = useQuery({ queryKey: ["channels"], queryFn: api.channels.list });

  const variants = useQuery({
    queryKey: ["variants", page, search, channelConnectionId, stockStatus, matchStatus],
    queryFn: () =>
      api.variants.list({
        page,
        size: PAGE_SIZE,
        q: search || undefined,
        channelConnectionId: channelConnectionId || undefined,
        stockStatus: stockStatus || undefined,
        matchStatus: matchStatus || undefined,
      }),
    // Plan §5.6: a page in flight must not have its selection/page yanked out from
    // under it by a poll landing mid-read — placeholderData keeps the previous page's
    // rows on screen (instead of a loading flash) while the next one resolves.
    placeholderData: (previous) => previous,
  });

  function submitSearch(e: React.FormEvent) {
    e.preventDefault();
    setPage(0);
    setSearch(searchInput.trim());
  }

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ["variants"] });
  }

  const rows = variants.data?.items ?? [];

  return (
    <>
      <PageHeader
        title="Products"
        description="Center stock and price, next to what each channel currently believes (Plan §U2)."
      />

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <form onSubmit={submitSearch} className="flex gap-2">
          <Input
            placeholder="Search sku / barcode / title"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            data-testid="products-search"
            className="w-64"
          />
        </form>

        <select
          value={channelConnectionId}
          onChange={(e) => {
            setPage(0);
            setChannelConnectionId(e.target.value);
          }}
          data-testid="products-filter-channel"
          className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm"
        >
          <option value="">All channels</option>
          {(channels.data ?? []).map((c) => (
            <option key={c.id} value={c.id}>
              {c.channel_type}
            </option>
          ))}
        </select>

        <select
          value={stockStatus}
          onChange={(e) => {
            setPage(0);
            setStockStatus(e.target.value);
          }}
          data-testid="products-filter-stock"
          className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm"
        >
          <option value="">Any stock</option>
          <option value="IN_STOCK">In stock</option>
          <option value="OUT_OF_STOCK">Out of stock</option>
        </select>

        <select
          value={matchStatus}
          onChange={(e) => {
            setPage(0);
            setMatchStatus(e.target.value);
          }}
          data-testid="products-filter-match"
          className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm"
        >
          <option value="">Any match state</option>
          <option value="MATCHED">Mapped to a channel</option>
          <option value="UNMATCHED">Not mapped anywhere</option>
        </select>
      </div>

      {!variants.data ? (
        <p className="py-8 text-sm text-muted-foreground">Loading…</p>
      ) : rows.length === 0 ? (
        <p data-testid="products-empty" className="py-8 text-sm text-muted-foreground">
          No products match this search.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border bg-background">
          <table data-testid="products-table" className="w-full text-sm">
            <thead className="border-b bg-muted/40 text-left">
              <tr>
                <th className="px-3 py-2 font-medium">SKU</th>
                <th className="px-3 py-2 font-medium">Title</th>
                <th className="px-3 py-2 font-medium">Stock</th>
                <th className="px-3 py-2 font-medium">Price</th>
                <th className="px-3 py-2 font-medium">Channels</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <VariantRows
                  key={row.id}
                  row={row}
                  expanded={expanded === row.id}
                  onToggle={() => setExpanded(expanded === row.id ? null : row.id)}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {variants.data && (
        <Pagination page={variants.data.page} size={variants.data.size} total={variants.data.total} onPageChange={setPage} itemLabel="variant" />
      )}
    </>
  );
}

function VariantRows({
  row,
  expanded,
  onToggle,
}: {
  row: VariantRow;
  expanded: boolean;
  onToggle: () => void;
}) {
  const channelList = row.channels ?? [];
  const canExpand = channelList.length > 0;

  return (
    <>
      <tr data-testid="products-row" className="border-b last:border-0">
        <td className="px-3 py-2 align-top">
          {row.sku_is_generated ? (
            <span title="Generated from the channel's barcode — Plan §1" className="font-mono text-xs text-muted-foreground">
              ⌗ {row.barcode?.slice(0, 4)}…{row.barcode?.slice(-2)}
            </span>
          ) : (
            <Link href={`/products/${row.id}`} className="underline hover:no-underline">
              {row.sku}
            </Link>
          )}
        </td>
        <td className="px-3 py-2 align-top">
          <Link href={`/products/${row.id}`} className="hover:underline">
            {row.title}
          </Link>
        </td>
        <td className="px-3 py-2 align-top" data-testid={`products-stock-${row.id}`}>
          {row.sellable}
        </td>
        <td className="px-3 py-2 align-top">{formatMoney(row.list_price, row.currency)}</td>
        <td className="px-3 py-2 align-top">
          {channelList.length === 0 ? (
            <span className="text-xs text-muted-foreground">No channel</span>
          ) : (
            <button
              type="button"
              onClick={onToggle}
              data-testid={`products-expand-${row.id}`}
              className="flex items-center gap-1.5 text-xs"
              disabled={!canExpand}
            >
              <span className="flex gap-0.5">
                {channelList.slice(0, 3).map((c) => (
                  <ChannelStatusChip key={c.channelConnectionId} status={pushStatusToChip(c.status)} className="gap-0" />
                ))}
              </span>
              <span className="text-muted-foreground">
                {channelList.length} channel{channelList.length === 1 ? "" : "s"} {canExpand && (expanded ? "▴" : "▾")}
              </span>
            </button>
          )}
        </td>
      </tr>
      {expanded && (
        <tr data-testid={`products-expanded-${row.id}`} className="border-b bg-muted/20 last:border-0">
          <td colSpan={5} className="px-6 py-3">
            <ChannelBreakdown channels={channelList} />
          </td>
        </tr>
      )}
    </>
  );
}

function ChannelBreakdown({ channels }: { channels: VariantChannelSummary[] }) {
  return (
    <div className="space-y-1.5">
      {channels.map((c) => (
        <div key={c.channelConnectionId} className="flex items-center gap-3 text-xs">
          <span className="w-28 font-medium">{c.channelType}</span>
          <span className="w-16 text-muted-foreground">stock {c.quantity ?? "—"}</span>
          <ChannelStatusChip
            status={pushStatusToChip(c.status)}
            detail={c.generation !== null ? `gen ${c.generation}` : undefined}
          />
          {c.updatedAt && <span className="text-muted-foreground">{new Date(c.updatedAt).toLocaleString()}</span>}
          {c.hasChannelPriceOverride && <span className="text-muted-foreground">· channel price</span>}
          {c.errorReason && <span className="text-durum-kritik">· {c.errorReason}</span>}
        </div>
      ))}
    </div>
  );
}
