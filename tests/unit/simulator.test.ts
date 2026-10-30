import { describe, expect, it } from "vitest";
import { simulateShift, SimulationInputError, type SimulationInput } from "@/lib/offers/simulator";

const base: SimulationInput = {
  basis: "EX_VAT",
  deliveries: 6,
  avgPaymentAgorot: 3703,
  shiftHours: 4,
  targetAgorot: 40000,
  avgMinutesPerDelivery: null,
  avgKmPerDelivery: null,
  vehicleCostPerKmAgorot: null,
};

describe("8. Shift Revenue Simulator", () => {
  it("current-ish shift: 6 × ₪37.03 = ₪222.18, gap to ₪400", () => {
    const r = simulateShift(base);
    expect(r.gross_agorot).toBe(22218);
    expect(r.gross_per_hour_agorot).toBeCloseTo(5554.5);
    expect(r.gap_to_target_agorot).toBe(17782);
    expect(r.target_reached).toBe(false);
    expect(r.deliveries_needed_at_this_avg).toBe(11); // ceil(40000/3703)
    expect(r.avg_needed_at_this_count_agorot).toBe(6667); // ceil(40000/6)
  });

  it("unknown time per delivery = math-only scenario, never 'feasible'", () => {
    const r = simulateShift({ ...base, deliveries: 11 });
    expect(r.feasibility).toBe("MATH_ONLY");
    expect(r.required_minutes).toBeNull();
    expect(r.notes[0]).toContain("סימולציה מתמטית בלבד");
  });

  it("with time: fits or exceeds the shift", () => {
    expect(simulateShift({ ...base, deliveries: 11, avgMinutesPerDelivery: 20 })).toMatchObject({ feasibility: "FITS_IN_SHIFT", required_minutes: 220, available_minutes: 240 });
    const over = simulateShift({ ...base, deliveries: 13, avgMinutesPerDelivery: 20 });
    expect(over.feasibility).toBe("EXCEEDS_SHIFT");
    expect(over.time_utilization).toBeCloseTo(260 / 240);
  });

  it("vehicle cost and contribution (not net profit)", () => {
    const r = simulateShift({ ...base, deliveries: 10, avgPaymentAgorot: 4000, avgKmPerDelivery: 5, vehicleCostPerKmAgorot: 50 });
    expect(r.gross_agorot).toBe(40000);
    expect(r.target_reached).toBe(true);
    expect(r.estimated_vehicle_cost_agorot).toBe(2500);
    expect(r.contribution_agorot).toBe(37500);
    expect(r.contribution_per_hour_agorot).toBe(9375);
    expect(simulateShift(base).estimated_vehicle_cost_agorot).toBeNull();
  });

  it("keeps the basis and warns for as-displayed offer prices", () => {
    const r = simulateShift({ ...base, basis: "AS_DISPLAYED", avgPaymentAgorot: 3126 });
    expect(r.basis).toBe("AS_DISPLAYED");
    expect(r.notes.join(" ")).toContain("לא ידוע אם כוללים מע״מ");
  });

  it("rejects invalid input", () => {
    expect(() => simulateShift({ ...base, shiftHours: 0 })).toThrow(SimulationInputError);
    expect(() => simulateShift({ ...base, deliveries: -1 })).toThrow(SimulationInputError);
    expect(() => simulateShift({ ...base, avgMinutesPerDelivery: 0 })).toThrow(SimulationInputError);
  });
});
