package com.haizz.exchange.marketdata.shared;

import com.haizz.exchange.marketdata.domain.Interval;
import com.haizz.exchange.marketdata.domain.exception.InvalidResolutionException;

public final class ResolutionMapper {

    private ResolutionMapper() {}

    public static Interval toInterval(String tvResolution) {
        if (!Interval.isValidTvResolution(tvResolution)) {
            throw new InvalidResolutionException(tvResolution);
        }
        return Interval.fromTvResolution(tvResolution);
    }

    public static String toTvResolution(Interval interval) {
        return interval.getTvResolution();
    }
}
