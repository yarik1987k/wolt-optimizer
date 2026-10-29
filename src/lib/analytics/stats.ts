export interface Summary {
  n: number;
  sum: number;
  mean: number | null;
  median: number | null;
  sd: number | null; // sample standard deviation (n-1); null when n < 2
  min: number | null;
  max: number | null;
}

export function summarize(values: number[]): Summary {
  const n = values.length;
  if (n === 0) return { n, sum: 0, mean: null, median: null, sd: null, min: null, max: null };
  const sorted = [...values].sort((a, b) => a - b);
  const sum = sorted.reduce((a, b) => a + b, 0);
  const mean = sum / n;
  const median = n % 2 ? sorted[(n - 1) / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
  const sd = n < 2 ? null : Math.sqrt(sorted.reduce((a, v) => a + (v - mean) ** 2, 0) / (n - 1));
  return { n, sum, mean, median, sd, min: sorted[0], max: sorted[n - 1] };
}
