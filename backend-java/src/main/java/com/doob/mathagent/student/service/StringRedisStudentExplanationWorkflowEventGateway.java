package com.doob.mathagent.student.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Lua-backed Redis buffer for the student-explanation SSE write-behind path.
 *
 * <p>Why Lua: an append must (1) keep the shared id sequence above the highest id the JVM has ever issued — the
 * sequence can be reseeded after a Redis restart or a direct-write degradation window — (2) allocate the id,
 * (3) push the entry and (4) refresh the run list TTL as one atomic step. Separate round-trips could interleave a
 * reseed between the INCR and the RPUSH and produce out-of-order ids inside a single run list, which is exactly what
 * the Last-Event-ID replay contract forbids. One EVAL per append keeps the request path at a single Redis
 * round-trip and touches zero MySQL connections.</p>
 *
 * <p>Entry wire format is {@code eventId \n eventName \n eventJson}: a stable prefix the flush/read paths can split
 * without parsing JSON (Jackson's compact output never emits raw newlines inside the serialized text).</p>
 */
@Component
@ConditionalOnProperty(prefix = "math-agent.redis.student-explanation-write-behind", name = "enabled", havingValue = "true")
public class StringRedisStudentExplanationWorkflowEventGateway implements StudentExplanationWorkflowEventRedisGateway {

    /**
     * KEYS: seq, run list, active-run set. ARGV: seed, "name\njson", ttl millis, per-run cap, run id.
     * Returns {eventId, listLength}; eventId is -1 when the per-run buffer is at capacity.
     */
    private static final DefaultRedisScript<List> APPEND_SCRIPT = listScript("""
            if redis.call('LLEN', KEYS[2]) >= tonumber(ARGV[4]) then
              return {-1, redis.call('LLEN', KEYS[2])}
            end
            local seed = tonumber(ARGV[1])
            local cur = redis.call('GET', KEYS[1])
            if (not cur) or tonumber(cur) < seed then
              redis.call('SET', KEYS[1], seed)
            end
            local id = redis.call('INCR', KEYS[1])
            redis.call('RPUSH', KEYS[2], id .. '\\n' .. ARGV[2])
            redis.call('PEXPIRE', KEYS[2], tonumber(ARGV[3]))
            redis.call('SADD', KEYS[3], ARGV[5])
            return {id, redis.call('LLEN', KEYS[2])}
            """);

    /** KEYS: run list, active-run set. ARGV: flushed count, run id. Returns the remaining list length. */
    private static final DefaultRedisScript<Long> CONFIRM_SCRIPT = scalarScript("""
            redis.call('LTRIM', KEYS[1], tonumber(ARGV[1]), -1)
            local left = redis.call('LLEN', KEYS[1])
            if left == 0 then
              redis.call('DEL', KEYS[1])
              redis.call('SREM', KEYS[2], ARGV[2])
            end
            return left
            """);

    private final StringRedisTemplate redisTemplate;
    private final StudentExplanationWriteBehindProperties properties;

    public StringRedisStudentExplanationWorkflowEventGateway(
            StringRedisTemplate redisTemplate,
            StudentExplanationWriteBehindProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public long append(String runId, String eventName, String eventJson, long seed) {
        List<Long> result = redisTemplate.execute(APPEND_SCRIPT,
                List.of(seqKey(), listKey(runId), runsKey()),
                String.valueOf(Math.max(0L, seed)),
                eventName + "\n" + eventJson,
                String.valueOf(properties.normalizedBufferTtl().toMillis()),
                String.valueOf(properties.normalizedMaxBufferedEvents()),
                runId);
        if (result == null || result.isEmpty() || !(result.get(0) instanceof Number)) {
            // A nil/garbage script reply means the entry may or may not be buffered; treat it as a Redis failure so
            // the caller writes through instead of silently inventing an id.
            throw new IllegalStateException("Student explanation write-behind append script returned no id");
        }
        return ((Number) result.get(0)).longValue();
    }

    @Override
    public List<BufferedEvent> readAfter(String runId, long afterEventId, int limit) {
        int window = Math.max(1, limit) * 3;
        List<BufferedEvent> fetched = readRange(runId, 0, window - 1L);
        if (fetched.size() >= window) {
            // The window only covered the oldest slice; with a bigger backlog the requested cursor may sit further
            // right, so widen exactly once to the bounded per-run ceiling instead of guessing an offset.
            fetched = readRange(runId, 0, properties.normalizedMaxBufferedEvents());
        }
        List<BufferedEvent> visible = new ArrayList<>();
        int boundedLimit = Math.max(1, limit);
        for (BufferedEvent item : fetched) {
            if (item.eventId() > afterEventId) {
                visible.add(item);
                if (visible.size() >= boundedLimit) {
                    break;
                }
            }
        }
        return visible;
    }

    @Override
    public List<BufferedEvent> peekOldest(String runId, int limit) {
        return readRange(runId, 0, Math.max(1, limit) - 1L);
    }

    @Override
    public long confirmFlushed(String runId, int flushedCount) {
        Long left = redisTemplate.execute(CONFIRM_SCRIPT,
                List.of(listKey(runId), runsKey()),
                String.valueOf(Math.max(0, flushedCount)), runId);
        return left == null ? -1L : left;
    }

    @Override
    public Set<String> activeRuns() {
        Set<String> members = redisTemplate.opsForSet().members(runsKey());
        return members == null ? Set.of() : members;
    }

    private List<BufferedEvent> readRange(String runId, long start, long stop) {
        List<String> raw = redisTemplate.opsForList().range(listKey(runId), start, stop);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<BufferedEvent> events = new ArrayList<>(raw.size());
        for (String entry : raw) {
            events.add(parse(entry));
        }
        return events;
    }

    /** Splits {@code id\nname\njson}; the JSON tail is taken verbatim because compact Jackson output has no raw newlines. */
    private static BufferedEvent parse(String entry) {
        int idEnd = entry.indexOf('\n');
        int nameEnd = idEnd < 0 ? -1 : entry.indexOf('\n', idEnd + 1);
        if (idEnd <= 0 || nameEnd < 0 || nameEnd == entry.length() - 1) {
            throw new IllegalStateException("Student explanation write-behind buffer entry is malformed");
        }
        try {
            return new BufferedEvent(Long.parseLong(entry.substring(0, idEnd)),
                    entry.substring(idEnd + 1, nameEnd),
                    entry.substring(nameEnd + 1));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Student explanation write-behind buffer entry id is invalid", exception);
        }
    }

    private String seqKey() {
        return properties.normalizedKeyPrefix() + ":seq";
    }

    /** runId is a backend-issued UUID (see createOrLoad), so it is appended verbatim: no hashing needed. */
    private String listKey(String runId) {
        return properties.normalizedKeyPrefix() + ":run:" + runId + ":events";
    }

    private String runsKey() {
        return properties.normalizedKeyPrefix() + ":runs";
    }

    private static DefaultRedisScript<List> listScript(String body) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(body);
        script.setResultType(List.class);
        return script;
    }

    private static DefaultRedisScript<Long> scalarScript(String body) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(body);
        script.setResultType(Long.class);
        return script;
    }
}
