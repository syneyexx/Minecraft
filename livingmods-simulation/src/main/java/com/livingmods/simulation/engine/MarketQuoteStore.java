package com.livingmods.simulation.engine;

import com.livingmods.common.id.PlayerId;
import com.livingmods.common.id.SettlementId;
import com.livingmods.common.model.ResourceType;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived in-memory market quotes. Not persisted — expire on reload/reconnect.
 * Single-use: commit consumes the quote.
 */
public final class MarketQuoteStore {
    private static final long TTL_MS = 60_000L;
    private static final int MAX_QUOTES = 512;

    public record Quote(
            UUID quoteId,
            PlayerId playerId,
            SettlementId settlementId,
            ResourceType resource,
            int amount,
            boolean buy,
            int goldIngots,
            double unitPrice,
            long createdMs,
            long revision
    ) {
        public boolean expired(long now) {
            return now - createdMs > TTL_MS;
        }
    }

    private final ConcurrentHashMap<UUID, Quote> quotes = new ConcurrentHashMap<>();

    public Quote put(Quote quote) {
        if (quotes.size() >= MAX_QUOTES) {
            purgeExpired(System.currentTimeMillis());
        }
        if (quotes.size() >= MAX_QUOTES) {
            // Drop oldest
            Iterator<Map.Entry<UUID, Quote>> it = quotes.entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        quotes.put(quote.quoteId(), quote);
        return quote;
    }

    public Quote peek(UUID quoteId) {
        if (quoteId == null) return null;
        Quote q = quotes.get(quoteId);
        if (q == null) return null;
        if (q.expired(System.currentTimeMillis())) {
            quotes.remove(quoteId, q);
            return null;
        }
        return q;
    }

    /** Consume quote for commit — returns null if missing, expired, wrong player, or already used. */
    public Quote consume(UUID quoteId, PlayerId player) {
        Quote q = peek(quoteId);
        if (q == null) return null;
        if (player == null || !q.playerId().equals(player)) return null;
        if (!quotes.remove(quoteId, q)) return null;
        return q;
    }

    public void purgeExpired(long now) {
        quotes.entrySet().removeIf(e -> e.getValue().expired(now));
    }

    public void clear() {
        quotes.clear();
    }
}
