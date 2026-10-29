import { handle } from "@/lib/api";
import { prisma } from "@/lib/db";
import { createExperiment } from "@/lib/experiment-service";

export const dynamic = "force-dynamic";

export const GET = () =>
  handle(() => prisma.experiment.findMany({ orderBy: { createdAt: "desc" }, include: { _count: { select: { shifts: true } } } }));

export const POST = (req: Request) => handle(async () => createExperiment(await req.json()));
