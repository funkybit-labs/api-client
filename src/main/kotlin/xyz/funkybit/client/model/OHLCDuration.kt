package xyz.funkybit.client.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import xyz.funkybit.client.utils.truncateTo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

enum class OHLCDuration {
    P1M,
    P5M,
    P15M,
    P1H,
    P4H,
    P1D,
    ;

    fun durationStart(instant: Instant): Instant =
        when (this) {
            P1M -> instant.truncateTo(DateTimeUnit.MINUTE)
            P5M -> instant.truncateTo(DateTimeUnit.MINUTE * 5)
            P15M -> instant.truncateTo(DateTimeUnit.MINUTE * 15)
            P1H -> instant.truncateTo(DateTimeUnit.HOUR)
            P4H -> instant.truncateTo(DateTimeUnit.HOUR * 4)
            P1D -> instant.truncateTo(DateTimeUnit.HOUR * 24)
        }

    fun interval(): Duration =
        when (this) {
            P1M -> 1.minutes
            P5M -> 5.minutes
            P15M -> 15.minutes
            P1H -> 1.hours
            P4H -> 4.hours
            P1D -> 1.days
        }
}
