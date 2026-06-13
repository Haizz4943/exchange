package com.haizz.exchange.gateway.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages channel subscriptions: channel → Set<connectionId> and connectionId → Set<channel>.
 *
 * Valid channel formats (whitelist):
 *   market:<PAIR>:depth
 *   market:<PAIR>:kline:<interval>
 *   market:<PAIR>:trades
 *   orders
 *   wallet
 */
@Slf4j
@Component
public class SubscriptionManager {

    // channel → Set<connectionId>
    private final ConcurrentHashMap<String, Set<String>> channelSubscribers = new ConcurrentHashMap<>();
    // connectionId → Set<channel>
    private final ConcurrentHashMap<String, Set<String>> connectionChannels = new ConcurrentHashMap<>();

    /**
     * Returns true if the channel name is valid.
     * Whitelist: market:<pair>:<type> or orders or wallet.
     */
    public boolean isValidChannel(String channel) {
        if (channel == null || channel.isBlank()) return false;
        if ("orders".equals(channel) || "wallet".equals(channel)) return true;
        // market:<PAIR>:depth, market:<PAIR>:kline:<interval>, market:<PAIR>:trades
        if (channel.startsWith("market:")) {
            String[] parts = channel.split(":");
            return parts.length >= 3;
        }
        return false;
    }

    public void subscribe(String connectionId, List<String> channels) {
        for (String ch : channels) {
            channelSubscribers.computeIfAbsent(ch, k -> ConcurrentHashMap.newKeySet()).add(connectionId);
            connectionChannels.computeIfAbsent(connectionId, k -> ConcurrentHashMap.newKeySet()).add(ch);
        }
        log.debug("Subscribed connId={} to channels={}", connectionId, channels);
    }

    public void unsubscribe(String connectionId, List<String> channels) {
        for (String ch : channels) {
            Set<String> subs = channelSubscribers.get(ch);
            if (subs != null) {
                subs.remove(connectionId);
                if (subs.isEmpty()) channelSubscribers.remove(ch);
            }
            Set<String> chans = connectionChannels.get(connectionId);
            if (chans != null) chans.remove(ch);
        }
    }

    public void unsubscribeAll(String connectionId) {
        Set<String> channels = connectionChannels.remove(connectionId);
        if (channels != null) {
            for (String ch : channels) {
                Set<String> subs = channelSubscribers.get(ch);
                if (subs != null) {
                    subs.remove(connectionId);
                    if (subs.isEmpty()) channelSubscribers.remove(ch);
                }
            }
        }
    }

    public Set<String> subscribersOf(String channel) {
        return channelSubscribers.getOrDefault(channel, Set.of());
    }

    public int totalCount() {
        return connectionChannels.values().stream().mapToInt(Set::size).sum();
    }
}
