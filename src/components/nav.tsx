"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";

const LINKS = [
  { href: "/", label: "דשבורד" },
  { href: "/deliveries", label: "רשומות" },
  { href: "/imports", label: "ייבוא" },
  { href: "/shifts", label: "משמרות" },
  { href: "/experiments", label: "ניסויים" },
  { href: "/settings", label: "הגדרות" },
];

export function Nav() {
  const path = usePathname();
  return (
    <nav aria-label="ניווט ראשי" className="flex max-w-full gap-1 overflow-x-auto">
      {LINKS.map((l) => {
        const active = l.href === "/" ? path === "/" : path.startsWith(l.href);
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
