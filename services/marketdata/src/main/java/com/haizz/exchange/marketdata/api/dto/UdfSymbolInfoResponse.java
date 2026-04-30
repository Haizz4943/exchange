package com.haizz.exchange.marketdata.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.marketdata.domain.PairMetadata;

import java.util.List;

public record UdfSymbolInfoResponse(
        String symbol,
        String name,
        String description,
        String type,
        String session,
        String timezone,
        String exchange,
        int minmov,
        int pricescale,
        @JsonProperty("has_intraday") boolean hasIntraday,
        @JsonProperty("has_no_volume") boolean hasNoVolume,
        @JsonProperty("visible_plots_set") String visiblePlotsSet,
        @JsonProperty("supported_resolutions") List<String> supportedResolutions
) {
    private static final List<String> RESOLUTIONS = List.of("1", "5", "15", "60", "240", "1D");

    public static UdfSymbolInfoResponse from(PairMetadata meta) {
        String sym = meta.symbol().value();
        String name = meta.baseAsset() + "/" + meta.quoteAsset();
        String desc = meta.baseAsset() + " / " + meta.quoteAsset();
        return new UdfSymbolInfoResponse(
                sym, name, desc, "crypto", "24x7", "Etc/UTC",
                "binance-sim", 1, meta.pricescale(),
                true, false, "ohlcv", RESOLUTIONS
        );
    }
}
