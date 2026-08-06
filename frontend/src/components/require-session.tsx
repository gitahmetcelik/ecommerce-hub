"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { readSession, type Session } from "@/lib/auth";

/**
 * Client-side gate. Not a security control — the backend rejects tokenless requests
 * regardless (SecurityConfig), and anything enforced only in a browser is enforced by
 * nobody. This exists so an unauthenticated visitor sees the login page instead of a
 * screen full of failed requests.
 */
export function RequireSession({ children }: { children: (session: Session) => React.ReactNode }) {
  const router = useRouter();
  const [session, setSession] = useState<Session | null | undefined>(undefined);

  useEffect(() => {
    const current = readSession();
    if (!current) {
      router.replace("/login");
    }
    setSession(current);
  }, [router]);

  if (session === undefined) {
    return <p className="py-8 text-sm text-muted-foreground">Loading…</p>;
  }
  if (session === null) {
    return null;
  }
  return <>{children(session)}</>;
}
