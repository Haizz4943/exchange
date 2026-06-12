package com.haizz.exchange.marketdata.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.haizz.exchange.marketdata.domain.Candlestick;

import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UdfHistoryResponse(
        String s,
        long[] t,
        String[] o,
        String[] h,
        String[] l,
        String[] c,
        String[] v,
        Long nextTime,
        String errmsg
) {
    public static UdfHistoryResponse ok(List<Candlestick> bars) {
        int n = bars.size();
        long[] t = new long[n];
        String[] o = new String[n], h = new String[n], l = new String[n],
                c = new String[n], v = new String[n];
        for (int i = 0; i < n; i++) {
            var bar = bars.get(i);
            t[i] = bar.openTime().getEpochSecond();
            o[i] = plain(bar.open());
            h[i] = plain(bar.high());
            l[i] = plain(bar.low());
            c[i] = plain(bar.close());
            v[i] = plain(bar.volume());
        }
        return new UdfHistoryResponse("ok", t, o, h, l, c, v, null, null);
    }

    public static UdfHistoryResponse noData(Long nextTimeSecs) {
        return new UdfHistoryResponse("no_data", null, null, null, null, null, null, nextTimeSecs, null);
    }

    public static UdfHistoryResponse error(String msg) {
        return new UdfHistoryResponse("error", null, null, null, null, null, null, null, msg);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
