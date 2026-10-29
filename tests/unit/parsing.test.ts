import { describe, expect, it } from "vitest";
import { parseAmountToAgorot, agorotToShekelString } from "@/lib/money";
import { classifyRow, isBundle, splitMerchants } from "@/lib/import/classify";
import { parseWoltCsv } from "@/lib/import/csv";
import { reportNumberFromFileName } from "@/lib/import/deduplicate";
import { computeShiftWindow, parseReportDate, weekdayOf } from "@/lib/time";

describe("money", () => {
  it("parses decimal strings to integer agorot without float drift", () => {
    expect(parseAmountToAgorot("300.0")).toBe(30000);
    expect(parseAmountToAgorot("71.42")).toBe(7142);
    expect(parseAmountToAgorot("18.75")).toBe(1875);
    expect(parseAmountToAgorot("0.29")).toBe(29);
    expect(parseAmountToAgorot("71,42")).toBe(7142);
    expect(parseAmountToAgorot("1,203.90")).toBe(120390);
    expect(parseAmountToAgorot("₪ 7")).toBe(700);
    expect(parseAmountToAgorot("-7.00")).toBe(-700);
    expect(() => parseAmountToAgorot("abc")).toThrow();
    expect(() => parseAmountToAgorot("1.234")).toThrow();
  });
  it("formats averages with two decimals", () => {
    expect(agorotToShekelString(151836 / 41)).toBe("37.03");
  });
});

describe("dates", () => {
  it("parses DD.MM.YYYY and derives the weekday", () => {
    expect(parseReportDate("11.10.2026")).toBe("2026-10-11");
    expect(weekdayOf("2026-10-11")).toBe(0); // Sunday
    expect(() => parseReportDate("31.02.2026")).toThrow();
  });
});

describe("classification & bundles", () => {
  it("classifies by explicit text rules, never by amount", () => {
    expect(classifyRow({ merchantRaw: "—", notes: "Funnel boost - O2 -D5 - G300₪ 11/10", amountAgorot: 30000 })).toBe("BONUS");
    expect(classifyRow({ merchantRaw: "—", notes: "WK1 D15G100 12-18/10", amountAgorot: 10000 })).toBe("BONUS");
    expect(classifyRow({ merchantRaw: "—", notes: "Restaurant Late: Poke Van", amountAgorot: 700 })).toBe("ADJUSTMENT");
    // A large amount with no bonus text is still a delivery.
    expect(classifyRow({ merchantRaw: "מסעדה", notes: "—", amountAgorot: 99900 })).toBe("DELIVERY");
    expect(classifyRow({ merchantRaw: "—", notes: "something new", amountAgorot: 500 })).toBe("UNKNOWN");
  });
  it("detects bundles on ', ' but not on an address comma", () => {
    expect(isBundle("בינו סנטר, ג׳אג׳ה עוף בגריל")).toBe(true);
    expect(splitMerchants("מוזס שופ כשר | באר יעקב, מוזס שופ כשר | באר יעקב, גולדה כשר | באר יעקב")).toHaveLength(3);
    expect(isBundle("Rishon LeZion ,20 הנגיד")).toBe(false);
    expect(isBundle("—")).toBe(false);
  });
  it("normalises duplicate-copy file names to the same report number", () => {
    expect(reportNumberFromFileName("116000001.pdf")).toBe("116000001");
    expect(reportNumberFromFileName("116000001 (1).pdf")).toBe("116000001");
    expect(reportNumberFromFileName("116000002(1).pdf")).toBe("116000002");
    expect(reportNumberFromFileName("notes.pdf")).toBeNull();
  });
});

describe("CSV parser edge cases", () => {
  const header = "תאריך,שעה,בית העסק,הערות,סכום ללא מעמ,קובץ מקור\n";
  it("handles BOM, quoted commas and CRLF", () => {
    const r = parseWoltCsv(`﻿${header}"11.10.2026","19:11:08","א, ב",—,71.42,116000001.pdf\r\n`, "x.csv");
    expect(r.errors).toEqual([]);
    expect(r.rows[0].merchantRaw).toBe("א, ב");
    expect(r.rows[0].isBundle).toBe(true);
    expect(r.rows[0].amountAgorot).toBe(7142);
  });
  it("keeps genuinely identical rows within one report (occurrence index)", () => {
    const line = "11.10.2026,19:11:08,א,—,10.00,116000001.pdf\n";
    const r = parseWoltCsv(header + line + line, "x.csv");
    expect(r.rows).toHaveLength(2);
    expect(new Set(r.rows.map((x) => x.externalRowKey)).size).toBe(2);
  });
  it("reports invalid rows instead of guessing", () => {
    const r = parseWoltCsv(header + "99.99.2026,19:11:08,א,—,10.00,a.pdf\n", "x.csv");
    expect(r.rows).toHaveLength(0);
    expect(r.errors).toHaveLength(1);
  });
  it("reports missing columns", () => {
    const r = parseWoltCsv("תאריך,שעה\n11.10.2026,19:00:00\n", "x.csv");
    expect(r.errors[0].message).toContain("עמודות חסרות");
  });
});

describe("shift duration (acceptance 8)", () => {
  it("19:00–22:00 is 3.0 hours", () => {
    const w = computeShiftWindow("2026-10-21", "19:00", "22:00");
    expect(w.durationHours).toBe(3);
    expect(w.crossesMidnight).toBe(false);
  });
  it("22:00–01:00 crosses midnight and is 3.0 hours", () => {
    const w = computeShiftWindow("2026-10-22", "22:00", "01:00");
    expect(w.durationHours).toBe(3);
    expect(w.crossesMidnight).toBe(true);
    expect(w.endedAt).toBe("2026-10-23T01:00");
  });
  it("is DST aware in Asia/Jerusalem (2026-10-25 clocks go back)", () => {
    // Israel leaves DST on the last Sunday of October: 02:00 -> 01:00 on 25.10.2026.
    expect(computeShiftWindow("2026-10-24", "23:00", "03:00").durationHours).toBe(5);
  });
  it("rejects zero duration", () => {
    expect(() => computeShiftWindow("2026-10-21", "19:00", "19:00")).toThrow();
  });
});
