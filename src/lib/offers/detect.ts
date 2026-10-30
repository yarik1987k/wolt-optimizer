import type { CandidateFields, ScreenEvent } from "./collector-format";

/**
 * Turns Collector screen snapshots into observed offers. Pure; mirrors the Collector's own rule
 * (WoltOfferCollector .../core/DetectedOffers.kt): an offer card is a screen with a ₪ amount AND
 * the card's accept button. A price without an accept button (e.g. the bonus banner) is not an offer.
 */
export const ACCEPT_WORDS = new Set(["принять", "accept", "קבל", "אשר"]);
const BONUS_WORDS = /бонус|bonus|בונוס/i;

// Wolt package-size tag ("Тяжелая (более 13 кг)"). Collector p1-pickup-1 stored it as the
// restaurant name by mistake (fixed in p1-pickup-2); such "names" are dropped on import.
const WEIGHT_UNIT = /\d+\s*(кг|kg|ק"ג|ק״ג|קג|ק'ג)(?![\p{L}\d])/iu;
const SIZE_WORD_WITH_BRACKET = /^(тяжел\p{L}*|средн\p{L}*|л[её]гк\p{L}*|heavy|medium|light|small|large|כבד\p{L}*|בינוני\p{L}*|קל\p{L}?)\s*\(/iu;
export const isWeightTag = (s: string) => WEIGHT_UNIT.test(s) || SIZE_WORD_WITH_BRACKET.test(s.trim());

// Wolt step labels that collector versions before 0.9.1 stored in the business slot (real shift
// 29.10.2026): "Заберите заказы: 2", "Выдача покупателю", "Проверка документов". Dropped on import.
const WOLT_STEP_LABEL = /^(заберите заказ|выдача покупател|проверка документ)/iu;
export const isWoltLabel = (s: string) => WOLT_STEP_LABEL.test(s.trim());

/** Snapshots of the same card less than this apart belong to one offer. */
export const SAME_OFFER_GAP_MS = 60_000;

export type Detection = "CONFIRMED_CARD" | "PARTIAL_CARD";

/** Meaning of detectionConfidence: how complete the card read was, NOT whether it is verified. */
export const DETECTION_CONFIDENCE: Record<Detection, number> = { CONFIRMED_CARD: 0.9, PARTIAL_CARD: 0.6 };

export interface OfferObservationInput {
  screenEventId: string;
  observedAtMs: number;
  sourcePackage: string;
  amountAgorot: number;
  distanceMeters: number | null;
  durationMin: number | null;
  stopsCount: number | null;
  pickupNames: string[];
  parseVersion: string;
  coalescedEventCount: number;
}

export interface DetectedOffer {
  offerKey: string; // screenEventId of the first observation
  sourcePackage: string;
  firstSeenMs: number;
  lastSeenMs: number;
  amountAgorot: number;
  distanceMeters: number | null;
  durationMin: number | null;
  stopsCount: number | null;
  pickupNames: string[];
  detection: Detection;
  observations: OfferObservationInput[];
}

/** "4.5" km -> 4500 m, exact decimal parsing (no float). Null when not a plain decimal. */
export function kmToMeters(km: string): number | null {
  const m = /^(\d+)(?:\.(\d{1,3}))?$/.exec(km.trim());
  if (!m) return null;
  return Number(m[1]) * 1000 + Number((m[2] ?? "").padEnd(3, "0"));
}

/** "[AMOUNT ₪27.49]" -> 2749 */
function amountFromRedacted(redacted: string): number | null {
  const m = /\[AMOUNT ₪(-?)(\d+)\.(\d{2})\]/.exec(redacted);
  return m ? (m[1] ? -1 : 1) * (Number(m[2]) * 100 + Number(m[3])) : null;
}

/**
 * The card's price: the first ₪ amount in tree order that is not inside a bonus element.
 * Falls back to the first listed amount only when no node list is available and the screen
 * mentions no bonus at all.
 */
export function cardAmount(f: CandidateFields): number | null {
  for (const n of f.nodes) {
    const a = amountFromRedacted(n.redacted);
    if (a === null) continue;
    if (BONUS_WORDS.test(n.redacted) || BONUS_WORDS.test(n.viewId ?? "")) continue;
    return a;
  }
  if (f.nodes.length === 0 && f.amounts_agorot.length && !f.ui_keywords.some((k) => BONUS_WORDS.test(k))) return f.amounts_agorot[0];
  return null;
}

export function isOfferCard(f: CandidateFields | null): f is CandidateFields {
  return !!f && f.amounts_agorot.length > 0 && f.ui_keywords.some((k) => ACCEPT_WORDS.has(k));
}

export function toObservation(e: ScreenEvent): OfferObservationInput | null {
  const f = e.redacted_candidate_fields;
  if (!isOfferCard(f)) return null;
  const amount = cardAmount(f);
  if (amount === null || amount <= 0) return null;
  const km = f.distances_km[0];
  return {
    screenEventId: e.id,
    observedAtMs: Date.parse(e.observed_at_utc),
    sourcePackage: e.source_package,
    amountAgorot: amount,
    distanceMeters: km === undefined ? null : kmToMeters(km),
    durationMin: f.durations_min[0] ?? null,
    stopsCount: f.stops_counts[0] ?? null,
    pickupNames: f.pickup_names.filter((n) => !isWeightTag(n) && !isWoltLabel(n)),
    parseVersion: e.parse_version,
    coalescedEventCount: e.coalesced_event_count,
  };
}

const compatible = (a: number | null, b: number | null) => a === null || b === null || a === b;

/**
 * Groups observations (any order) into offers: same price, compatible distance, gap ≤ 60 s. The pickup
 * line may change while one card scrolls or a bundle lists its businesses one by one (real 21:21
 * bundle: "Tiv Ta'am", then "Moses Shop"), so names are merged, not compared.
 */
export function groupObservations(observations: OfferObservationInput[]): DetectedOffer[] {
  const sorted = [...observations].sort((a, b) => a.observedAtMs - b.observedAtMs);
  const out: DetectedOffer[] = [];
  for (const o of sorted) {
    const last = out[out.length - 1];
    if (
      last &&
      last.sourcePackage === o.sourcePackage &&
      last.amountAgorot === o.amountAgorot &&
      compatible(last.distanceMeters, o.distanceMeters) &&
      o.observedAtMs - last.lastSeenMs <= SAME_OFFER_GAP_MS
    ) {
      last.lastSeenMs = o.observedAtMs;
      last.distanceMeters ??= o.distanceMeters;
      last.durationMin ??= o.durationMin;
      last.stopsCount ??= o.stopsCount;
      last.pickupNames = [...new Set([...last.pickupNames, ...o.pickupNames])];
      last.observations.push(o);
      last.detection = last.distanceMeters !== null ? "CONFIRMED_CARD" : "PARTIAL_CARD";
    } else {
      out.push({
        offerKey: o.screenEventId,
        sourcePackage: o.sourcePackage,
        firstSeenMs: o.observedAtMs,
        lastSeenMs: o.observedAtMs,
        amountAgorot: o.amountAgorot,
        distanceMeters: o.distanceMeters,
        durationMin: o.durationMin,
        stopsCount: o.stopsCount,
        pickupNames: o.pickupNames,
        detection: o.distanceMeters !== null ? "CONFIRMED_CARD" : "PARTIAL_CARD",
        observations: [o],
      });
    }
  }
  return out;
}
