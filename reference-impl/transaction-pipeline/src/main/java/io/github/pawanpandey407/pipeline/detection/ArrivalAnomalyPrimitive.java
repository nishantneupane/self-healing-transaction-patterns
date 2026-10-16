package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * D4: arrival anomaly.
 *
 * Three shapes, each judged against the learned arrival baseline:
 *
 * <ul>
 *   <li>Arrivals at zero while the baseline says traffic should be
 *   flowing. This is the Family 1.3 signature, the application healthy
 *   while the path is dead, and it fires at once. It stays a rule of its
 *   own rather than the bottom of the band: on a noisy baseline,
 *   mean - sigma * stddev is below zero and a band rule never sees a
 *   dead path.</li>
 *   <li>A partial drop: arrivals below the baseline band, by sigma and
 *   by the relative floor, for consecutive windows. Low windows are never
 *   learned, so a drop that persists keeps firing instead of becoming the
 *   new normal. The verdict is worded neutrally because the generator is
 *   closed-loop: a slow stage cuts arrivals, and that is back-pressure,
 *   not a dead path.</li>
 *   <li>Arrivals surging past the baseline band.</li>
 * </ul>
 *
 * Retry-vs-unique divergence from the spec waits until the pipeline can
 * generate retry traffic.
 *
 * It counts arrivals, not processing. A held arrival still arrived, and
 * a replay burst after a release is old traffic, not new; counting
 * processing instead made every release replay read as a surge.
 */
@Component
@Order(4)
public class ArrivalAnomalyPrimitive implements DetectionPrimitive {

    // Verdict cooldowns run per condition, so a drop verdict cannot mute
    // the zero verdict of a path that degrades and then dies.
    private static final String ZERO = "zero";
    private static final String DROP = "drop";
    private static final String SURGE = "surge";

    private final BaselineStore baselines;
    private final DetectionProperties props;
    private final VerdictStore verdicts;

    private int lowStreak;

    public ArrivalAnomalyPrimitive(BaselineStore baselines, DetectionProperties props, VerdictStore verdicts) {
        this.baselines = baselines;
        this.props = props;
        this.verdicts = verdicts;
    }

    @Override
    public String name() {
        return "D4-arrival-anomaly";
    }

    @Override
    public List<Verdict> observe(WindowSnapshot w) {
        List<Verdict> out = new ArrayList<>();
        BaselineStore.RollingBaseline b = baselines.get(name(), "pipeline", w.hourBucket());
        double arrivals = w.arrived();
        boolean low = false;

        if (b.samples() >= props.getWarmupWindows()) {
            boolean expectsTraffic = b.mean() >= props.getMinWindowSamples();
            boolean dead = arrivals == 0 && expectsTraffic;
            // Zero is the deepest drop, so it extends the streak whatever
            // the band says.
            low = dead || (expectsTraffic
                    && arrivals < b.mean() - props.getSigma() * b.stddev()
                    && arrivals < b.mean() * (1 - props.getRelativeFloor()));
            lowStreak = low ? lowStreak + 1 : 0;

            if (dead) {
                emit(out, ZERO, new Verdict(Instant.now(), name(), "pipeline", arrivals, b.mean(), b.stddev(),
                        "arrivals dropped to zero while baseline expects traffic, traffic-path failure signature",
                        Map.of("expectedPerWindow", b.mean())));
            } else if (low && lowStreak >= props.getConfirmWindows()) {
                emit(out, DROP, new Verdict(Instant.now(), name(), "pipeline", arrivals, b.mean(), b.stddev(),
                        "arrival rate dropped below its baseline band for consecutive windows",
                        Map.of("expectedPerWindow", b.mean(), "consecutiveWindows", lowStreak)));
            } else if (arrivals > b.mean() + props.getSigma() * b.stddev()
                    && arrivals > b.mean() * (1 + props.getRelativeFloor())) {
                emit(out, SURGE, new Verdict(Instant.now(), name(), "pipeline", arrivals, b.mean(), b.stddev(),
                        "arrival rate surged beyond its baseline band",
                        Map.of("windowArrivals", w.arrived())));
            }
        } else {
            // A window that is not judged breaks the streak; consecutive
            // means consecutive.
            lowStreak = 0;
        }
        // Empty windows (startup, or the path already dead), triggered
        // windows, and low windows still waiting for confirmation are
        // excluded from learning; a baseline dragged toward zero by
        // startup windows makes normal traffic look like a surge, and one
        // dragged down by a drop makes the drop look normal.
        if (out.isEmpty() && !low && arrivals > 0) {
            b.update(arrivals);
        }
        return out;
    }

    private void emit(List<Verdict> out, String condition, Verdict verdict) {
        out.add(verdict);
        verdicts.add(verdict, condition);
    }
}
