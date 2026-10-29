import { handle } from "@/lib/api";
import { getSettings, settingsSchema, updateSettings } from "@/lib/settings";

export const dynamic = "force-dynamic";

export const GET = () => handle(getSettings);
export const PATCH = (req: Request) => handle(async () => updateSettings(settingsSchema.partial().parse(await req.json())));
