package com.awardwatch.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import java.time.LocalDate;

class DateRangeTest {

    @Test
    void nullStartRejected() {
        assertThatThrownBy(() -> new DateRange(null, LocalDate.of(2026, 8, 9))).isInstanceOf(NullPointerException.class).hasMessage("start cannot be null");
    }

    @Test
    void nullEndRejected() {
        assertThatThrownBy(() -> new DateRange(LocalDate.of(2026, 8, 9), null)).isInstanceOf(NullPointerException.class).hasMessage("end cannot be null");
    }

    @Test
    void endBeforeStartRejected() {
        assertThatThrownBy(() -> new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 7, 9))).isInstanceOf(IllegalArgumentException.class).hasMessage("end cannot be before start");
    }

    @Test
    void endOneDayBeforeStartRejected() {
        assertThatThrownBy(() -> new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 8))).isInstanceOf(IllegalArgumentException.class).hasMessage("end cannot be before start");
    }

    @Test
    void startEqualsEndAccepted() {
        LocalDate date = LocalDate.of(2026, 8, 9);
        assertThatCode(() -> new DateRange(date, date)).doesNotThrowAnyException();
    }

    @Test
    void maxDaysAccepted() {
        LocalDate date = LocalDate.of(2026, 8, 9);
        assertThatCode(() -> new DateRange(date, date.plusDays(DateRange.MAX_DAYS - 1))).doesNotThrowAnyException();
    }

    @Test
    void overMaxDaysRejected() {
        LocalDate date = LocalDate.of(2026, 8, 9);
        assertThatThrownBy(() -> new DateRange(date, date.plusDays(DateRange.MAX_DAYS))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxDaysMessageNamesTheCap() {
        LocalDate date = LocalDate.of(2026, 8, 9);
        assertThatThrownBy(() -> new DateRange(date, date.plusDays(DateRange.MAX_DAYS))).isInstanceOf(IllegalArgumentException.class).hasMessage("range cannot span more than " + DateRange.MAX_DAYS + " days");
    }

    @Test
    void nullCheckedBeforeOrdering() {
        assertThatThrownBy(() -> new DateRange(null, null)).isInstanceOf(NullPointerException.class).hasMessage("start cannot be null");
    }

    @Test
    void spanCheckedAcrossLeapDay() {
        DateRange leapYear = new DateRange(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1));
        DateRange commonYear = new DateRange(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 1));

        assertThat(leapYear.length()).isEqualTo(3);
        assertThat(commonYear.length()).isEqualTo(2);
    }

    @Test
    void singleHasLengthOne() {
        DateRange range = DateRange.single(LocalDate.of(2026, 8, 9));
        assertThat(range.length()).isEqualTo(1);
    }

    @Test
    void singleHasEqualEndpoints() {
        LocalDate date = LocalDate.of(2026, 8, 9);
        DateRange range = DateRange.single(date);

        assertThat(range.start()).isEqualTo(date);
        assertThat(range.end()).isEqualTo(date);
    }

    @Test
    void singleRejectsNull() {
        assertThatThrownBy(() -> DateRange.single(null)).isInstanceOf(NullPointerException.class).hasMessage("start cannot be null");
    }

    @Test
    void singleAgreesWithConstructor() {
        DateRange single = DateRange.single(LocalDate.of(2026, 8, 9));
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 9));

        assertThat(single).isEqualTo(range);
    }

    @Test
    void containsStart() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        assertThat(range.contains(LocalDate.of(2026, 8, 9))).isTrue();
    }

    @Test
    void containsEnd() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        assertThat(range.contains(LocalDate.of(2026, 8, 19))).isTrue();
    }

    @Test
    void containsInteriorDate() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        assertThat(range.contains(LocalDate.of(2026, 8, 14))).isTrue();
    }

    @Test
    void doesNotContainDayBeforeStart() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        assertThat(range.contains(LocalDate.of(2026, 8, 8))).isFalse();
    }

    @Test
    void doesNotContainDayAfterEnd() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        assertThat(range.contains(LocalDate.of(2026, 8, 20))).isFalse();
    }

    @Test
    void singleContainsOnlyItsOwnDate() {
        DateRange range = DateRange.single(LocalDate.of(2026, 8, 9));

        assertThat(range.contains(LocalDate.of(2026, 8, 9))).isTrue();
        assertThat(range.contains(LocalDate.of(2026, 8, 8))).isFalse();
        assertThat(range.contains(LocalDate.of(2026, 8, 10))).isFalse();
    }

    @Test
    void containsRejectsNull() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        assertThatThrownBy(() -> range.contains(null)).isInstanceOf(NullPointerException.class).hasMessage("date cannot be null");
    }

    @Test
    void datesIncludesEnd() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        assertThat(range.dates()).contains(LocalDate.of(2026, 8, 19));
    }

    @Test
    void datesIncludesStart() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        assertThat(range.dates()).contains(LocalDate.of(2026, 8, 9));
    }

    @Test
    void datesCountMatchesLength() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        assertThat(range.dates()).hasSize(range.length());
    }

    @Test
    void datesAreAscending() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 12));
        assertThat(range.dates()).containsExactly(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 12));
    }

    @Test
    void singleYieldsExactlyOneDate() {
        DateRange range = DateRange.single(LocalDate.of(2026, 8, 9));
        assertThat(range.dates()).containsExactly(LocalDate.of(2026, 8, 9));
    }

    @Test
    void datesCanBeStreamedTwice() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 12));

        assertThat(range.dates()).hasSize(4);
        assertThat(range.dates()).hasSize(4);
    }

    @Test
    void lengthOfSingleIsOne() {
        assertThat(DateRange.single(LocalDate.of(2026, 8, 9)).length()).isEqualTo(1);
    }

    @Test
    void lengthCountsBothEndpoints() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 10));
        assertThat(range.length()).isEqualTo(2);
    }

    @Test
    void lengthAtMaxDays() {
        LocalDate date = LocalDate.of(2026, 8, 9);
        DateRange range = new DateRange(date, date.plusDays(DateRange.MAX_DAYS - 1));

        assertThat(range.length()).isEqualTo(DateRange.MAX_DAYS);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 30, DateRange.MAX_DAYS})
    void lengthAgreesWithDatesCount(int days) {
        LocalDate date = LocalDate.of(2026, 8, 9);
        DateRange range = new DateRange(date, date.plusDays(days - 1));

        assertThat(range.dates()).hasSize(range.length());
        assertThat(range.length()).isEqualTo(days);
    }

    @Test
    void intersectionWithSelfIsEqual() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        assertThat(range.intersection(range)).contains(range);
    }

    @Test
    void intersectionOfPartialOverlapIsSharedSpan() {
        DateRange earlier = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        DateRange later = new DateRange(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 24));

        assertThat(earlier.intersection(later)).contains(new DateRange(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 19)));
    }

    @Test
    void intersectionIsSymmetric() {
        DateRange earlier = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        DateRange later = new DateRange(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 24));

        assertThat(earlier.intersection(later)).isEqualTo(later.intersection(earlier));
    }

    @Test
    void intersectionOfContainedRangeIsInnerRange() {
        DateRange outer = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        DateRange inner = new DateRange(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 14));

        assertThat(outer.intersection(inner)).contains(inner);
    }

    @Test
    void intersectionTouchingByOneDayIsSingleDay() {
        DateRange earlier = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        DateRange later = new DateRange(LocalDate.of(2026, 8, 19), LocalDate.of(2026, 8, 24));

        assertThat(earlier.intersection(later)).contains(DateRange.single(LocalDate.of(2026, 8, 19)));
    }

    @Test
    void intersectionOfAdjacentRangesIsEmpty() {
        DateRange earlier = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        DateRange later = new DateRange(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 24));

        assertThat(earlier.intersection(later)).isEmpty();
    }

    @Test
    void intersectionOfDisjointRangesIsEmpty() {
        DateRange earlier = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        DateRange later = new DateRange(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 19));

        assertThat(earlier.intersection(later)).isEmpty();
    }

    @Test
    void intersectionIsNeverWiderThanEitherInput() {
        DateRange earlier = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        DateRange later = new DateRange(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 24));
        DateRange shared = earlier.intersection(later).orElseThrow();

        assertThat(shared.length()).isLessThanOrEqualTo(earlier.length());
        assertThat(shared.length()).isLessThanOrEqualTo(later.length());
    }

    @Test
    void intersectionRejectsNull() {
        DateRange range = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        assertThatThrownBy(() -> range.intersection(null)).isInstanceOf(NullPointerException.class).hasMessage("other cannot be null");
    }

    @Test
    void equalRangesAreEqualAndHashAlike() {
        DateRange first = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        DateRange second = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void differentEndsAreNotEqual() {
        DateRange first = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 19));
        DateRange second = new DateRange(LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 20));

        assertThat(first).isNotEqualTo(second);
    }
}
