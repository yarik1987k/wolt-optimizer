import { describe, expect, it } from "vitest";
import { clusteredEffectiveN, compareArms, kishEffectiveN, summarize, unitsPerArm } from "@/lib/evidence/stats";

describe("evidence statistics", () => {
  it("one unit has a mean but no spread and no interval", () => {
    const s = summarize([5829]);
    expect(s.mean).toBe(5829);
    expect(s.sd).toBeNull();
    expect(s.ci95).toBeNull();
  });

  it("uses a t interval for small samples", () => {
    const s = summarize([5000, 6000, 7000]);
    expect(s.mean).toBe(6000);
    expect(s.sd).toBeCloseTo(1000);
    // t(2) = 4.303 → half-width 4.303 × 1000 / √3
    expect(s.ci95!.high - s.mean!).toBeCloseTo((4.303 * 1000) / Math.sqrt(3), 1);
  });

  it("effective n shrinks with unequal weights and is capped by clusters", () => {
    expect(kishEffectiveN([1, 1, 1, 1])).toBeCloseTo(4);
    expect(kishEffectiveN([25, 43, 26, 82])).toBeLessThan(4);
    // All chains in one shift: for a shift-level claim the effective n is 1.
    expect(clusteredEffectiveN(3.5, 1, 4, null)).toBe(1);
    expect(clusteredEffectiveN(30, 5, 6, null)).toBe(5);
    expect(clusteredEffectiveN(30, 5, 6, 0.2)).toBeCloseTo(30 / 2);
  });

  it("validates only with enough units per arm and an interval that excludes zero", () => {
    // One shift per arm: never a verdict, however large the gap.
    expect(compareArms([9000], [5000], 5, 500).verdict).toBe("INCONCLUSIVE");
    // Five per arm, clearly separated → SUPPORTS.
    const a = [6200, 6400, 6100, 6300, 6500];
    const b = [5200, 5400, 5300, 5100, 5500];
    expect(compareArms(a, b, 5, 500).verdict).toBe("SUPPORTS");
    expect(compareArms(b, a, 5, 500).verdict).toBe("CONTRADICTS");
    // Five per arm, overlapping and wide → INCONCLUSIVE with the reason.
    const r = compareArms([5000, 7000, 6000, 5500, 6500], [5200, 6800, 5900, 5600, 6600], 5, 100);
    expect(r.verdict).toBe("INCONCLUSIVE");
    expect(r.reasons.join(" ")).toContain("practically relevant");
    // Tight and centred on zero, inside ±₪5/h → NO_PRACTICAL_EFFECT.
    expect(compareArms([6000, 6010, 5990, 6005, 5995], [6000, 6008, 5992, 6003, 5997], 5, 500).verdict).toBe("NO_PRACTICAL_EFFECT");
  });

  it("sizes an experiment from the observed spread", () => {
    // sd ₪10/h, smallest effect ₪5/h → 2 × 2.8² × 4 ≈ 63 shifts per arm.
    expect(unitsPerArm(1000, 500)).toBe(63);
    expect(unitsPerArm(1000, 0)).toBe(Infinity);
  });
});
