/**
 * Small-sample statistics for the evidence layer. Pure. No universal "n ≥ 10" proof threshold: a claim is
 * judged by its unit of independence, its spread, its interval and whether it repeats.
 */

/** Two-sided 95% t critical values by degrees of freedom (1–30); larger df → 1.96. */
const T95 = [12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.16, 2.145, 2.131, 2.12, 2.11, 2.101, 2.093, 2.086, 2.08, 2.074, 2.069, 2.064, 2.06, 2.056, 2.052, 2.048, 2.045, 2.042];
export const t95 = (df: number) => (df < 1 ? Infinity : df <= 30 ? T95[Math.floor(df) - 1] : 1.96);

export interface Summary {
  n: number;
  mean: number | null;
  sd: number | null;
  /** 95% interval for the mean; null when n < 2 (spread unknown). */
  ci95: { low: number; high: number } | null;
  /** Coefficient of variation (sd / |mean|). */
  cv: number | null;
}

export function summarize(xs: number[]): Summary {
  const n = xs.length;
  if (!n) return { n, mean: null, sd: null, ci95: null, cv: null };
  const mean = xs.reduce((a, b) => a + b, 0) / n;
  if (n < 2) return { n, mean, sd: null, ci95: null, cv: null };
  const sd = Math.sqrt(xs.reduce((a, b) => a + (b - mean) ** 2, 0) / (n - 1));
  const half = t95(n - 1) * (sd / Math.sqrt(n));
  return { n, mean, sd, ci95: { low: mean - half, high: mean + half }, cv: mean === 0 ? null : sd / Math.abs(mean) };
}

/** Kish effective sample size of a weighted mean: (Σw)² ÷ Σw². Unequal weights shrink it. */
export function kishEffectiveN(weights: number[]): number {
  const s = weights.reduce((a, b) => a + b, 0);
  const s2 = weights.reduce((a, b) => a + b * b, 0);
  return s2 === 0 ? 0 : (s * s) / s2;
}

/**
 * Effective sample size for units nested in clusters (chains in shifts): the Kish n deflated by the design
 * effect 1 + (m̄ − 1)·ICC. With one cluster the between-cluster variance is unknown, so for any claim that must
 * generalise beyond that cluster (a shift-level claim) the effective n is 1.
 */
export function clusteredEffectiveN(kishN: number, clusters: number, meanClusterSize: number, icc: number | null): number {
  if (clusters <= 1) return 1;
  if (icc === null) return Math.min(kishN, clusters); // ICC unknown → conservative: no more than the clusters
  return kishN / (1 + (meanClusterSize - 1) * icc);
}

export type DifferenceVerdict = "SUPPORTS" | "CONTRADICTS" | "NO_PRACTICAL_EFFECT" | "INCONCLUSIVE";

export interface Difference {
  a: Summary;
  b: Summary;
  /** mean(a) − mean(b) and its Welch 95% interval. */
  diff: number | null;
  ci95: { low: number; high: number } | null;
  verdict: DifferenceVerdict;
  reasons: string[];
}

/**
 * Compares two arms of independent units (e.g. blocks of shift time). SUPPORTS = a > b with the interval above
 * zero; CONTRADICTS = interval below zero; NO_PRACTICAL_EFFECT = interval inside ±smallestEffect;
 * INCONCLUSIVE otherwise or with fewer than minPerArm units in an arm.
 */
export function compareArms(a: number[], b: number[], minPerArm: number, smallestEffect: number): Difference {
  const sa = summarize(a);
  const sb = summarize(b);
  const reasons: string[] = [];
  if (sa.n < minPerArm || sb.n < minPerArm) reasons.push(`needs ≥ ${minPerArm} independent units per arm (has ${sa.n} and ${sb.n})`);
  if (sa.mean === null || sb.mean === null || sa.sd === null || sb.sd === null) {
    return { a: sa, b: sb, diff: sa.mean !== null && sb.mean !== null ? sa.mean - sb.mean : null, ci95: null, verdict: "INCONCLUSIVE", reasons: [...reasons, "spread unknown in at least one arm"] };
  }
  const va = (sa.sd * sa.sd) / sa.n;
  const vb = (sb.sd * sb.sd) / sb.n;
  const df = (va + vb) ** 2 / ((va * va) / (sa.n - 1) + (vb * vb) / (sb.n - 1));
  const diff = sa.mean - sb.mean;
  const half = t95(df) * Math.sqrt(va + vb);
  const ci95 = { low: diff - half, high: diff + half };
  let verdict: DifferenceVerdict = "INCONCLUSIVE";
  if (!reasons.length) {
    if (ci95.low > 0) verdict = "SUPPORTS";
    else if (ci95.high < 0) verdict = "CONTRADICTS";
    else if (ci95.low > -smallestEffect && ci95.high < smallestEffect) verdict = "NO_PRACTICAL_EFFECT";
    else reasons.push("interval includes both zero and a practically relevant effect");
  }
  return { a: sa, b: sb, diff, ci95, verdict, reasons };
}

/** Units per arm to detect `effect` with ~80% power at 5% two-sided, given a per-unit sd (normal approximation). */
export function unitsPerArm(sd: number, effect: number): number {
  if (effect <= 0) return Infinity;
  return Math.ceil(2 * ((1.96 + 0.84) ** 2) * (sd / effect) ** 2);
}
