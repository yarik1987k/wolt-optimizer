import { describe, expect, it } from "vitest";
import { cellPolygon } from "@/lib/hotspots/geo";
import { cellOf } from "@/lib/plan/cells";
import { timeWindows, type WorkShift } from "@/lib/plan/decisions";
import { buildRoute, type RoutePoint } from "@/lib/plan/route";
import { localToUtcMs } from "@/lib/time";

const S = 1000;
const p = (t: number, lat: number, speed: number | null = 8): RoutePoint => ({ t: t * S, lat, lon: 34.78, speed });

describe("route", () => {
  it("prefers the precise track and splits it into delivering runs that share boundaries", () => {
    const precise = Array.from({ length: 11 }, (_, i) => p(i * 10, 32 + i * 0.001));
    const r = buildRoute("s", precise, [p(0, 32), p(100, 32.01)], [[30 * S, 70 * S]], [40 * S], [70 * S]);
    expect(r.precision).toBe("PRECISE");
    expect(r.segments.map((s) => s.delivering)).toEqual([false, true, false]);
    expect(r.segments[0].points.at(-1)).toEqual(r.segments[1].points[0]);
    expect(r.km).toBeCloseTo(1.11, 1);
    expect(r.deliveringKm).toBeCloseTo(0.556, 2); // a step belongs to its start point: 30 s → 80 s
    expect(r.markers.map((m) => m.kind)).toEqual(["START", "PICKUP", "DROPOFF", "END"]);
  });

  it("falls back to coarse cells, places no marker it cannot place, and ignores standing-still jitter", () => {
    const r = buildRoute("s", [], [p(0, 32), p(45, 32.005)], [], [10_000 * S], []);
    expect(r.precision).toBe("COARSE");
    expect(r.markers.some((m) => m.kind === "PICKUP")).toBe(false);
    const still = Array.from({ length: 21 }, (_, i) => p(i * 30, 32 + (i % 2 ? 0.00005 : 0), 0.1));
    expect(buildRoute("s", still, [], [], [], []).km).toBe(0);
  });
});

describe("plan", () => {
  it("cell ids match the Collector's grid: a cell's own centre maps back to it (real 29.10 cells)", () => {
    for (const id of ["g500:7124:6558", "g500:7128:6559", "g500:7115:6573"]) {
      const poly = cellPolygon(id)!;
      expect(cellOf((poly[0][1] + poly[2][1]) / 2, (poly[0][0] + poly[2][0]) / 2)).toBe(id);
    }
  });

  it("groups shifts by weekday and start hour; one shift is a hypothesis, five repeat", () => {
    const shift = (date: string, hhmm: string, net: number): WorkShift => ({
      id: `${date}${hhmm}`,
      startMs: localToUtcMs(date, hhmm),
      endMs: localToUtcMs(date, hhmm) + 3 * 3_600_000,
      precise: false,
      summary: { netPerShiftHourAgorot: { low: net - 50, high: net + 50 } } as WorkShift["summary"],
    });
    const one = timeWindows([shift("2026-10-29", "20:09", 5829)]);
    expect(one).toHaveLength(1);
    expect(one[0].label).toBe("ה׳ \u206618-21\u2069");
    expect(one[0].evidence).toBe("HYPOTHESIS");
    const five = timeWindows(["2026-11-05", "2026-11-12", "2026-11-19", "2026-11-26", "2026-12-03"].map((d) => shift(d, "20:00", 6000)));
    expect(five[0].evidence).toBe("REPEATED");
    expect(five[0].shifts).toBe(5);
  });
});
