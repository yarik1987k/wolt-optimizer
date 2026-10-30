/**
 * Evidence & Experiment Plan: labelled findings (OBSERVATION / HYPOTHESIS / VALIDATED_FINDING), the operational
 * constraints of each work shift, the observation periods, and the next proposed experiment.
 * Usage: npm run evidence:report -- [out.md]   (default out: reports/evidence-and-experiment.md)
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { prisma } from "../src/lib/db";
import { evidenceReport } from "../src/lib/evidence/plan";

const ZONE = "Asia/Jerusalem";
const when = (ms: number | null) => (ms === null ? "—" : new Date(ms).toLocaleString("en-GB", { timeZone: ZONE, dateStyle: "short", timeStyle: "medium" }));
const shekel = (a: number) => `₪${(a / 100).toFixed(2)}`;
const pct = (x: number | null) => (x === null ? "—" : `${(x * 100).toFixed(0)}%`);

async function main() {
  const out = resolve(process.argv[2] ?? "reports/evidence-and-experiment.md");
  const r = await evidenceReport();
  const L: string[] = ["# Evidence & Experiment Plan", ""];
  L.push(
    "Goal: measurable ways to raise **net revenue per full shift hour** (accepted-offer amounts − vehicle cost, idle time included). No live recommendations.",
    "",
    `Rules (settings): work shift ≥ ${r.settings.evidenceMinWorkShiftMinutes} min · VALIDATED needs ≥ ${r.settings.evidenceMinShiftsForValidation} independent work shifts per compared condition and a 95% interval excluding zero · coverage ≥ ${r.settings.evidenceMinCoverage * 100}% · smallest effect worth acting on ${shekel(r.settings.evidenceSmallestEffectAgorotPerHour)}/h · vehicle cost ${r.settings.vehicleCostPerKmAgorot === null ? "not set" : `${shekel(r.settings.vehicleCostPerKmAgorot)}/km`}.`,
    "",
  );

  L.push("## Observation periods", "", "Shift boundaries, offers seen, acceptances and completions are different clocks; reports never mix periods silently.", "");
  L.push("| Period | Shift boundaries | Offers seen | Acceptances | Completions (OBSERVED slides) | Chains | Revenue | In shift economics |", "|---|---|---|---|---|---|---|---|");
  for (const p of r.periods) {
    const sp = (x: { n: number; firstMs: number | null; lastMs: number | null }) => (x.n ? `${x.n}: ${when(x.firstMs)} – ${when(x.lastMs)}` : "0");
    L.push(
      `| ${p.kind} | ${p.shiftStartMs === null ? "—" : `${when(p.shiftStartMs)} – ${when(p.shiftEndMs)}`} | ${sp(p.offersSeen)} | ${sp(p.acceptances)} | ${sp(p.completions)} | ${p.chains} | ${shekel(p.revenueAgorot)} | ${p.kind === "WORK_SHIFT" ? "yes" : "no"} |`,
    );
  }

  L.push("", "## Operational constraints (per work shift)", "");
  for (const w of r.workShifts) {
    L.push(
      `### Shift ${when(w.startMs)} – ${when(w.endMs)} (${w.hours.toFixed(2)} h)`,
      "",
      `Net per full shift hour: **${w.netPerShiftHourAgorot ? `${shekel(w.netPerShiftHourAgorot.low)}–${shekel(w.netPerShiftHourAgorot.high)}` : "—"}** (OBSERVATION).`,
      "",
      "| Constraint | Observed | Share | Interpretation |",
      "|---|---|---|---|",
    );
    const order = [...w.constraints].sort((a, b) => (b.share ?? -1) - (a.share ?? -1));
    for (const c of order) L.push(`| ${c.label} | ${c.observed} | ${c.share === null ? "—" : `${pct(c.share)} of ${c.shareOf === "SHIFT_TIME" ? "shift time" : c.shareOf === "REVENUE" ? "revenue" : c.shareOf === "GPS_KM" ? "GPS km" : "—"}`} | ${c.interpretation} |`);
    L.push("", "| Chain | Offers | Revenue | Minutes | ₪ / active min | GPS km |", "|---|---|---|---|---|---|");
    for (const c of w.chainRates)
      L.push(
        `| ${when(Number(c.chainKey))} | ${c.offers} | ${shekel(c.revenueAgorot)} | ${c.minutes.low.toFixed(0)}–${c.minutes.high.toFixed(0)} | ${shekel(c.agorotPerMinute.low)}–${shekel(c.agorotPerMinute.high)} | ${c.km ? `${c.km.low.toFixed(1)}–${c.km.high.toFixed(1)}` : "—"} |`,
      );
    L.push("");
  }

  L.push("## Uncertainty and effective sample size", "");
  const p = r.primaryMetric.perShift;
  L.push(
    `- **Primary metric** (unit = work shift): n = ${p.n}, mean ${p.mean === null ? "—" : shekel(p.mean)}, sd ${p.sd === null ? "unknown" : shekel(p.sd)}, 95% interval ${p.ci95 ? `${shekel(p.ci95.low)}–${shekel(p.ci95.high)}` : "not computable"} — ${r.primaryMetric.note}.`,
    `- **Chain level**: ${r.chainLevel.chains} chains, Kish effective n ${r.chainLevel.kishN.toFixed(1)} (weights = chain minutes), effective n for shift-level claims ${r.chainLevel.effectiveN.toFixed(0)} — ${r.chainLevel.note}. Per-chain gross revenue per active hour (before vehicle cost): mean ${r.chainLevel.perChainGrossPerActiveHour.mean === null ? "—" : shekel(r.chainLevel.perChainGrossPerActiveHour.mean)}, sd ${r.chainLevel.perChainGrossPerActiveHour.sd === null ? "—" : shekel(r.chainLevel.perChainGrossPerActiveHour.sd)} (CV ${pct(r.chainLevel.perChainGrossPerActiveHour.cv)}).`,
    "- Chain-level analysis stays separate from offer-level analysis: offers inside a chain have no duration or distance of their own.",
    "",
  );

  for (const kind of ["OBSERVATION", "HYPOTHESIS", "VALIDATED_FINDING"] as const) {
    const fs = r.findings.filter((f) => f.kind === kind);
    L.push(`## ${kind}`, "");
    if (!fs.length) L.push("None.", "");
    for (const f of fs) L.push(`- **${f.topic}:** ${f.statement}  \n  _Basis:_ ${f.basis}${f.needs ? `  \n  _Needs:_ ${f.needs}` : ""}`);
    L.push("");
  }

  const e = r.experiment;
  L.push(`## Proposed experiment ${e.id}: ${e.title}`, "", `**Hypothesis.** ${e.hypothesis}`, "", "**Why this one first**", ...e.whyThisFirst.map((x) => `- ${x}`), "");
  L.push(
    `**Baseline.** ${e.baseline.value ? `${shekel(e.baseline.value.low)}–${shekel(e.baseline.value.high)}` : "—"} ${e.baseline.unit} — ${e.baseline.basis}. ${e.baseline.uncertainty}.`,
    "",
    "**Arms**",
    ...e.arms.map((a) => `- **${a.name}:** ${a.rule}`),
    "",
    `**Unit.** ${e.unit}`,
    "",
    `**Primary metric.** ${e.primaryMetric}`,
    "",
    "**Secondary metrics**",
    ...e.secondaryMetrics.map((x) => `- ${x}`),
    "",
    "**Data required**",
    ...e.dataRequired.map((d) => `- ${d.item} — ${d.status === "AVAILABLE" ? "available" : "**not yet built**"}`),
    "",
    "**Sample size**",
    ...e.sampleSize.map((x) => `- ${x}`),
    "",
    `**Supporting evidence.** ${e.supporting}`,
    "",
    "**Contradicting / no-effect evidence**",
    ...e.contradicting.map((x) => `- ${x}`),
    "",
    "**Confounders and mitigation**",
    ...e.confounders.map((c) => `- **${c.factor}:** ${c.mitigation}`),
    "",
    "**Risks and limits**",
    ...e.risks.map((x) => `- ${x}`),
    "",
  );
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
