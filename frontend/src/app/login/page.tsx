"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { storeSession } from "@/lib/auth";

/**
 * The organization id is part of the form, not derived from the email.
 *
 * <p>Emails are unique per organization, not globally (Plan §10) — the same person can
 * work for two tenants — so an email alone does not identify an account. A global email
 * would have been friendlier here and a worse constraint everywhere else.
 */
export default function LoginPage() {
  const router = useRouter();
  const [organizationId, setOrganizationId] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const response = await api.auth.login(organizationId.trim(), email.trim(), password);
      storeSession(response.accessToken, response.refreshToken, {
        userId: response.userId,
        organizationId: response.organizationId,
        roles: response.roles,
      });
      router.push("/orders");
    } catch {
      // Deliberately one message for every failure. Telling the user which part was
      // wrong tells an attacker which email addresses have accounts.
      setError("Sign-in failed. Check the organization, email and password.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-md pt-12">
      <h1 className="mb-6 text-2xl font-semibold">Sign in</h1>

      <form onSubmit={submit} className="space-y-4 rounded-lg border bg-background p-6">
        <label className="block space-y-1">
          <span className="text-sm font-medium">Organization ID</span>
          <input
            data-testid="login-organization"
            value={organizationId}
            onChange={(e) => setOrganizationId(e.target.value)}
            required
            className="w-full rounded-md border bg-background px-3 py-2 text-sm"
            placeholder="00000000-0000-0000-0000-000000000000"
          />
        </label>

        <label className="block space-y-1">
          <span className="text-sm font-medium">Email</span>
          <input
            data-testid="login-email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            className="w-full rounded-md border bg-background px-3 py-2 text-sm"
          />
        </label>

        <label className="block space-y-1">
          <span className="text-sm font-medium">Password</span>
          <input
            data-testid="login-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            className="w-full rounded-md border bg-background px-3 py-2 text-sm"
          />
        </label>

        {error && (
          <p data-testid="login-error" className="text-sm text-destructive">
            {error}
          </p>
        )}

        <button
          data-testid="login-submit"
          type="submit"
          disabled={busy}
          className="w-full rounded-md bg-primary px-3 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50"
        >
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
