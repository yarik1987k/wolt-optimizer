"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

/** Built around the courier's decisions (same as the phone app); older tools live under "מתקדם". */
const LINKS = [
  { href: "/plan", label: "לפני" },
  { href: "/review", label: "אחרי" },
  { href: "/learn", label: "מה עובד" },
  { href: "/route", label: "מסלול" },
  { href: "/imports", label: "ייבוא" },
  { href: "/settings", label: "הגדרות" },
  { href: "/advanced", label: "מתקדם" },
];
/** Old pages: highlight "מתקדם" while on them. */
const ADVANCED = ["/dashboard", "/deliveries", "/offers", "/simulator", "/hotspots", "/shifts", "/experiments"];

export function Nav() {
  const path = usePathname();
  return (
    <nav aria-label="ניווט ראשי" className="flex max-w-full gap-1 overflow-x-auto">
      {LINKS.map((l) => {
        const active = path.startsWith(l.href) || (l.href === "/advanced" && ADVANCED.some((a) => path.startsWith(a)));
        return (
          <Link
            key={l.href}
            href={l.href}
            aria-current={active ? "page" : undefined}
            className={cn("rounded-md px-3 py-1.5 text-sm whitespace-nowrap", active ? "bg-primary text-primary-foreground" : "hover:bg-background")}
          >
            {l.label}
          </Link>
        );
      })}
    </nav>
  );
}
