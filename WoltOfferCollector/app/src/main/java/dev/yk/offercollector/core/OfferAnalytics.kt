package dev.yk.offercollector.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Offer analytics — a line-by-line port of Wolt Optimizer's `src/lib/offers/analytics.ts`
 * (`analyzeOffers` + `summarize`), so the phone and the web engine give the same numbers.
 * Amounts are agorot as displayed (VAT basis unknown). Asia/Jerusalem local time.
 */
object OfferAnalytics {
    val ZONE: ZoneId = ZoneId.of("Asia/Jerusalem")
    val PRICE_THRESHOLDS_SHEKEL = listOf(25, 30, 35, 40, 45, 50, 60)
    private const val BIN = 500L

    data class Point(val firstSeenMs: Long, val amountAgorot: Long, val distanceMeters: Long?, val onScreenSeconds: Long)

    data class Summary(val n: Int, val sum: Double, val mean: Double?, val median: Double?, val sd: Double?, val min: Double?, val max: Double?)

    data class HourRow(val hour: Int, val n: Int, val meanAgorot: Double, val medianAgorot: Double, val sumAgorot: Double)
    data class Bin(val fromAgorot: Long, val toAgorot: Long, val n: Int)
    data class Threshold(val shekel: Int, val atOrAbove: Int, val share: Double?)

    data class Result(
        val count: Int,
        val amount: Summary,
        val distanceM: Summary,
        val offersWithDistance: Int,
        /** sum(amount) / sum(km) over offers with a known distance. */
        val agorotPerKmAggregate: Double?,
        val agorotPerKm: Summary,
        val byHour: List<HourRow>,
        val distribution: List<Bin>,
        /** Minutes between consecutive offers on the same local date. */
        val gapsMinutes: Summary,
        val thresholds: List<Threshold>,
        val onScreenSeconds: Summary,
    )

    fun summarize(values: List<Double>): Summary {
        val n = values.size
        if (n == 0) return Summary(0, 0.0, null, null, null, null, null)
        val s = values.sorted()
        val sum = s.sum()
        val mean = sum / n
        val median = if (n % 2 == 1) s[(n - 1) / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        val sd = if (n < 2) null else sqrt(s.sumOf { (it - mean) * (it - mean) } / (n - 1))
        return Summary(n, sum, mean, median, sd, s.first(), s.last())
    }

    fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate()
    fun localHour(ms: Long): Int = Instant.ofEpochMilli(ms).atZone(ZONE).hour

    fun analyze(points: List<Point>): Result {
        val amounts = points.map { it.amountAgorot.toDouble() }
        val withDistance = points.filter { it.distanceMeters != null && it.distanceMeters > 0 }
        val sumKm = withDistance.sumOf { it.distanceMeters!! } / 1000.0

        val hours = points.groupBy { localHour(it.firstSeenMs) }.toSortedMap()
        val bins = points.groupingBy { floor(it.amountAgorot.toDouble() / BIN).toLong() * BIN }.eachCount().toSortedMap()

        val sorted = points.sortedBy { it.firstSeenMs }
        val gaps = sorted.zipWithNext()
            .filter { (a, b) -> localDate(a.firstSeenMs) == localDate(b.firstSeenMs) }
            .map { (a, b) -> (b.firstSeenMs - a.firstSeenMs) / 60_000.0 }

        return Result(
            count = points.size,
            amount = summarize(amounts),
            distanceM = summarize(withDistance.map { it.distanceMeters!!.toDouble() }),
            offersWithDistance = withDistance.size,
            agorotPerKmAggregate = if (sumKm > 0) withDistance.sumOf { it.amountAgorot } / sumKm else null,
            agorotPerKm = summarize(withDistance.map { it.amountAgorot / (it.distanceMeters!! / 1000.0) }),
            byHour = hours.map { (h, v) ->
                val s = summarize(v.map { it.amountAgorot.toDouble() })
                HourRow(h, s.n, s.mean!!, s.median!!, s.sum)
            },
            distribution = bins.map { (b, n) -> Bin(b, b + BIN, n) },
            gapsMinutes = summarize(gaps),
            thresholds = PRICE_THRESHOLDS_SHEKEL.map { t ->
                val n = amounts.count { it >= t * 100 }
                Threshold(t, n, if (amounts.isEmpty()) null else n.toDouble() / amounts.size)
            },
            onScreenSeconds = summarize(points.map { it.onScreenSeconds.toDouble() }),
        )
    }

    /** DetectedOffer → Point. Distance "6.4" km → 6400 m (same rounding as the engine's import). */
    fun point(o: DetectedOffer): Point = Point(
        firstSeenMs = o.firstSeenMs,
        amountAgorot = o.amountAgorot,
        distanceMeters = o.distanceKm?.toBigDecimalOrNull()?.multiply(1000.toBigDecimal())?.toLong(),
        onScreenSeconds = ((o.lastSeenMs - o.firstSeenMs) / 1000).coerceAtLeast(1),
    )
}
