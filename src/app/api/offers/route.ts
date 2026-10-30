import { handle } from "@/lib/api";
import { getOfferAnalytics, parseOfferFilters } from "@/lib/offers/service";

export const dynamic = "force-dynamic";

/** Observed offers + analytics + hypotheses. Amounts in agorot as displayed (VAT basis unknown). */
export const GET = (req: Request) =>
  handle(async () => {
    const sp = Object.fromEntries(new URL(req.url).searchParams);
    const { rows, ...rest } = await getOfferAnalytics(parseOfferFilters(sp));
    return { amount_basis: "AS_DISPLAYED_VAT_UNKNOWN", offers: rows, ...rest };
  });
