package com.haizz.exchange.marketdata.shared;

import com.haizz.exchange.marketdata.config.MarketProperties;
import com.haizz.exchange.marketdata.domain.Interval;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SupportedPairs {

    private final Set<PairSymbol> pairs;
    private final Set<Interval> intervals;

    public SupportedPairs(MarketProperties properties) {
        this.pairs = properties.getPairs().stream()
                .map(PairSymbol::of)
                .collect(Collectors.toUnmodifiableSet());
        this.intervals = properties.getIntervals().stream()
                .map(Interval::fromValue)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<PairSymbol> getPairs() { return pairs; }
    public Set<Interval> getIntervals() { return intervals; }
    public List<String> getPairValues() { return pairs.stream().map(PairSymbol::value).toList(); }

    public boolean supports(String pairSymbol) {
        return pairs.contains(PairSymbol.of(pairSymbol));
    }

    public boolean supports(PairSymbol pair) {
        return pairs.contains(pair);
    }
}
