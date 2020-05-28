package org.netpreserve.chronicrawl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public class Schedule implements Comparable<Schedule> {
    private static final ZonedDateTime COMPARISON_TIME = ZonedDateTime.parse("2018-01-01T09:00:00Z");

    public final long id;
    public final String name;
    public final int years;
    public final int months;
    public final int days;
    private final int dayOfWeekBits;
    private final int hourOfDayBits;

    public Schedule(long id, String name, int years, int months, int days, int daysOfWeek, int hoursOfDay) {
        this.id = id;
        this.name = name;
        this.years = years;
        this.months = months;
        this.days = days;
        this.dayOfWeekBits = daysOfWeek;
        this.hourOfDayBits = hoursOfDay;
    }

    ZonedDateTime apply(ZonedDateTime prev) {
        var next = prev.plusYears(years).plusMonths(months).plusDays(days);

        // figure out the next hour slot we're eligible for
        if (hourOfDayBits != 0) {
            int hour = nextSetBitWrap(hourOfDayBits, next.getHour() + 1);
            if (hour <= next.getHour()) next = next.plusDays(1);
            next = next.withHour(hour);
        }

        // now find the next day of the week slot
        if (dayOfWeekBits != 0) {
            if (hourOfDayBits == 0) next = next.plusDays(1);
            int day = nextSetBitWrap(dayOfWeekBits, next.getDayOfWeek().getValue() - 1);
            next = next.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day + 1)));
        }

        return next;
    }

    static int nextSetBitWrap(int bits, int start) {
        int i = nextSetBit(bits, start);
        return i >= Integer.SIZE ? nextSetBit(bits, 0) : i;
    }

    private static int nextSetBit(int bits, int start) {
        return Integer.numberOfTrailingZeros(bits & ((-1) << start));
    }

    @Override
    public int compareTo(Schedule o) {
        return apply(COMPARISON_TIME).compareTo(o.apply(COMPARISON_TIME));
    }

    public List<DayOfWeek> daysOfWeek() {
        List<DayOfWeek> list = new ArrayList<>();
        for (int i = nextSetBit(dayOfWeekBits, 0); i < Integer.SIZE; i = nextSetBit(dayOfWeekBits, i + 1)) {
            list.add(DayOfWeek.of(i + 1));
        }
        return list;
    }

    public List<Integer> hoursOfDay() {
        List<Integer> list = new ArrayList<>();
        for (int i = nextSetBit(hourOfDayBits, 0); i < Integer.SIZE; i = nextSetBit(hourOfDayBits, i + 1)) {
            list.add(i);
        }
        return list;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        var periodList = new ArrayList<String>();
        if (years != 0) periodList.add(years == 1 && months == 0 && days == 0 ? "year" : years + " years");
        if (months != 0) periodList.add(months == 1 && years == 0 && days == 0 ? "month" : months + " months");
        if (days != 0) periodList.add(days == 1 && years == 0 && months == 0 ? "day" : days + " days");
        if (!periodList.isEmpty()) {
           sb.append("every ");
           sb.append(String.join(", ", periodList));
        }
        if (dayOfWeekBits != 0) {
            sb.append(" on ");
            sb.append(daysOfWeek().stream().map(day -> day.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                    .collect(joining(", ")));
        }
        if (hourOfDayBits != 0) {
            sb.append(" at ");
            sb.append(hoursOfDay().stream().map(Schedule::prettyHour).collect(joining(", ")));
        }
        return sb.toString().trim();
    }

    public boolean hasDayOfWeek(int dayIndex) {
        return (dayOfWeekBits & (1 << dayIndex)) != 0;
    }

    public boolean hasHourOfDay(int hour) {
        return (hourOfDayBits & (1 << hour)) != 0;
    }

    static String prettyHour(int hour) {
        if (hour == 0) {
            return "midnight";
        } else if (hour == 12) {
            return "noon";
        } else if (hour < 12) {
            return hour + "am";
        } else {
            return (hour - 12) + "pm";
        }
    }

    static List<String> hourNames() {
        return IntStream.range(0, 24).mapToObj(Schedule::prettyHour).collect(toList());
    }

    static List<String> dayNames() {
        return Arrays.stream(DayOfWeek.values()).map(day -> day.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                .collect(toList());
    }

    public Instant apply(Instant prev) {
        return apply(prev.atZone(ZoneId.systemDefault())).toInstant();
    }
}
