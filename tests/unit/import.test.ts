import { beforeEach, describe, expect, it } from "vitest";
import { prisma } from "@/lib/db";
import { commitImport, previewImport } from "@/lib/import/service";
import { getBuckets, getOverview } from "@/lib/analytics/metrics";
import { agorotToShekelString } from "@/lib/money";
import { FIXTURE_NAME, fixtureText, fixtureWithDuplicateReportCopy, resetDb } from "../helpers";

beforeEach(resetDb);

describe("fixture reconciliation (acceptance 1–6, 11)", () => {
  it("1. imports 44 rows, total 192536 agorot, 41/2/1 by type", async () => {
    const preview = await previewImport(fixtureText(), FIXTURE_NAME);
    expect(preview.parsedRowCount).toBe(44);
    expect(preview.hasErrors).toBe(false);
    const res = await commitImport(fixtureText(), FIXTURE_NAME);
    expect(res.insertedCount).toBe(44);
    expect(await prisma.deliveryRecord.count()).toBe(44);
    const total = await prisma.deliveryRecord.aggregate({ _sum: { amountAgorot: true } });
    expect(total._sum.amountAgorot).toBe(192536);
    const byType = Object.fromEntries(
      (await prisma.deliveryRecord.groupBy({ by: ["type"], _count: true })).map((g) => [g.type, g._count]),
    );
    expect(byType).toEqual({ DELIVERY: 41, BONUS: 2, ADJUSTMENT: 1 });
  });

  it("per-report control totals: 116000001 = 72146, 116000002 = 120390", async () => {
    const preview = await previewImport(fixtureText(), FIXTURE_NAME);
    const reports = Object.fromEntries(preview.reports.map((r) => [r.reportKey, r]));
    expect(reports["116000001"].totalAgorot).toBe(72146);
    expect(reports["116000001"].rowCount).toBe(11);
    expect(reports["116000002"].totalAgorot).toBe(120390);
    expect(reports["116000002"].rowCount).toBe(33);
  });

  it("2. DELIVERY sum = 151836 agorot and average rounds to ₪37.03", async () => {
    await commitImport(fixtureText(), FIXTURE_NAME);
    const o = await getOverview({});
    expect(o.delivery_income_ex_vat_agorot).toBe(151836);
    expect(o.delivery_record_count).toBe(41);
    expect(agorotToShekelString(o.avg_amount_per_record_agorot!)).toBe("37.03");
    expect(o.bonus_total_agorot).toBe(40000);
    expect(o.adjustment_total_agorot).toBe(700);
    expect(o.all_rows_total_agorot).toBe(192536);
  });

  it("3. the duplicate copy of report 116000001 is recognised and not counted again", async () => {
    const text = fixtureWithDuplicateReportCopy();
    const preview = await previewImport(text, "with-copy.csv");
    expect(preview.parsedRowCount).toBe(55);
    expect(preview.uniqueRowCount).toBe(44);
    expect(preview.inFileDuplicateCount).toBe(11);
    expect(preview.totalAgorot).toBe(192536);
    expect(preview.findings.some((f) => f.message.includes("116000001") && f.message.includes("116000001 (1).pdf"))).toBe(true);
    const res = await commitImport(text, "with-copy.csv");
    expect(res.insertedCount).toBe(44);
    expect(res.skippedInFile).toBe(11);
    expect(await prisma.deliveryRecord.count()).toBe(44);

    // And importing the copy alone later adds nothing either.
    const copyOnly = text.split("\n").filter((l, i) => i === 0 || l.endsWith("116000001 (1).pdf")).join("\n");
    const res2 = await commitImport(copyOnly, "copy-only.csv");
    expect(res2.insertedCount).toBe(0);
    expect(res2.skippedCount).toBe(11);
    expect(await prisma.deliveryRecord.count()).toBe(44);
  });

  it("4. importing the same CSV twice leaves 44 rows and reports skips", async () => {
    await commitImport(fixtureText(), FIXTURE_NAME);
    const preview = await previewImport(fixtureText(), FIXTURE_NAME);
    expect(preview.alreadyImportedCount).toBe(44);
    expect(preview.identicalFileImportedAt).not.toBeNull();
    const second = await commitImport(fixtureText(), FIXTURE_NAME);
    expect(second.insertedCount).toBe(0);
    expect(second.skippedCount).toBe(44);
    expect(await prisma.deliveryRecord.count()).toBe(44);
    expect(await prisma.importBatch.count()).toBe(2);
  });

  it("a CSV with a small change is not treated as a brand-new report", async () => {
    await commitImport(fixtureText(), FIXTURE_NAME);
    // Same content, re-saved with CRLF and BOM by a spreadsheet program.
    const resaved = "﻿" + fixtureText().replace(/\n/g, "\r\n");
    const r = await commitImport(resaved, "resaved.csv");
    expect(r.insertedCount).toBe(0);
    expect(await prisma.deliveryRecord.count()).toBe(44);
  });

  it("5. the ₪300 bonus is not a ₪300 delivery", async () => {
    await commitImport(fixtureText(), FIXTURE_NAME);
    const bonus = await prisma.deliveryRecord.findFirstOrThrow({ where: { amountAgorot: 30000 } });
    expect(bonus.type).toBe("BONUS");
    const o = await getOverview({});
    const maxDelivery = await prisma.deliveryRecord.aggregate({ where: { type: "DELIVERY" }, _max: { amountAgorot: true } });
    expect(maxDelivery._max.amountAgorot).toBe(7142);
    expect(o.delivery_income_ex_vat_agorot).toBe(151836);
    const hours = await getBuckets("hour", {});
    expect(hours.buckets.reduce((a, b) => a + b.sum_agorot, 0)).toBe(151836);
  });

  it("6. a row with two merchants stays one payment row", async () => {
    await commitImport(fixtureText(), FIXTURE_NAME);
    const rows = await prisma.deliveryRecord.findMany({ where: { merchantRaw: { contains: "בינו סנטר" } } });
    expect(rows).toHaveLength(1);
    expect(rows[0].isBundle).toBe(true);
    expect(rows[0].amountAgorot).toBe(5677);
    expect(await prisma.deliveryRecord.count({ where: { type: "DELIVERY", isBundle: true } })).toBe(7);
    // The address with an internal comma is not a bundle.
    const addr = await prisma.deliveryRecord.findFirstOrThrow({ where: { merchantRaw: { contains: "Rishon LeZion" } } });
    expect(addr.isBundle).toBe(false);
  });

  it("11. merchant chart never attributes bundle income to individual merchants", async () => {
    await commitImport(fixtureText(), FIXTURE_NAME);
    const m = await getBuckets("merchant", {});
    // ג׳אג׳ה עוף בגריל appears alone once (23.28) and inside a bundle (56.77).
    const jaja = m.buckets.find((b) => b.key === "ג׳אג׳ה עוף בגריל")!;
    expect(jaja.sum_agorot).toBe(2328);
    expect(jaja.n_records).toBe(1);
    expect(m.buckets.every((b) => !b.key.includes(", "))).toBe(true);
    expect(m.bundles!.n_records).toBe(7);
    const singlesSum = m.buckets.reduce((a, b) => a + b.sum_agorot, 0);
    expect(singlesSum + m.bundles!.sum_agorot).toBe(151836);
  });
});
