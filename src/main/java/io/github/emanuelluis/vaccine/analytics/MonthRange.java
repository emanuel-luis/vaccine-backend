package io.github.emanuelluis.vaccine.analytics;

import io.github.emanuelluis.vaccine.web.InvalidRequestException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/** An inclusive range of months, at most {@value #MAX_MONTHS} long, to keep responses bounded. */
public record MonthRange(YearMonth from, YearMonth to) {

    public static final int MAX_MONTHS = 36;
    /** Request parameter format: {@code YYYY-MM}. */
    public static final String PATTERN = "\\d{4}-(0[1-9]|1[0-2])";

    public MonthRange {
        if (from.isAfter(to)) {
            throw new InvalidRequestException("`from` (%s) must not be after `to` (%s)".formatted(from, to));
        }
        if (from.until(to, ChronoUnit.MONTHS) + 1 > MAX_MONTHS) {
            throw new InvalidRequestException("the range may cover at most %d months".formatted(MAX_MONTHS));
        }
    }

    public static MonthRange parse(String from, String to) {
        return new MonthRange(YearMonth.parse(from), YearMonth.parse(to));
    }

    public LocalDate firstDay() {
        return from.atDay(1);
    }

    public LocalDate endExclusive() {
        return to.plusMonths(1).atDay(1);
    }
}
