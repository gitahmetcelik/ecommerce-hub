"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { clearSession, hasRole, readRefreshToken, readSession, type Session } from "@/lib/auth";

const LINKS = [
  { href: "/products", label: "Products" },
  { href: "/orders", label: "Orders" },
  { href: "/stock", label: "Stock" },
  { href: "/returns", label: "Returns" },
  { href: "/matching", label: "Matching" },
  { href: "/channels", label: "Channels" },
  { href: "/queue", label: "Operator queue" },
];

const ADMIN_LINKS = [{ href: "/diagnostics", label: "Diagnostics" }];

export function Nav() {
  const pathname = usePathname();
  const router = useRouter();
  const [session, setSession] = useState<Session | null>(null);

  // Read after mount: localStorage does not exist during server rendering, and reading
  // it in render would make the server and client markup disagree.
  useEffect(() => setSession(readSession()), [pathname]);

  if (pathname === "/login") {
    return null;
  }

  async function signOut() {
    const refreshToken = readRefreshToken();
    if (refreshToken) {
      await api.auth.logout(refreshToken).catch(() => undefined);
    }
    clearSession();
    router.push("/login");
  }

  return (
    <header className="border-b bg-background">
      <div className="mx-auto flex w-full max-w-6xl items-center gap-6 px-4 py-3">
        <Link href="/orders" className="font-semibold">
          E-commerce Hub
        </Link>

        <nav className="flex flex-1 flex-wrap gap-4 text-sm">
          {LINKS.map((link) => (
            <Link
              key={link.href}
              href={link.href}
              data-testid={`nav-${link.href.slice(1)}`}
              className={
                pathname.startsWith(link.href)
                  ? "font-medium text-foreground"
                  : "text-muted-foreground hover:text-foreground"
              }
            >
              {link.label}
            </Link>
          ))}
          {hasRole(session, "ADMIN") &&
            ADMIN_LINKS.map((link) => (
              <Link
                key={link.href}
                href={link.href}
                data-testid={`nav-${link.href.slice(1)}`}
                className={
                  pathname.startsWith(link.href)
                    ? "font-medium text-foreground"
                    : "text-muted-foreground hover:text-foreground"
                }
              >
                {link.label}
              </Link>
            ))}
        </nav>

        {session && (
          <div className="flex items-center gap-3 text-sm">
            <span data-testid="current-role" className="text-muted-foreground">
              {session.roles.join(", ") || "no role"}
            </span>
            <button type="button" onClick={signOut} className="underline hover:no-underline">
              Sign out
            </button>
          </div>
        )}
      </div>
    </header>
  );
}
