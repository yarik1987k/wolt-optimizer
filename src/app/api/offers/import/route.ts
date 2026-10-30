import { handle } from "@/lib/api";
import { importCollectorExport, MAX_OFFER_EXPORT_BYTES, OfferImportError } from "@/lib/offers/import";

/** Multipart upload of a Wolt Offer Collector diagnostics export (JSON). Idempotent. */
export const POST = (req: Request) =>
  handle(async () => {
    const form = await req.formData();
    const file = form.get("file");
    if (!(file instanceof File)) throw new OfferImportError("לא צורף קובץ JSON");
    if (file.size > MAX_OFFER_EXPORT_BYTES) throw new OfferImportError("הקובץ גדול מ-50MB");
    return importCollectorExport(await file.text(), file.name);
  });
