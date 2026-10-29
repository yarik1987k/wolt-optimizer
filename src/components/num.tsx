import { formatShekel } from "@/lib/money";

/** Numbers/times rendered LTR inside RTL text. */
export function Num({ children }: { children: React.ReactNode }) {
  return <span className="num">{children}</span>;
}

export function Money({ agorot }: { agorot: number | null | undefined }) {
  if (agorot === null || agorot === undefined) return <span className="text-muted">אין נתונים</span>;
  return <span className="num">{formatShekel(agorot)}</span>;
}
