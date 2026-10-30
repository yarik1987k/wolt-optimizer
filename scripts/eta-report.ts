/**
 * ETA validation report for one local day: what Wolt's route numbers mean, and how far they were from
 * OBSERVED completions. Console + Markdown.
 * Usage: npm run eta:report -- 2026-10-29 [out.md]   (default out: reports/eta-validation-<day>.md)
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { prisma } from "../src/lib/db";
import { addDays, localToUtcMs } from "../src/lib/time";

const ZONE = "Asia/Jerusalem";
const hms = (d: Date | null) => (d === null ? "—" : d.toLocaleTimeString("en-GB", { timeZone: ZONE }));
const hm = (d: Date | null) => (d === null ? "—" : d.toLocaleTimeString("en-GB", { timeZone: ZONE, hour: "2-digit", minute: "2-digit" }));
const sgn = (x: number | null) => (x === null ? "—" : `${x >= 0 ? "+" : ""}${x.toFixed(1)}`);

function stats(xs: number[]) {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const q = (p: number) => s[Math.min(s.length - 1, Math.max(0, Math.round(p * (s.length - 1))))];
  return { n: s.length, min: s[0], median: q(0.5), max: s[s.length - 1], mean: s.reduce((a, b) => a + b, 0) / s.length };
}

async function main() {
  const day = process.argv[2];
  if (!day || !/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new Error("Usage: npm run eta:report -- YYYY-MM-DD [out.md]");
  const out = resolve(process.argv[3] ?? `reports/eta-validation-${day}.md`);
  const from = new Date(localToUtcMs(day, "00:00"));
  const to = new Date(localToUtcMs(addDays(day, 1), "00:00"));
  const checks = await prisma.collectorEtaCheck.findMany({ where: { completedAtUtc: { gte: from, lt: to } }, orderBy: { completedAtUtc: "asc" } });

  const L: string[] = [`# ETA validation — ${day}`, ""];
  L.push(
    "Labels: **OBSERVED** = drop-off slide (completion) with its time · **WOLT_DISPLAY** = a time or minutes Wolt showed · **MODEL** = nothing here; no model estimates are used.",
    "",
    "Error = completion − Wolt's target, in minutes (positive = later than the target).",
    "",
  );
  L.push("## Completions vs Wolt's targets", "");
  L.push("| Completed (OBSERVED) | Stop identity | Order times on drop-off screen (WOLT_DISPLAY) | Route target first seen (WOLT_DISPLAY) | Target at first sight | Target last seen | Error vs drop-off screen | Error vs first route target | Error vs last route target | Lead | Notes |");
  L.push("|---|---|---|---|---|---|---|---|---|---|---|");
  for (const c of checks) {
    const times = (JSON.parse(c.orderTimesJson) as number[]).map((t) => hm(new Date(t))).join(" / ") || "—";
    L.push(
      `| ${hms(c.completedAtUtc)} | ${c.identity} | ${times} | ${hms(c.routeFirstSeenAtUtc)} | ${hm(c.routeTargetFirstAtUtc)} | ${hm(c.routeTargetLastAtUtc)} | ${sgn(c.errorVsDropScreenMin)} | ${sgn(c.errorVsFirstRouteMin)} | ${sgn(c.errorVsLastRouteMin)} | ${c.leadMin === null ? "—" : `${c.leadMin.toFixed(0)} min`} | ${(JSON.parse(c.notesJson) as string[]).join("; ")} |`,
    );
  }
  const reliable = checks.filter((c) => c.identity !== "UNKNOWN");
  const route = stats(reliable.map((c) => c.errorVsFirstRouteMin).filter((x): x is number => x !== null));
  const screen = stats(reliable.map((c) => c.errorVsDropScreenMin).filter((x): x is number => x !== null));
  L.push("", "## Error ranges", "");
  const row = (name: string, s: ReturnType<typeof stats>) =>
    s ? `| ${name} | ${s.n} | ${sgn(s.min)} | ${sgn(s.median)} | ${sgn(s.mean)} | ${sgn(s.max)} |` : `| ${name} | 0 | — | — | — | — |`;
  L.push("| Against | n | Min | Median | Mean | Max |", "|---|---|---|---|---|---|");
  L.push(row("first route target (identity matched on the route)", route), row("drop-off screen target", screen));
  L.push("", `Stops with a reliable identity: ${reliable.length} of ${checks.length}. Small sample — these ranges describe this day only.`, "");
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, L.join("\n"));
  console.log(L.join("\n"));
  console.log(`\n→ ${out}`);
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
