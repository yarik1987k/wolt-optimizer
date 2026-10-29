import { handle } from "@/lib/api";
import { reclassifyAll } from "@/lib/reclassify";

export const POST = () => handle(reclassifyAll);
