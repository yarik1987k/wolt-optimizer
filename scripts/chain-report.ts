/**
 * Chain Economics report for one local day: console + Markdown file.
 * Usage: npm run chains:report -- 2026-10-29 [out.md]   (default out: reports/chain-economics-<day>.md)
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { prisma } from "../src/lib/db";
import { chainReport, type Range } from "../src/lib/chains/economics";

const ZONE = "Asia/Jerusalem";
const hm = (ms: number | null) => (ms === null ? "?" : new Date(ms).toLocaleTimeString("en-GB", { timeZone: ZONE, hour: "2-digit", minute: "2-digit" }));
const hms = (ms: number) => new Date(ms).toLocaleTimeString("en-GB", { timeZone: ZONE });
const shekel = (a: number) => `₪${(a / 100).toFixed(2)}`;
const rng = (x: Range | null, f: (n: number) => string) => (x === null ? "—" : f(x.low) === f(x.high) ? f(x.low) : `${f(x.low)}–${f(x.high)}`);
const hrs = (h: number) => `${h.toFixed(2)} h`;

async function main() {
  const day = process.argv[2];
  if (!day || !/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new Error("Usage: npm run chains:report -- YYYY-MM-DD [out.md]");
  const out = resolve(process.argv[3] ?? `reports/chain-economics-${day}.md`);
  const r = await chainReport(day);
  const chains = await prisma.collectorChain.findMany({ where: { chainKey: { in: r.chains.map((c) => c.chainKey) } } });
  const L: string[] = [];
  L.push(`# Chain Economics — ${day}`, "");
  L.push(
    `Vehicle cost: ${r.costPerKmAgorot === null ? "not set" : `${shekel(r.costPerKmAgorot)}/km`} (engine setting). Figures are ranges; "—" = missing, reason listed. No live recommendation is derived from this.`,
    "",
    "Labels: **OBSERVED** = seen on screen with a time · **WOLT_DISPLAY** = a number Wolt showed, meaning unverified · **ESTIMATE** = computed here, with its method.",
    "",
  );

  L.push("## Shift — the optimisation target", "", "Earnings after vehicle cost per **shift** hour (idle time included). Revenue = accepted-offer amounts (tips not included).", "");
  L.push("| Shift | Hours | Chain time | Verified idle | Online, unverified | Offline | Unknown | Revenue | Slides | GPS km | Vehicle cost | Gross / shift h | Net / shift h | Net / active h |");
  L.push("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
  for (const s of r.shifts) {
    L.push(
      `| ${hm(s.startMs)}–${hm(s.endMs)} | ${hrs(s.shiftHours)} | ${rng(s.chainHours, hrs)} | ${hrs(s.verifiedIdleHours)} | ${hrs(s.onlineUnverifiedHours)} | ${hrs(s.offlineHours)} | ${hrs(s.unknownHours)} | ${shekel(s.revenueAgorot)} | ${s.completionSlides} | ${rng(s.distanceKm, (n) => n.toFixed(1))} | ${rng(s.vehicleCostAgorot, shekel)} | ${shekel(s.grossPerShiftHourAgorot)} | ${rng(s.netPerShiftHourAgorot, shekel)} | ${rng(s.netPerActiveHourAgorot, shekel)} |`,
    );
  }
  for (const s of r.shifts) if (s.missing.length) L.push("", `- Shift ${hm(s.startMs)}: ${s.missing.join("; ")}`);

  L.push("", "## Chains", "");
  L.push("| Chain | Kind | Offers (added) | Orders | Completions (OBSERVED slides) | End evidence | Revenue | Duration (min) | GPS km | Vehicle cost | Gross / h | Net / h |");
  L.push("|---|---|---|---|---|---|---|---|---|---|---|---|");
  for (const c of r.chains) {
    L.push(
      `| ${hm(Number(c.chainKey))} | ${c.kind === "SINGLE_OFFER" ? "single offer" : "with added offers"} | ${c.offers} (${c.addedOffers}) | ${rng(c.orders, (n) => `${n}`)} | ${c.completionSlides} | ${c.endEvidence} | ${shekel(c.revenueAgorot)} | ${rng(c.durationMin, (n) => n.toFixed(0))} | ${rng(c.distanceKm, (n) => n.toFixed(1))} | ${rng(c.vehicleCostAgorot, shekel)} | ${rng(c.grossPerHourAgorot, shekel)} | ${rng(c.netPerHourAgorot, shekel)} |`,
    );
  }
  L.push("", "### Completion times and route log", "", "Route entries: position.kind→target (WOLT_DISPLAY target clock = snapshot time + shown minutes; no target = stop already reached). Stale frames excluded.", "");
  for (const row of chains.sort((a, b) => Number(a.chainKey) - Number(b.chainKey))) {
    const slides = row.completionSlidesJson ? (JSON.parse(row.completionSlidesJson) as number[]) : [];
    const changes = row.routeChangesJson
      ? (JSON.parse(row.routeChangesJson) as {
          at_ms: number;
          active_pickups: number;
          active_dropoffs: number;
          added_stops: number;
          removed_stops: number;
          stops: { kind: string; position: number; eta_minutes: number | null; target_at_ms: number | null }[];
        }[])
      : [];
    L.push(`- **${hm(Number(row.chainKey))}** — OBSERVED completions: ${slides.map(hms).join(", ") || "none"}`);
    for (const ch of changes) {
      L.push(
        `  - ${hms(ch.at_ms)} (+${ch.added_stops} / −${ch.removed_stops}): ${ch.stops.map((st) => `${st.position + 1}.${st.kind === "PICKUP" ? "P" : "D"}${st.target_at_ms === null ? "" : `→${hm(st.target_at_ms)}`}`).join("  ")}`,
      );
    }
  }
  L.push("", "### Missing data", "");
  for (const c of r.chains) if (c.missing.length) L.push(`- **${hm(Number(c.chainKey))}**: ${c.missing.join("; ")}`);

  L.push("", "## Offers", "");
  L.push("| Seen | Accepted | Amount | Card km / min (WOLT_DISPLAY) | Context | Active before | New orders (confidence) | Route before / after | New stops (pickup / drop-off, ambiguous) | Training sample |");
  L.push("|---|---|---|---|---|---|---|---|---|---|");
  const trips = await prisma.collectorTrip.findMany({ where: { tripKey: { in: r.offers.map((o) => o.tripKey) } } });
  for (const o of r.offers) {
    const t = trips.find((x) => x.tripKey === o.tripKey);
    const nw = o.newOrdersLow === null ? "?" : o.newOrdersLow === o.newOrdersHigh ? `${o.newOrdersLow}` : `${o.newOrdersLow}–${o.newOrdersHigh}`;
    L.push(
      `| ${hm(o.offerSeenAtMs)} | ${hm(o.acceptedEarliestMs)}–${hm(o.acceptedLatestMs)} | ${o.amountAgorot === null ? "?" : shekel(o.amountAgorot)} | ${t?.cardDistanceKm ?? "—"} / ${t?.cardMinutes ?? "—"} | ${o.acceptContext ?? "?"} | ${o.activeOrdersBefore ?? "?"} | ${nw} (${o.newOrdersConfidence ?? "?"}) | ${o.hasRouteBefore ? `yes (${Math.round((t?.routeBeforeAgeMs ?? 0) / 60_000)} min old)` : "—"} / ${o.hasRouteAfter ? "yes" : "—"} | ${o.newPickupStops ?? "?"} / ${o.newDropoffStopsByShape ?? "?"}, ${o.ambiguousDropoffStops ?? "?"} | ${o.trainingEligible ? "yes" : "no"} |`,
    );
  }

  L.push("", "## Added offers — chain without the offer", "");
  L.push(
    "Only the revenue without the offer is known (exact). Its duration and distance are **UNKNOWN**: Wolt's route numbers are fixed target times that did not move when work was added (see the ETA validation report), and no justified route model exists yet. No bound, estimate or marginal rate is given.",
    "",
  );
  L.push("| Offer | Amount | Chain without: revenue | Duration / distance without | Route before (age) | Drop-off screen before (age) | Route after (delay) | Completions in between | Carried target moved | Route changed for other reasons |");
  L.push("|---|---|---|---|---|---|---|---|---|---|");
  const m = (x: number | null) => (x === null ? "—" : `${x.toFixed(1)} min`);
  for (const cf of r.counterfactuals) {
    L.push(
      `| ${hm(Number(cf.tripKey))} | ${shekel(cf.amountAgorot)} | ${shekel(cf.chainWithout.revenueAgorot)} | UNKNOWN | ${m(cf.routeBeforeAgeMin)} | ${m(cf.dropScreenBeforeAgeMin)} | ${m(cf.routeAfterDelayMin)} | ${cf.completionsBetweenBeforeAndAccept ?? "—"} | ${cf.carriedTargetShiftMin === null ? "—" : `${cf.carriedTargetShiftMin >= 0 ? "+" : ""}${cf.carriedTargetShiftMin.toFixed(0)} min (WOLT_DISPLAY)`} | ${cf.routeChangedForOtherReasons === null ? "—" : cf.routeChangedForOtherReasons ? "yes" : "no"} |`,
    );
  }
  for (const cf of r.counterfactuals) if (cf.notes.length) L.push(`- ${hm(Number(cf.tripKey))}: ${cf.notes.join("; ")}`);

  L.push("", "## Incremental-offer question", "", ...r.incrementalReadiness.map((x) => `- ${x}`), "");
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
