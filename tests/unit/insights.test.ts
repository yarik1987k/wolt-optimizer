import { beforeEach, describe, expect, it } from "vitest";
import { commitImport } from "@/lib/import/service";
import { generateInsights, getInsights } from "@/lib/analytics/insights";
import { FIXTURE_NAME, fixtureText, resetDb } from "../helpers";

describe("insights engine", () => {
  beforeEach(async () => {
    await resetDb();
    await commitImport(fixtureText(), FIXTURE_NAME);
  });

  it("returns at most 3 evidence-backed hypotheses with limitations, or explains why not", async () => {
    const r = await getInsights({});
    expect(r.analyzed_record_count).toBe(41);
    expect(r.hypotheses.length).toBeLessThanOrEqual(3);
    for (const h of r.hypotheses) {
      expect(h.sample_size).toBeGreaterThanOrEqual(10); // minSampleTestCandidate
      expect(h.baseline.n + h.group.n).toBe(41); // baseline excludes the group itself
      expect(h.robust_gap_pct).toBeGreaterThan(0);
      expect(h.limitations.join(" ")).toContain("שעת הרישום בדוח");
      expect(h.confidence_label).not.toMatch(/מובהק/);
      expect(h.kind).not.toBe("MERCHANT");
      if (h.distinct_dates === 1) expect(h.limitations[0]).toContain("מתאריך אחד"); // no single merchant has 5+ records in this data
    }
    if (r.hypotheses.length < 3) expect(r.explanation).toBeTruthy();
    for (const o of r.observations) {
      expect(o.sample_size).toBeLessThan(10);
      expect(o.status).toBe("OBSERVATION");
      expect(o.confidence_label).toMatch(o.sample_size < 5 ? /^מדגם קטן — לא להמלצה/ : /^אינדיקציה ראשונית/);
    }
  });

  it("guards against a single outlier driving a hypothesis", () => {
    const base = Array.from({ length: 20 }, (_, i) => ({ eventDate: `2026-10-${10 + (i % 5)}`, eventTime: "12:10:00", weekday: 1, merchantRaw: `m${i}`, isBundle: false, amountAgorot: 3000 }));
    const spike = Array.from({ length: 5 }, (_, i) => ({
      eventDate: `2026-10-${10 + i}`,
      eventTime: "19:10:00",
      weekday: 1,
      merchantRaw: `s${i}`,
      isBundle: false,
      amountAgorot: i === 0 ? 50000 : 2900,
    }));
    const r = generateInsights([...base, ...spike], { minSampleObservation: 5, minSampleTestCandidate: 10 });
    expect(r.hypotheses.find((h) => h.key === "HOUR:19:00")).toBeUndefined();
  });

  it("never attributes bundles to merchants", () => {
    const recs = Array.from({ length: 12 }, (_, i) => ({
      eventDate: "2026-10-20",
      eventTime: "19:00:00",
      weekday: 2,
      merchantRaw: i < 6 ? "א, ב" : `m${i}`,
      isBundle: i < 6,
      amountAgorot: i < 6 ? 9000 : 3000,
    }));
    const r = generateInsights(recs, { minSampleObservation: 5, minSampleTestCandidate: 10 });
    expect([...r.hypotheses, ...r.observations].some((h) => h.kind === "MERCHANT" && h.group_label.includes(","))).toBe(false);
  });
});
