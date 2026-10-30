import Link from "next/link";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

const PAGES = [
  { href: "/offers", title: "כל ההצעות", text: "כל ההצעות שנאספו, עם אימות ידני." },
  { href: "/shifts", title: "משמרות ידניות", text: "משמרות שהוזנו ידנית ושיוך רשומות תשלום." },
  { href: "/experiments", title: "ניסויים", text: "ניסויים ידניים (מהגרסה הקודמת)." },
  { href: "/dashboard", title: "דשבורד תשלומים", text: "ניתוח קבצי התשלום של Wolt (CSV)." },
  { href: "/deliveries", title: "רשומות תשלום", text: "רשומות התשלום שיובאו." },
  { href: "/hotspots", title: "מפת אזורים", text: "צפיפות הצעות לפי אזור (הגרסה הקודמת)." },
  { href: "/simulator", title: "סימולטור", text: "חישוב משמרת היפותטית." },
];

const REPORTS = [
  { cmd: "npm run reconcile -- 2026-10-29", text: "התאמה מול נתוני היום של Wolt" },
  { cmd: "npm run chains:report -- 2026-10-29", text: "כלכלת שרשראות ליום" },
  { cmd: "npm run eta:report -- 2026-10-29", text: "בדיקת שעות היעד של Wolt" },
  { cmd: "npm run performance:report", text: "ביצועי הצעות לאורך זמן" },
  { cmd: "npm run evidence:report", text: "ממצאים ותכנון ניסוי" },
];

/** Older tools and detailed reports, kept but out of the main decisions. */
export default function AdvancedPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">מתקדם</h1>
        <p className="text-sm text-muted">כלים קודמים ודוחות מפורטים. ההחלטות היומיומיות נמצאות בלפני / אחרי / מה עובד / מסלול.</p>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        {PAGES.map((p) => (
          <Link key={p.href} href={p.href}>
            <Card className="h-full hover:bg-background">
              <CardHeader>
                <CardTitle>{p.title}</CardTitle>
                <CardDescription>{p.text}</CardDescription>
              </CardHeader>
            </Card>
          </Link>
        ))}
      </div>
      <Card>
        <CardHeader>
          <CardTitle>דוחות מפורטים (טרמינל)</CardTitle>
          <CardDescription>כותבים קובץ Markdown לתיקיית reports/.</CardDescription>
        </CardHeader>
        <CardContent>
          <ul className="space-y-1 text-sm">
            {REPORTS.map((r) => (
              <li key={r.cmd} className="flex flex-wrap justify-between gap-2">
                <span>{r.text}</span>
                <code dir="ltr" className="text-xs">{r.cmd}</code>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>
    </div>
  );
}
