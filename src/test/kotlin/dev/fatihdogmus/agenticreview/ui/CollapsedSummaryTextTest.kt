package dev.fatihdogmus.agenticreview.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CollapsedSummaryTextTest {
    @Test
    fun collapsesWhitespaceAndNewlinesToSingleSpaces() {
        assertThat(collapsedSummaryText("  first line\n\n  second\tline  "))
            .isEqualTo("first line second line")
    }

    @Test
    fun truncatesToBudgetIncludingEllipsis() {
        val long = "x".repeat(200)
        val result = collapsedSummaryText(long)
        // The budget is the total length of the summary, ellipsis included.
        assertThat(result).hasSize(COLLAPSED_SUMMARY_BUDGET)
        assertThat(result).endsWith("…")
        assertThat(result.dropLast(1)).isEqualTo("x".repeat(COLLAPSED_SUMMARY_BUDGET - 1))
    }

    @Test
    fun textExactlyAtBudgetIsNotTruncated() {
        val exact = "y".repeat(COLLAPSED_SUMMARY_BUDGET)
        assertThat(collapsedSummaryText(exact)).isEqualTo(exact)
    }

    @Test
    fun textOneOverBudgetIsTruncatedToBudget() {
        val over = "z".repeat(COLLAPSED_SUMMARY_BUDGET + 1)
        val result = collapsedSummaryText(over)
        assertThat(result).hasSize(COLLAPSED_SUMMARY_BUDGET).endsWith("…")
    }

    @Test
    fun leavesShortTextUntouched() {
        assertThat(collapsedSummaryText("short")).isEqualTo("short")
    }

    @Test
    fun budgetIs120Characters() {
        assertThat(COLLAPSED_SUMMARY_BUDGET).isEqualTo(120)
    }
}
