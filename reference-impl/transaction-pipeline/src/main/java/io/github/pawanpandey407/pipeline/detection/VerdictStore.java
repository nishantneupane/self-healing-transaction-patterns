package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * Bounded in-memory verdict log, newest first, mirrored to the app log.
 *
 * A persisting condition re-alarms only after the cooldown, not every
 * window; alert channels that repeat themselves every few seconds get
 * muted by the humans reading them, which defeats the point.
 */
@Component
public class VerdictStore {

    private static final Logger log = LoggerFactory.getLogger(VerdictStore.class);
    private static final int CAPACITY = 200;

    private final DetectionProperties props;
    private final ApplicationEventPublisher events;
    private final ConcurrentLinkedDeque<Verdict> verdicts = new ConcurrentLinkedDeque<>();
    private final ConcurrentMap<String, Long> lastEmittedMs = new ConcurrentHashMap<>();

    public VerdictStore(DetectionProperties props, ApplicationEventPublisher events) {
        this.props = props;
        this.events = events;
    }

    /** Returns true if the verdict was recorded, false if cooled down. */
    public boolean add(Verdict verdict) {
        return add(verdict, "");
    }

    /**
     * Records a verdict for one of several conditions a primitive reports
     * on the same subject, with a cooldown per condition. D4 reports zero
     * arrivals, a partial drop and a surge on the same pipeline; a path
     * that degrades and then dies must still record the zero verdict while
     * the drop verdict's cooldown runs.
     *
     * Returns true if the verdict was recorded, false if cooled down.
     */
    public boolean add(Verdict verdict, String condition) {
        String key = verdict.primitive() + ":" + verdict.subject() + ":" + condition;
        long now = System.currentTimeMillis();
        Long last = lastEmittedMs.get(key);
        if (last != null && now - last < props.getCooldownSeconds() * 1000) {
            return false;
        }
        lastEmittedMs.put(key, now);
        log.warn("VERDICT [{}] {} observed={} baseline={} : {}",
                verdict.primitive(), verdict.subject(),
                String.format("%.4f", verdict.observed()),
                String.format("%.4f", verdict.baselineMean()),
                verdict.message());
        verdicts.addFirst(verdict);
        while (verdicts.size() > CAPACITY) {
            verdicts.pollLast();
        }
        events.publishEvent(new VerdictRecorded(verdict));
        return true;
    }

    public List<Verdict> list() {
        return Collections.unmodifiableList(new ArrayList<>(verdicts));
    }
}
