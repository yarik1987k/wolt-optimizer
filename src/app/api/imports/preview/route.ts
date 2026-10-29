import { handle, readUpload } from "@/lib/api";
import { previewImport } from "@/lib/import/service";

export const POST = (req: Request) =>
  handle(async () => {
    const { text, fileName } = await readUpload(req);
    return previewImport(text, fileName);
  });
