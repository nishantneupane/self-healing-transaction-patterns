package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4 reads arrivals, not processing. The two diverge whenever recovery is
 * active: held arrivals are traffic that did not get processed, and a
 * replay is processing of traffic that arrived earlier.
 */
class ArrivalAnomalyPrimitiveTest {

    private static final String ZERO = "arrivals dropped to zero while baseline expects traffic, "
            + "traffic-path failure signature";
    private static final String DROP = "arrival rate dropped below its baseline band for consecutive windows";

    private final List<Verdict> published = new ArrayList<>();
    private DetectionProperties props;
    private BaselineStore baselines;
    private VerdictStore verdicts;
    private ArrivalAnomalyPrimitive d4;

    @BeforeEach
    void setUp() {
        props = new DetectionProperties();
        props.setWarmupWindows(3);
        verdicts = new VerdictStore(props, event -> published.add(((VerdictRecorded) event).verdict()));
        baselines = new BaselineStore();
        d4 = new ArrivalAnomalyPrimitive(baselines, props, verdicts);
        for (int i = 0; i < 4; i++) {
            d4.observe(window(100, 100));
        }
    }

    @Test
    void heldArrivalsAreStillTrafficNotADeadPath() {
        // Everything that arrived was held, so nothing was processed.
        List<Verdict> out = d4.observe(window(100, 0));

        assertThat(out).isEmpty();
    }

    @Test
    void aReplayWithNoNewArrivalsIsStillADeadPath() {
        // Nothing new arrived; the only processing was replaying old traffic.
        List<Verdict> out = d4.observe(window(0, 140));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).observed()).isZero();
        assertThat(out.get(0).message()).contains("dropped to zero");
    }

    @Test
    void zeroArrivalsFireAtOnceOnANoisyBaseline() {
        // A baseline swinging between 40 and 160 has a stddev of about 69
        // against a mean of 100, so mean - sigma * stddev is far below zero.
        // A band rule could never see this path die; the zero rule must.
        ArrivalAnomalyPrimitive noisy = new ArrivalAnomalyPrimitive(new BaselineStore(), props, verdicts);
        for (int i = 0; i < 4; i++) {
            noisy.observe(window(i % 2 == 0 ? 40 : 160, 100));
        }

        assertThat(noisy.observe(window(0, 0))).singleElement()
                .satisfies(v -> assertThat(v.message()).isEqualTo(ZERO));
    }

    @Test
    void aPartialDropFiresOnceItIsConfirmed() {
        assertThat(d4.observe(window(20, 20))).isEmpty();

        assertThat(d4.observe(window(20, 20))).singleElement().satisfies(v -> {
            assertThat(v.message()).isEqualTo(DROP);
            assertThat(v.observed()).isEqualTo(20.0);
            assertThat(v.evidence().get("consecutiveWindows")).isEqualTo(2);
        });
    }

    @Test
    void oneLowWindowIsNotADrop() {
        assertThat(d4.observe(window(20, 20))).isEmpty();
        assertThat(d4.observe(window(100, 100))).isEmpty();
        assertThat(d4.observe(window(20, 20))).isEmpty();
    }

    @Test
    void aWindowTheRuleCannotJudgeBreaksTheStreak() {
        assertThat(d4.observe(window(20, 20))).isEmpty();
        // Hour 11 has no learned baseline yet, so this window is not judged.
        assertThat(d4.observe(window(11, 20, 20))).isEmpty();

        assertThat(d4.observe(window(20, 20))).isEmpty();
    }

    @Test
    void aDropIsNeverLearnedSoItKeepsFiring() {
        BaselineStore.RollingBaseline b = baselines.get(d4.name(), "pipeline", 10);
        long learned = b.samples();

        // The first low window waits for confirmation; it must not be
        // learned either, or a drop shifts the baseline it is judged against.
        assertThat(d4.observe(window(20, 20))).isEmpty();
        assertThat(b.samples()).isEqualTo(learned);

        for (int i = 0; i < 10; i++) {
            assertThat(d4.observe(window(20, 20))).hasSize(1);
        }
        assertThat(b.samples()).isEqualTo(learned);
        assertThat(b.mean()).isEqualTo(100.0);
    }

    @Test
    void aPathThatDegradesThenDiesStillRecordsTheZeroVerdict() {
        d4.observe(window(20, 20));
        d4.observe(window(20, 20));
        // The drop verdict's cooldown is running; the zero verdict must
        // still be recorded.
        d4.observe(window(0, 0));

        assertThat(published).extracting(Verdict::message).containsExactly(DROP, ZERO);
    }

    private static WindowSnapshot window(long arrived, long processed) {
        return window(10, arrived, processed);
    }

    private static WindowSnapshot window(int hour, long arrived, long processed) {
        return new WindowSnapshot(hour, arrived, processed, 0, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), -1, 0.1);
    }
}
