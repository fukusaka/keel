package io.github.fukusaka.keel.server.http

import kotlin.time.Instant

/** Seconds in one day. */
private const val SECONDS_PER_DAY = 86_400L

/** Seconds in one hour. */
private const val SECONDS_PER_HOUR = 3_600L

/** Seconds in one minute. */
private const val SECONDS_PER_MINUTE = 60L

/** Days in a 400-year Gregorian cycle. */
private const val DAYS_PER_400_YEARS = 146_097L

/** The civil epoch offset (1970-01-01) expressed in the days-from-0000-03-01 calendar. */
private const val EPOCH_DAYS_OFFSET = 719_468L

/** Abbreviated day-of-week names, Sunday-indexed, as required by RFC 9110 IMF-fixdate. */
private val WEEKDAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/** Abbreviated month names, 1-indexed (index 0 unused), as required by RFC 9110 IMF-fixdate. */
private val MONTHS = arrayOf(
    "", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/** Floored integer division — `a / b` rounded toward negative infinity. */
private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0L) q - 1 else q
}

/** Floored modulo — the non-negative remainder of [a] divided by [b]. */
private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

/**
 * Formats [instant] as an RFC 9110 §5.6.7 IMF-fixdate string, the
 * preferred `Last-Modified` / `Date` header form — for example
 * `Sun, 06 Nov 1994 08:49:37 GMT`.
 *
 * The calendar conversion uses Howard Hinnant's `civil_from_days`
 * algorithm so no platform date library is needed in `commonMain`.
 */
internal fun formatHttpDate(instant: Instant): String {
    val epochSeconds = instant.epochSeconds
    val days = floorDiv(epochSeconds, SECONDS_PER_DAY)
    val secondOfDay = floorMod(epochSeconds, SECONDS_PER_DAY)

    val hour = secondOfDay / SECONDS_PER_HOUR
    val minute = (secondOfDay % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val second = secondOfDay % SECONDS_PER_MINUTE

    // Day of week: 1970-01-01 was a Thursday (index 4).
    val weekday = floorMod(days + 4, 7).toInt()

    val (year, month, day) = civilFromDays(days)

    return buildString {
        append(WEEKDAYS[weekday])
        append(", ")
        append(pad2(day))
        append(' ')
        append(MONTHS[month])
        append(' ')
        append(year)
        append(' ')
        append(pad2(hour))
        append(':')
        append(pad2(minute))
        append(':')
        append(pad2(second))
        append(" GMT")
    }
}

/**
 * Parses an RFC 9110 IMF-fixdate string (the only form keel emits and the
 * dominant `If-Modified-Since` form) into an [Instant], or null when the
 * string is not a well-formed IMF-fixdate.
 *
 * The two obsolete HTTP-date forms (RFC 850 and asctime) are not parsed;
 * a malformed or unrecognised value yields null, which the serve layer
 * treats as "condition not met" — a safe degradation to a `200`.
 */
internal fun parseHttpDate(value: String): Instant? {
    // IMF-fixdate: "Sun, 06 Nov 1994 08:49:37 GMT" — fixed 29-character layout.
    val text = value.trim()
    if (text.length != IMF_LENGTH) return null
    val day = text.substring(5, 7).toLongOrNull() ?: return null
    val month = MONTHS.indexOf(text.substring(8, 11))
    if (month < 1) return null
    val year = text.substring(12, 16).toLongOrNull() ?: return null
    val hour = text.substring(17, 19).toLongOrNull() ?: return null
    val minute = text.substring(20, 22).toLongOrNull() ?: return null
    val second = text.substring(23, 25).toLongOrNull() ?: return null
    if (text.substring(25) != " GMT") return null
    val days = daysFromCivil(year, month, day)
    val epochSeconds = days * SECONDS_PER_DAY + hour * SECONDS_PER_HOUR + minute * SECONDS_PER_MINUTE + second
    return Instant.fromEpochSeconds(epochSeconds)
}

/** civil_from_days (Howard Hinnant): days since 1970-01-01 to a (year, month, day) triple. */
private fun civilFromDays(days: Long): Triple<Long, Int, Long> {
    val z = days + EPOCH_DAYS_OFFSET
    val era = (if (z >= 0) z else z - (DAYS_PER_400_YEARS - 1)) / DAYS_PER_400_YEARS
    val dayOfEra = z - era * DAYS_PER_400_YEARS
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    val year = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val civilYear = if (month <= 2) year + 1 else year
    return Triple(civilYear, month.toInt(), day)
}

/** days_from_civil (Howard Hinnant): a (year, month, day) triple to days since 1970-01-01. */
private fun daysFromCivil(year: Long, month: Int, day: Long): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * DAYS_PER_400_YEARS + dayOfEra - EPOCH_DAYS_OFFSET
}

/** Left-pads [value] to two digits. */
private fun pad2(value: Long): String = if (value < 10) "0$value" else value.toString()

/** Fixed character length of an IMF-fixdate string. */
private const val IMF_LENGTH = 29
