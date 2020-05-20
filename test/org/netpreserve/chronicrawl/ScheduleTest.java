package org.netpreserve.chronicrawl;

import org.junit.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class ScheduleTest {
    @Test
    public void testNextSetBigWrap() {
        assertEquals(1, Schedule.nextSetBitWrap(0b1101010, 0));
        assertEquals(1, Schedule.nextSetBitWrap(0b1101010, 1));
        assertEquals(3, Schedule.nextSetBitWrap(0b1101010, 2));
        assertEquals(1, Schedule.nextSetBitWrap(0b1101010, 8));
    }

    @Test
    public void testSchedule() {
        ZonedDateTime t = ZonedDateTime.of(2020, 8, 6, 12, 34, 21, 0, ZoneOffset.UTC);
        Schedule thursdays = new Schedule(0, "", 0, 0, 0, 1 << 3, 0);
        assertEquals(ZonedDateTime.parse("2020-08-13T12:34:21Z"), thursdays.apply(t));

        Schedule at9amAnd3pm = new Schedule(0, "", 0, 0, 0, 0, (1 << 9) | (1 << 15));
        assertEquals(ZonedDateTime.parse("2020-08-06T15:34:21Z"), at9amAnd3pm.apply(t));
        assertEquals(ZonedDateTime.parse("2020-08-07T09:34:21Z"), at9amAnd3pm.apply(at9amAnd3pm.apply(t)));

        Schedule at9amAnd3pmWednesday = new Schedule(0, "", 0, 0, 0, 1 << 2, (1 << 9) | (1 << 15));
        assertEquals(ZonedDateTime.parse("2020-08-12T15:34:21Z"), at9amAnd3pmWednesday.apply(t));

        Schedule at9amAnd3pmWedAndFriday = new Schedule(0, "", 0, 0, 0, 1 << 4 | 1 << 2, (1 << 9) | (1 << 15));
        ZonedDateTime v = at9amAnd3pmWedAndFriday.apply(t);
        assertEquals(ZonedDateTime.parse("2020-08-07T15:34:21Z"), v);
        v = at9amAnd3pmWedAndFriday.apply(v);
        assertEquals(ZonedDateTime.parse("2020-08-12T09:34:21Z"), v);
        v = at9amAnd3pmWedAndFriday.apply(v);
        assertEquals(ZonedDateTime.parse("2020-08-12T15:34:21Z"), v);
        v = at9amAnd3pmWedAndFriday.apply(v);
        assertEquals(ZonedDateTime.parse("2020-08-14T09:34:21Z"), v);
    }

    @Test
    public void testSummary() {
        var savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            Schedule at9amAnd3pmWedAndFriday = new Schedule(0, "", 0, 0, 0, 1 << 4 | 1 << 2, (1 << 9) | (1 << 15));
            assertEquals("on Wednesday, Friday at 9:00, 15:00", at9amAnd3pmWedAndFriday.summary());
        } finally {
            Locale.setDefault(savedLocale);
        }
    }

}