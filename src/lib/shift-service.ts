import { prisma } from "@/lib/db";
import { shiftInputSchema, toShiftData, type ShiftInput } from "@/lib/analytics/shifts";

async function syncExperiment(shiftId: string, input: ShiftInput) {
  if (input.experimentId === undefined) return;
  await prisma.experimentShift.deleteMany({ where: { shiftId } });
  if (input.experimentId && input.experimentGroup) {
    await prisma.experimentShift.create({
      data: { shiftId, experimentId: input.experimentId, group: input.experimentGroup },
    });
  }
}

export async function createShift(body: unknown) {
  const input = shiftInputSchema.parse(body);
  const shift = await prisma.shift.create({ data: toShiftData(input) });
  await syncExperiment(shift.id, input);
  return shift;
}

export async function updateShift(id: string, body: unknown) {
  const current = await prisma.shift.findUniqueOrThrow({ where: { id } });
  const input = shiftInputSchema.parse({
    date: current.date,
    startTime: current.startTime,
    endTime: current.endTime,
    areaText: current.areaText,
    distanceKm: current.distanceKm,
    vehicleCostPerKmAgorot: current.vehicleCostPerKmAgorot,
    followedRecommendation: current.followedRecommendation,
    notes: current.notes,
    ...(body as object),
  });
  const shift = await prisma.shift.update({ where: { id }, data: toShiftData(input) });
  await syncExperiment(id, input);
  return shift;
}
