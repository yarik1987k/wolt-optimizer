import { handle, readUpload } from "@/lib/api";
import { getImportHistory } from "@/lib/imports";
import { commitImport } from "@/lib/import/service";

export const dynamic = "force-dynamic";

export const GET = () => handle(getImportHistory);

/** Commits an uploaded CSV (idempotent). Use /api/imports/preview first to review it. */
export const POST = (req: Request) =>
  handle(async () => {
    const { text, fileName } = await readUpload(req);
    return commitImport(text, fileName);
  });
