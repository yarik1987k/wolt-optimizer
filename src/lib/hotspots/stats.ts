/** Small, dependency-free interval estimators for the hotspot analysis. */

export interface Interval {
  lower: number;
  upper: number;
}

/** ln Γ(x), Lanczos approximation (g = 7, n = 9). */
export function lnGamma(x: number): number {
  const c = [
    0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313, -176.61502916214059, 12.507343278686905,
    -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
  ];
  if (x < 0.5) return Math.log(Math.PI / Math.sin(Math.PI * x)) - lnGamma(1 - x);
  x -= 1;
  let a = c[0];
  const t = x + 7.5;
  for (let i = 1; i < 9; i++) a += c[i] / (x + i);
  return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
}

/** Regularized lower incomplete gamma P(a, x). */
export function gammaP(a: number, x: number): number {
  if (x <= 0) return 0;
  const lead = Math.exp(-x + a * Math.log(x) - lnGamma(a));
  if (x < a + 1) {
    // Series.
    let sum = 1 / a;
    let term = sum;
    for (let n = 1; n < 1000; n++) {
      term *= x / (a + n);
      sum += term;
      if (Math.abs(term) < Math.abs(sum) * 1e-15) break;
    }
    return sum * lead;
  }
  // Continued fraction for Q(a, x) (modified Lentz).
  const tiny = 1e-300;
  let b = x + 1 - a;
  let c = 1 / tiny;
  let dd = 1 / b;
  let h = dd;
  for (let i = 1; i < 1000; i++) {
    const an = -i * (i - a);
    b += 2;
    dd = an * dd + b;
    if (Math.abs(dd) < tiny) dd = tiny;
    c = b + an / c;
    if (Math.abs(c) < tiny) c = tiny;
    dd = 1 / dd;
    const del = dd * c;
    h *= del;
    if (Math.abs(del - 1) < 1e-15) break;
  }
  return 1 - lead * h;
}

/** x such that P(a, x) = p (bisection; plenty precise for display). */
export function gammaQuantile(p: number, a: number): number {
  let lo = 0;
  let hi = Math.max(1, a);
  while (gammaP(a, hi) < p) hi *= 2;
  for (let i = 0; i < 200; i++) {
    const mid = (lo + hi) / 2;
    if (gammaP(a, mid) < p) lo = mid;
    else hi = mid;
  }
  return (lo + hi) / 2;
}

/**
 * Exact (Garwood) two-sided interval for a Poisson count k:
 * lower = χ²(α/2; 2k)/2, upper = χ²(1−α/2; 2k+2)/2 — i.e. gamma quantiles with shape k and k+1.
 */
export function poissonInterval(k: number, confidence = 0.95): Interval {
  const alpha = 1 - confidence;
  return {
    lower: k === 0 ? 0 : gammaQuantile(alpha / 2, k),
    upper: gammaQuantile(1 - alpha / 2, k + 1),
  };
}

/** Rate (events per hour) with the Garwood interval; null when there is no exposure. */
export function poissonRate(k: number, exposureHours: number, confidence = 0.95): { rate: number; interval: Interval } | null {
  if (!(exposureHours > 0)) return null;
  const i = poissonInterval(k, confidence);
  return { rate: k / exposureHours, interval: { lower: i.lower / exposureHours, upper: i.upper / exposureHours } };
}

/** Wilson score interval for a proportion (95% by default). */
export function wilsonInterval(successes: number, n: number, z = 1.959963984540054): Interval | null {
  if (n <= 0) return null;
  const p = successes / n;
  const denom = 1 + (z * z) / n;
  const centre = (p + (z * z) / (2 * n)) / denom;
  const half = (z * Math.sqrt((p * (1 - p)) / n + (z * z) / (4 * n * n))) / denom;
  return { lower: Math.max(0, centre - half), upper: Math.min(1, centre + half) };
}
