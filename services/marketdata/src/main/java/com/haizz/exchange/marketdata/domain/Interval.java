package com.haizz.exchange.marketdata.domain;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum Interval {
    ONE_MINUTE("1m", Duration.ofMinutes(1), "1"),
    FIVE_MINUTES("5m", Duration.ofMinutes(5), "5"),
    FIFTEEN_MINUTES("15m", Duration.ofMinutes(15), "15"),
    ONE_HOUR("1h", Duration.ofHours(1), "60"),
    FOUR_HOURS("4h", Duration.ofHours(4), "240"),
    ONE_DAY("1d", Duration.ofDays(1), "1D");

    private final String value;
    private final Duration duration;
    private final String tvResolution;

    private static final Map<String, Interval> BY_VALUE =
            Arrays.stream(values()).collect(Collectors.toMap(i -> i.value, Function.identity()));

    private static final Map<String, Interval> BY_TV_RESOLUTION =
            Arrays.stream(values()).collect(Collectors.toMap(i -> i.tvResolution, Function.identity()));

    Interval(String value, Duration duration, String tvResolution) {
        this.value = value;
        this.duration = duration;
        this.tvResolution = tvResolution;
    }

    public String getValue() { return value; }
    public Duration getDuration() { return duration; }
    public String getTvResolution() { return tvResolution; }

    public static Interval fromValue(String value) {
        Interval interval = BY_VALUE.get(value);
        if (interval == null) throw new IllegalArgumentException("Unknown interval: " + value);
        return interval;
    }

    public static Interval fromTvResolution(String resolution) {
        Interval interval = BY_TV_RESOLUTION.get(resolution);
        if (interval == null) throw new IllegalArgumentException("Unknown TV resolution: " + resolution);
        return interval;
    }

    public static boolean isValidTvResolution(String resolution) {
        return BY_TV_RESOLUTION.containsKey(resolution);
    }
}
