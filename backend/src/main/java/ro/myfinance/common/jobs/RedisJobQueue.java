package ro.myfinance.common.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis list-backed queue (one list per {@link Job.JobType}). The worker pops with a blocking
 * read. This is the foundation stub — payloads are pushed; consumers are wired in MOD-04/05/07.
 *
 * <p>TODO(MOD-04): exponential backoff + DLQ + visibility timeout. Consider Postgres {@code pgmq}
 * as the alternative the design allows.
 */
@Component
public class RedisJobQueue implements JobQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisJobQueue.class);
    static final String KEY_PREFIX = "myfinance:jobs:";

    private final StringRedisTemplate redis;

    public RedisJobQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void enqueue(Job job) {
        String key = KEY_PREFIX + job.type().name();
        // payload encoding kept trivial for the scaffold: idempotencyKey|payloadJson
        String message = job.idempotencyKey() + "|" + (job.payloadJson() == null ? "" : job.payloadJson());
        redis.opsForList().leftPush(key, message);
        log.debug("Enqueued {} (key={})", job.type(), job.idempotencyKey());
    }
}
