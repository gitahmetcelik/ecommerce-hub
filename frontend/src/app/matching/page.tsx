"use client";

import { useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Card, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { PageHeader } from "@/components/data-table";
import { EmptyState } from "@/components/hub/empty-state";
import { UndoStrip, useUndoable } from "@/components/hub/undo-strip";
import { RequireSession } from "@/components/require-session";
import { api } from "@/lib/api";
import type { Session } from "@/lib/auth";
import { cn } from "@/lib/utils";
import type { CandidateVariant, MappingCandidate } from "@/lib/types";

export default function MatchingPage() {
  return <RequireSession>{(session) => <Matching session={session} />}</RequireSession>;
}

function channelLabel(item: MappingCandidate): string {
  return item.title ?? item.channel_variant_id;
}

/**
 * ui-plani.md §4.2: the highest-volume manual work in the app — a 5,000-variant catalog
 * produces hundreds of these during backfill (Phase 3), so this is built around never
 * touching the mouse. Every candidate list here is a real judgment call: the matching
 * service auto-resolves anything with exactly one clean match before a mapping_candidate
 * row is ever written (see CatalogMatchingService.pendingCandidatesWithDetails), so
 * there's no safe "approve all" subset to bulk-act on — every row here genuinely needs a
 * human, which is why this screen optimizes for going through them fast one at a time
 * rather than for approving many at once.
 */
function Matching({ session }: { session: Session }) {
  const queryClient = useQueryClient();
  const candidates = useQuery({
    // One-at-a-time review workflow (Plan §U1's matching workspace), not a browsed
    // list — a wide single page preserves the pre-Faz-7 behavior instead of adding
    // prev/next controls that would fight the keyboard-driven flow below.
    queryKey: ["mapping-candidates"],
    queryFn: () => api.matching.candidates({ size: 200 }),
    refetchInterval: 5000,
  });

  const { pending, remainingMs, enqueue, undo } = useUndoable();
  const [currentIndex, setCurrentIndex] = useState(0);
  const [selectedIdx, setSelectedIdx] = useState(0);
  const [manualVariantId, setManualVariantId] = useState("");

  const visible = useMemo(
    () => (candidates.data?.items ?? []).filter((c) => c.id !== pending?.id),
    [candidates.data, pending],
  );
  const current = visible[Math.min(currentIndex, Math.max(0, visible.length - 1))];
  const currentCandidates = current?.candidates ?? [];

  // React-recommended "adjust state during render" pattern (not an effect): the
  // selection and manual-id field must reset the instant the current item changes,
  // whether that's from an explicit "next" or from the list shifting under an undo.
  const [trackedId, setTrackedId] = useState<string | undefined>(current?.id);
  if (current?.id !== trackedId) {
    setTrackedId(current?.id);
    setSelectedIdx(0);
    setManualVariantId("");
  }

  function refresh() {
    queryClient.invalidateQueries({ queryKey: ["mapping-candidates"] });
  }

  function resolveTo(item: MappingCandidate, candidate: CandidateVariant) {
    enqueue({
      id: item.id,
      label: `Matched "${channelLabel(item)}" → ${candidate.sku}`,
      commit: async () => {
        try {
          await api.matching.resolve(item.id, candidate.variantId);
        } catch (error) {
          toast.error((error as Error).message);
        } finally {
          refresh();
        }
      },
    });
  }

  function ignoreItem(item: MappingCandidate) {
    enqueue({
      id: item.id,
      label: `Ignored "${channelLabel(item)}"`,
      commit: async () => {
        try {
          await api.matching.ignore(item.id);
        } catch (error) {
          toast.error((error as Error).message);
        } finally {
          refresh();
        }
      },
    });
  }

  function goNext() {
    setCurrentIndex((i) => Math.min(i + 1, visible.length - 1));
  }

  // Keyboard-first (ui-plani.md §4.2): up/down move the selection, Enter commits it, Y
  // ignores the item outright, → just moves on without deciding. Disabled while typing in
  // the manual-id fallback so an operator can still type digits and letters normally.
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA")) return;
      if (!current) return;

      if (event.key === "ArrowDown") {
        event.preventDefault();
        setSelectedIdx((i) => Math.min(i + 1, currentCandidates.length - 1));
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        setSelectedIdx((i) => Math.max(i - 1, 0));
      } else if (event.key === "Enter") {
        event.preventDefault();
        const candidate = currentCandidates[selectedIdx];
        if (candidate) resolveTo(current, candidate);
      } else if (event.key === "y" || event.key === "Y") {
        event.preventDefault();
        ignoreItem(current);
      } else if (event.key === "ArrowRight" || event.key === "n") {
        event.preventDefault();
        goNext();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, currentCandidates, selectedIdx]);

  return (
    <>
      <PageHeader
        title="Matching"
        description="Channel items the hub could not attach to a variant. Nothing here was dropped — that is the point."
      />

      <div className="mb-4 flex items-center justify-between text-sm text-muted-foreground">
        <span data-testid="matching-count">{visible.length} waiting</span>
        <span className="hidden sm:inline">↑↓ select · Enter match · Y ignore · → next</span>
      </div>

      {visible.length === 0 ? (
        candidates.isLoading ? (
          <p className="py-8 text-sm text-muted-foreground">Loading…</p>
        ) : (
          <EmptyState
            variant="success"
            title="Everything the channels sent matched a variant."
            testId="matching-empty"
          />
        )
      ) : (
        current && (
          <div className="grid gap-4 md:grid-cols-2">
            <Card>
              <CardContent className="space-y-1">
                <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">From the channel</p>
                <p className="text-base font-medium" data-testid="matching-current-title">{channelLabel(current)}</p>
                <p className="text-sm text-muted-foreground">Barcode: {current.barcode ?? "—"}</p>
                <p className="text-sm text-muted-foreground">Channel variant: {current.channel_variant_id}</p>
              </CardContent>
            </Card>

            <div className="space-y-2">
              <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
                {currentCandidates.length > 0
                  ? `${currentCandidates.length} variants share barcode ${current.barcode}`
                  : "No SKU or barcode match found"}
              </p>

              {currentCandidates.map((candidate, i) => (
                <Card
                  key={candidate.variantId}
                  data-testid={`candidate-${candidate.variantId}`}
                  className={cn(
                    "cursor-pointer transition-colors",
                    i === selectedIdx && "ring-2 ring-primary",
                  )}
                  onClick={() => setSelectedIdx(i)}
                  onDoubleClick={() => resolveTo(current, candidate)}
                >
                  <CardContent className="flex items-center justify-between py-3">
                    <div>
                      <p className="text-sm font-medium">{candidate.sku}</p>
                      <p className="text-xs text-muted-foreground">
                        {candidate.title ?? "Untitled"} · barcode {candidate.barcode ?? "—"}
                      </p>
                      {candidate.openOrderItems > 0 && (
                        <p className="text-xs text-durum-uyari">
                          Used in {candidate.openOrderItems} open order item{candidate.openOrderItems === 1 ? "" : "s"}
                        </p>
                      )}
                    </div>
                    <Button
                      type="button"
                      size="sm"
                      onClick={(e) => {
                        e.stopPropagation();
                        resolveTo(current, candidate);
                      }}
                      data-testid={`resolve-${candidate.variantId}`}
                    >
                      Match
                    </Button>
                  </CardContent>
                </Card>
              ))}

              <div className="flex gap-2 pt-2">
                <Input
                  placeholder="Or enter a variant ID directly"
                  value={manualVariantId}
                  onChange={(e) => setManualVariantId(e.target.value)}
                  data-testid="matching-manual-id"
                  className="text-xs"
                />
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={!manualVariantId}
                  onClick={() =>
                    resolveTo(current, { variantId: manualVariantId, sku: manualVariantId, barcode: null, title: null, openOrderItems: 0 })
                  }
                  data-testid="matching-manual-resolve"
                >
                  Match
                </Button>
              </div>

              <div className="flex gap-2 pt-1">
                <Button type="button" size="sm" variant="outline" onClick={() => ignoreItem(current)} data-testid="matching-ignore">
                  Ignore (Y)
                </Button>
                <Button type="button" size="sm" variant="ghost" onClick={goNext} data-testid="matching-next">
                  Skip for now (→)
                </Button>
              </div>
            </div>
          </div>
        )
      )}

      <UndoStrip pending={pending} remainingMs={remainingMs} onUndo={undo} />
    </>
  );
}
