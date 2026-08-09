"use client";

import { useMutation } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { PageHeader } from "@/components/data-table";
import { EmptyState } from "@/components/hub/empty-state";
import { RequireSession } from "@/components/require-session";
import { api } from "@/lib/api";
import { hasRole, type Session } from "@/lib/auth";

/**
 * Plan §U6 — the connect wizard. The plan's own mockup shows five channel cards
 * (Shopier/Hepsiburada/PTT AVM/Trendyol/Manuel), written before Faz 3's own research
 * (docs/kanal-arastirmasi.md) found none of those buildable without a live seller
 * account and Faz 4 built Shopify instead (docs/kanal-arastirmasi.md's 2026-08-07
 * revision). Offering cards for connectors that don't exist would fail at step 2 with
 * "No PlatformConnector registered" — so this only lists what's actually wired.
 */
const CHANNEL_TYPES = [
  { value: "SHOPIFY", label: "Shopify", identifiedBy: "SKU" },
] as const;

export default function ConnectChannelPage() {
  return <RequireSession>{(session) => <ConnectChannel session={session} />}</RequireSession>;
}

function ConnectChannel({ session }: { session: Session }) {
  const router = useRouter();
  const [channelType, setChannelType] = useState<string | null>(null);
  const [storeDomain, setStoreDomain] = useState("");
  const [accessToken, setAccessToken] = useState("");
  const [webhookSecret, setWebhookSecret] = useState("");

  const connect = useMutation({
    mutationFn: () =>
      api.channels.create(channelType!, {
        storeDomain,
        accessToken,
        webhookSecret: webhookSecret || null,
      }),
    onSuccess: (result) => {
      toast.success("Channel connected — importing catalog and orders now.");
      router.push(`/channels/${result.id}`);
    },
    onError: (error: Error) => toast.error(error.message),
  });

  if (!hasRole(session, "ADMIN")) {
    return (
      <>
        <PageHeader title="Connect a channel" />
        <EmptyState
          variant="no-data"
          title="ADMIN only."
          description="Connecting a channel puts real credentials into the hub — ask an admin to do this one."
          testId="connect-forbidden"
        />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Connect a channel"
        description={channelType ? "2 · Credentials" : "1 · Choose a channel"}
      />

      {!channelType && (
        <div className="grid max-w-md grid-cols-2 gap-3" data-testid="connect-step-select">
          {CHANNEL_TYPES.map((c) => (
            <Card
              key={c.value}
              className="cursor-pointer ring-1 ring-foreground/10 hover:ring-foreground/30"
              onClick={() => setChannelType(c.value)}
              data-testid={`connect-channel-${c.value}`}
            >
              <CardHeader>
                <CardTitle>{c.label}</CardTitle>
                <CardDescription>identified by {c.identifiedBy}</CardDescription>
              </CardHeader>
            </Card>
          ))}
        </div>
      )}

      {channelType === "SHOPIFY" && (
        <Card className="max-w-md" data-testid="connect-step-credentials">
          <CardHeader>
            <CardTitle>Shopify</CardTitle>
            <CardDescription>
              From your store admin's "Develop apps" page: the store domain and a static Admin API access token.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <div>
              <Label htmlFor="store-domain">Store domain</Label>
              <Input
                id="store-domain"
                placeholder="your-store.myshopify.com"
                value={storeDomain}
                onChange={(e) => setStoreDomain(e.target.value)}
                data-testid="connect-store-domain"
                className="mt-1"
              />
            </div>
            <div>
              <Label htmlFor="access-token">Admin API access token</Label>
              <Input
                id="access-token"
                type="password"
                placeholder="shpat_…"
                value={accessToken}
                onChange={(e) => setAccessToken(e.target.value)}
                data-testid="connect-access-token"
                className="mt-1"
              />
            </div>
            <div>
              <Label htmlFor="webhook-secret">Webhook secret (optional)</Label>
              <Input
                id="webhook-secret"
                type="password"
                value={webhookSecret}
                onChange={(e) => setWebhookSecret(e.target.value)}
                data-testid="connect-webhook-secret"
                className="mt-1"
              />
            </div>

            <div className="flex justify-end gap-2 pt-2">
              <Button type="button" variant="outline" onClick={() => setChannelType(null)} disabled={connect.isPending}>
                Back
              </Button>
              <Button
                type="button"
                disabled={connect.isPending || !storeDomain || !accessToken}
                onClick={() => connect.mutate()}
                data-testid="connect-submit"
              >
                {connect.isPending ? "Checking credentials…" : "Connect"}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}
    </>
  );
}
