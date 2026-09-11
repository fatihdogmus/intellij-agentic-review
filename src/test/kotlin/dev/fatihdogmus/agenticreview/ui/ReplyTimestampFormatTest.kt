package dev.fatihdogmus.agenticreview.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReplyTimestampFormatTest {

    @Test
    fun formatsIsoOffsetTimestampAsReadableLocalTime() {
        assertThat(formatReplyTimestamp("2026-09-11T10:12:13+03:00"))
            .isEqualTo("2026-09-11 10:12:13")
    }

    @Test
    fun dropsSubSecondPrecision() {
        // nowIso() emits OffsetDateTime.now(), which carries nanoseconds.
        assertThat(formatReplyTimestamp("2026-09-11T10:12:13.123456789+03:00"))
            .isEqualTo("2026-09-11 10:12:13")
    }

    @Test
    fun preservesTheRecordedWallClockRegardlessOfOffset() {
        // Formatting must not shift the displayed time by the JVM's default zone,
        // otherwise the rendered value depends on where the test runs.
        assertThat(formatReplyTimestamp("2026-09-11T10:12:13-08:00"))
            .isEqualTo("2026-09-11 10:12:13")
        assertThat(formatReplyTimestamp("2026-09-11T10:12:13Z"))
            .isEqualTo("2026-09-11 10:12:13")
    }

    @Test
    fun acceptsLocalDateTimeWithoutOffset() {
        assertThat(formatReplyTimestamp("2026-09-11T10:12:13"))
            .isEqualTo("2026-09-11 10:12:13")
    }

    @Test
    fun fallsBackToRawValueWhenUnparseable() {
        assertThat(formatReplyTimestamp("not a timestamp")).isEqualTo("not a timestamp")
        assertThat(formatReplyTimestamp("")).isEqualTo("")
    }
}
