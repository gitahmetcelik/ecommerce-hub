"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
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

/** Plan §8.2 point 2 — the way out of CREDENTIALS_INVALID. Same shape as the connect wizard's step 2, only Shopify exists today. */
export function RotateCredentialsDialog({
  channelConnectionId,
  onClose,
  onDone,
}: {
  channelConnectionId: string;
  onClose: () => void;
  onDone: () => void;
}) {
  const [storeDomain, setStoreDomain] = useState("");
  const [accessToken, setAccessToken] = useState("");
  const [webhookSecret, setWebhookSecret] = useState("");
  const queryClient = useQueryClient();

  const rotate = useMutation({
    mutationFn: () =>
      api.channels.rotateCredentials(channelConnectionId, {
        storeDomain,
        accessToken,
        webhookSecret: webhookSecret || null,
      }),
    onSuccess: () => {
      toast.success("Credentials rotated.");
      queryClient.invalidateQueries({ queryKey: ["channel", channelConnectionId] });
      onDone();
    },
    onError: (error: Error) => toast.error(error.message),
  });

  return (
    <Dialog open onOpenChange={(next) => !next && !rotate.isPending && onClose()}>
      <DialogContent data-testid="rotate-credentials-dialog">
        <DialogHeader>
          <DialogTitle>Rotate credentials</DialogTitle>
          <DialogDescription>Checked against the channel before anything is saved — a rejected credential leaves the old one in place.</DialogDescription>
        </DialogHeader>

        <div className="space-y-3">
          <div>
            <Label htmlFor="rotate-store-domain">Store domain</Label>
            <Input
              id="rotate-store-domain"
              placeholder="your-store.myshopify.com"
              value={storeDomain}
              onChange={(e) => setStoreDomain(e.target.value)}
              data-testid="rotate-store-domain"
              className="mt-1"
            />
          </div>
          <div>
            <Label htmlFor="rotate-access-token">Admin API access token</Label>
            <Input
              id="rotate-access-token"
              type="password"
              placeholder="shpat_…"
              value={accessToken}
              onChange={(e) => setAccessToken(e.target.value)}
              data-testid="rotate-access-token"
              className="mt-1"
            />
          </div>
          <div>
            <Label htmlFor="rotate-webhook-secret">Webhook secret (optional)</Label>
            <Input
              id="rotate-webhook-secret"
              type="password"
              value={webhookSecret}
              onChange={(e) => setWebhookSecret(e.target.value)}
              data-testid="rotate-webhook-secret"
              className="mt-1"
            />
          </div>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" disabled={rotate.isPending} onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="button"
            disabled={rotate.isPending || !storeDomain || !accessToken}
            onClick={() => rotate.mutate()}
            data-testid="rotate-submit"
          >
            {rotate.isPending ? "Checking…" : "Rotate"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
