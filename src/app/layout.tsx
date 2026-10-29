import type { Metadata } from "next";
import { Nav } from "@/components/nav";
import "./globals.css";

export const metadata: Metadata = {
  title: "Wolt Optimizer",
  description: "ניתוח אישי של תשלומי וולט — מקומי בלבד",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="he" dir="rtl">
      <body className="min-h-screen font-sans antialiased">
        <header className="sticky top-0 z-10 border-b border-border bg-surface">
          <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-2 px-4 py-2">
            <span className="font-semibold">Wolt Optimizer <span className="text-xs font-normal text-muted">v0.1 · מקומי</span></span>
            <Nav />
          </div>
        </header>
        <main className="mx-auto max-w-6xl px-4 py-6">{children}</main>
      </body>
    </html>
  );
}
