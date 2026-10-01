package com.example.wallet.cache;

import com.example.wallet.config.WalletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis cache for balances. The database is ALWAYS the source of truth; Redis only makes reads fast.
 *
 * Value format: "version:balanceKobo:currency".
 * Writes go through a Lua script that refuses to overwrite a newer version with an older one, so two
 * commits finishing out of order (or a slow cache-miss refill) can never leave a stale balance behind.
 */
@Component
public class BalanceCache {

    private static final Logger log = LoggerFactory.getLogger(BalanceCache.class);

    private static final String SET_IF_NEWER_LUA = """
            local cur = redis.call('GET', KEYS[1])
            if cur then
              local v = tonumber(string.match(cur, '^(%d+):'))
              if v and v >= tonumber(ARGV[1]) then return 0 end
            end
            redis.call('SET', KEYS[1], ARGV[1] .. ':' .. ARGV[2] .. ':' .. ARGV[3], 'EX', ARGV[4])
            return 1
            """;

    private static final DefaultRedisScript<Long> SET_IF_NEWER = new DefaultRedisScript<>(SET_IF_NEWER_LUA, Long.class);

    public record Snapshot(UUID accountId, long version, long balance, String currency) {}

    private final StringRedisTemplate redis;
    private final WalletProperties props;

    public BalanceCache(StringRedisTemplate redis, WalletProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public Optional<Snapshot> get(UUID accountId) {
        try {
            String raw = redis.opsForValue().get(key(accountId));
            if (raw == null) return Optional.empty();
            String[] p = raw.split(":", 3);
            return Optional.of(new Snapshot(accountId, Long.parseLong(p[0]), Long.parseLong(p[1]), p[2]));
        } catch (RuntimeException e) {
            // Redis down/slow/garbled: degrade to the database, never fail the request.
            log.warn("Balance cache read failed for {}: {}", accountId, e.toString());
            return Optional.empty();
        }
    }

    public void put(Snapshot s) {
        try {
            redis.execute(SET_IF_NEWER, List.of(key(s.accountId())),
                    String.valueOf(s.version()), String.valueOf(s.balance()), s.currency(),
                    String.valueOf(props.cache().ttl().toSeconds()));
        } catch (RuntimeException e) {
            // The TTL heals a missed write. Correctness never depends on this call succeeding.
            log.warn("Balance cache write failed for {}: {}", s.accountId(), e.toString());
        }
    }

    private String key(UUID accountId) {
        return props.cache().keyPrefix() + accountId;
    }
}
