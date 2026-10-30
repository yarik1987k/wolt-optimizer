/**
 * Historical Offer Performance report (all imported history): which offer characteristics go with higher
 * earnings after vehicle cost, and which relationships cannot be established yet. Console + Markdown.
 * Usage: npm run performance:report -- [out.md]   (default out: reports/offer-performance.md)
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { prisma } from "../src/lib/db";
import type { Range } from "../src/lib/chains/economics";
import { MIN_COVERAGE, MIN_SEGMENT_CHAINS, performanceReport } from "../src/lib/history/performance";

const ZONE = "Asia/Jerusalem";
const when = (ms: number) => new Date(ms).toLocaleString("en-GB", { timeZone: ZONE, dateStyle: "short", timeStyle: "short" });
const shekel = (a: number) => `₪${(a / 100).toFixed(2)}`;
const rng = (x: Range | null, f: (n: number) => string) => (x === null ? "—" : f(x.low) === f(x.high) ? f(x.low) : `${f(x.low)}–${f(x.high)}`);
const h = (n: number) => n.toFixed(2);

async function main() {
  const out = resolve(process.argv[2] ?? "reports/offer-performance.md");
  const r = await performanceReport();
  const L: string[] = ["# Historical Offer Performance", ""];
  L.push(
    `Scope — work shifts: ${r.scope.shiftBoundaries.map((s) => `${when(s.startMs)} – ${when(s.endMs)}`).join("; ") || "none"} (shift boundaries) · offer acceptances: ${r.scope.acceptances ? `${when(r.scope.acceptances.firstMs)} – ${when(r.scope.acceptances.lastMs)}` : "—"} · chain completions (OBSERVED slides): ${r.scope.completions ? `${when(r.scope.completions.firstMs)} – ${when(r.scope.completions.lastMs)}` : "—"} · ${r.scope.excludedSessions} short/test session(s) excluded · vehicle cost ${r.costPerKmAgorot === null ? "not set" : `${shekel(r.costPerKmAgorot)}/km`} · descriptive only, no live recommendation.`,
    "",
    `Rules: the unit is the delivery **chain**. Offer characteristics are compared on **standalone** chains only (one offer = its own economics); chains with offers added to an active route are measured only as a whole. A segment is compared only with ≥ ${MIN_SEGMENT_CHAINS} complete chains and ≥ ${MIN_COVERAGE * 100}% coverage, and a difference counts only when the ranges do not overlap. Differences are correlations in your own history, not causes. Wolt's displayed target times are not used.`,
    "",
  );

  const established = r.dimensions.flatMap((d) => d.establishedDifferences.map((x) => `${d.label}: ${x}`));
  L.push("## Summary", "");
  L.push(`**Relationships established:** ${established.length ? "" : "none yet."}`, ...established.map((x) => `- ${x}`), "");
  L.push("**Cannot be established yet:**");
  for (const d of r.dimensions) {
    if (d.establishedDifferences.length) continue;
    // A comparison needs two segments with enough data: deficits of the two largest (a missing second counts in full).
    const top = d.segments.map((s) => s.completeChains).sort((a, b) => b - a);
    const need = [0, 1].reduce((t, i) => t + Math.max(0, MIN_SEGMENT_CHAINS - (top[i] ?? 0)), 0);
    L.push(
      `- ${d.label} — ${d.segments.length} segment(s) seen, largest has ${top[0] ?? 0} complete chain(s); at least ${need} more complete chains are needed before two segments can be compared.`,
    );
  }
  L.push(
    "- Value of an individual offer added to an active route — needs the route without it, which is UNKNOWN (see the ETA reports); only chains with and without added offers can be compared.",
    "- Any strategy's earnings — every candidate strategy disagrees with at least one of your real decisions, and those outcomes were never observed.",
    "",
  );

  L.push("## Headline — earnings after vehicle cost", "");
  L.push("| Measure | Value | Basis |", "|---|---|---|");
  L.push(`| Net per **shift** hour (idle included) | ${rng(r.netPerShiftHourAgorot, shekel)} | ${r.shifts.filter((s) => s.vehicleCostAgorot).length} shift(s) with reliable GPS |`);
  L.push(`| Net per **active** hour (in chains) | ${rng(r.netPerActiveHourAgorot, shekel)} | ${r.chains.filter((c) => c.complete).length} complete chain(s) |`);
  L.push("", "The two are kept apart: the optimisation target is per shift hour; per active hour ignores idle time.", "");
  L.push("### Shifts", "", "| Shift | Hours | Chain time | Verified idle | Revenue | Vehicle cost | Net / shift h | Net / active h |", "|---|---|---|---|---|---|---|---|");
  for (const s of r.shifts) {
    L.push(
      `| ${when(s.startMs)} | ${h(s.shiftHours)} | ${rng(s.chainHours, h)} h | ${h(s.verifiedIdleHours)} h | ${shekel(s.revenueAgorot)} | ${rng(s.vehicleCostAgorot, shekel)} | ${rng(s.netPerShiftHourAgorot, shekel)} | ${rng(s.netPerActiveHourAgorot, shekel)} |`,
    );
  }

  L.push("", "## Chains", "", "| Chain | Offers | Standalone | Revenue | Minutes | GPS km | Net / active h | Complete |", "|---|---|---|---|---|---|---|---|");
  for (const c of r.chains) {
    L.push(
      `| ${when(Number(c.chainKey))} | ${c.offers.map((o) => (o.amountAgorot === null ? "?" : shekel(o.amountAgorot)) + (o.context === "ADDED_TO_ACTIVE_ROUTE" ? " (added)" : "")).join(", ")} | ${c.standalone ? "yes" : "no"} | ${shekel(c.economics.revenueAgorot)} | ${rng(c.economics.durationMin, (n) => n.toFixed(0))} | ${rng(c.economics.distanceKm, (n) => n.toFixed(1))} | ${rng(c.economics.netPerHourAgorot, shekel)} | ${c.complete ? "yes" : `no — ${c.economics.missing.join("; ")}`} |`,
    );
  }

  L.push("", "## Segments", "");
  for (const d of r.dimensions) {
    L.push(`### ${d.label}`, "", `Scope: ${d.scope === "STANDALONE" ? "standalone chains" : "all chains"}.`, "");
    L.push("| Segment | Chains | Complete | Revenue | Active h | Net / active h (pooled) | Spread per chain | Status |", "|---|---|---|---|---|---|---|---|");
    for (const s of d.segments) {
      L.push(
        `| ${s.bucket} | ${s.chains} | ${s.completeChains} | ${shekel(s.revenueAgorot)} | ${rng(s.activeHours, h)} | ${rng(s.netPerActiveHourAgorot, shekel)} | ${rng(s.chainNetPerHourSpread, shekel)} | ${s.status === "COMPARABLE" ? "comparable" : `insufficient — ${s.reasons.join("; ")}`} |`,
      );
    }
    if (!d.segments.length) L.push("| — | 0 | 0 | — | — | — | — | no chains in scope |");
    L.push("", ...d.establishedDifferences.map((x) => `- **Established:** ${x}`), ...d.notEstablished.map((x) => `- **Not established:** ${x}`), "");
  }

  L.push("## Candidate offer-selection strategies (prepared, not recommended)", "");
  L.push("| Strategy | Offers | Agree: accept | Agree: decline | Would decline what you accepted | Would accept what you declined | Unknown decision | Verdict |", "|---|---|---|---|---|---|---|---|");
  for (const s of r.strategies) {
    L.push(`| ${s.name} — ${s.description} | ${s.offers} | ${s.agreeAccept} | ${s.agreeDecline} | ${s.wouldDeclineAccepted} | ${s.wouldAcceptDeclined} | ${s.unknownDecision} | ${s.verdict} |`);
  }
  L.push("", "Limitations:", ...[...new Set(r.strategies.flatMap((s) => s.limitations.filter((l) => !/^\d/.test(l))))].map((l) => `- ${l}`), "");

  L.push("## Data coverage", "", ...r.coverageNotes.map((x) => `- ${x}`), "");
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
