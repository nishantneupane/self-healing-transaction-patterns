package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClientDivergencePrimitiveTest {

    private static final int HOUR = 12;
    private static final int PER_CLIENT = 12;
    private static final int THIN = 6;
    private static final List<String> CLIENTS = List.of("client-1", "client-2", "client-3", "client-4", "client-5");

    private final List<Verdict> published = new ArrayList<>();
    private BaselineStore baselines;
    private ClientDivergencePrimitive d1;

    @BeforeEach
    void setUp() {
        DetectionProperties props = new DetectionProperties();
        props.setWarmupWindows(3);
        props.setMinWindowSamples(10);
        VerdictStore store = new VerdictStore(props, event -> published.add(((VerdictRecorded) event).verdict()));
        baselines = new BaselineStore();
        d1 = new ClientDivergencePrimitive(baselines, props, store);
    }

    @Test
    void staysSilentDuringWarmup() {
        for (int i = 0; i < 3; i++) {
            assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();
        }
    }

    @Test
    void namesAClientThatDivergesInConsecutiveWindows() {
        warmUp();

        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();
        List<Verdict> verdicts = d1.observe(window(Map.of("client-3", PER_CLIENT)));

        assertThat(verdicts).singleElement().satisfies(v -> {
            assertThat(v.subject()).isEqualTo("client-3");
            assertThat(v.observed()).isEqualTo(1.0);
            assertThat(v.evidence().get("failingStages")).isEqualTo(Map.of("authorization", 12L));
            assertThat(v.evidence().get("consecutiveWindows")).isEqualTo(2);
        });
        assertThat(published).hasSize(1);
    }

    @Test
    void ignoresAOneWindowSpike() {
        warmUp();

        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();
        assertThat(d1.observe(window(Map.of()))).isEmpty();
        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();
    }

    @Test
    void staysSilentWhenTheWholeFleetDegradesTogether() {
        warmUp();
        Map<String, Integer> everyone = new HashMap<>();
        CLIENTS.forEach(c -> everyone.put(c, PER_CLIENT / 2));

        // A fleet-wide fault is not a client fault; isolating one client
        // would be the wrong response, so D1 must not name one.
        assertThat(d1.observe(window(everyone))).isEmpty();
        assertThat(d1.observe(window(everyone))).isEmpty();
    }

    @Test
    void ignoresASingleFailureInAWindow() {
        warmUp();

        assertThat(d1.observe(window(Map.of("client-3", 1)))).isEmpty();
        assertThat(d1.observe(window(Map.of("client-3", 1)))).isEmpty();
    }

    @Test
    void aBorderlineStartToAnIncidentIsNotLearned() {
        warmUp();
        BaselineStore.RateBaseline own = baselines.rate(ClientDivergencePrimitive.NAME, "client-3", HOUR);
        double failuresBefore = own.failures();

        // Too many failures to learn from, too few to call an incident.
        assertThat(d1.observe(window(Map.of("client-3", 2)))).isEmpty();
        assertThat(own.failures()).isEqualTo(failuresBefore);

        // The incident proper is still judged against the clean baseline.
        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT / 2)))).isEmpty();
        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT / 2)))).singleElement()
                .satisfies(v -> assertThat(v.subject()).isEqualTo("client-3"));
    }

    @Test
    void anIncidentThatStartsDuringWarmupIsNotLearned() {
        d1.observe(window(Map.of()));
        d1.observe(window(Map.of()));
        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();

        List<Verdict> verdicts = d1.observe(window(Map.of("client-3", PER_CLIENT)));

        assertThat(verdicts).singleElement().satisfies(v -> assertThat(v.subject()).isEqualTo("client-3"));
        BaselineStore.RateBaseline own = baselines.rate(ClientDivergencePrimitive.NAME, "client-3", HOUR);
        assertThat(own.failures()).isZero();
        assertThat(own.transactions()).isEqualTo(2.0 * PER_CLIENT);
    }

    @Test
    void keepsNamingAPersistingDivergenceButPublishesItOnce() {
        warmUp();

        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();
        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).hasSize(1);
        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).hasSize(1);
        // The condition persists, but the store's cooldown keeps it to one alert.
        assertThat(published).hasSize(1);
    }

    @Test
    void aClientAbsentFromAWindowLosesItsStreak() {
        warmUp();

        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();
        // No live traffic, as for a client that is isolated.
        for (int i = 0; i < 40; i++) {
            assertThat(d1.observe(window(Map.of(), Map.of("client-3", 0)))).isEmpty();
        }
        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();

        // The next divergent window is the second in a row, and the verdict says so.
        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).singleElement()
                .satisfies(v -> assertThat(v.evidence().get("consecutiveWindows")).isEqualTo(2));
    }

    @Test
    void aThinHealthyWindowBreaksTheStreak() {
        warmUp();

        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();
        assertThat(d1.observe(window(Map.of(), Map.of("client-3", THIN)))).isEmpty();
        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();
    }

    @Test
    void anOrdinaryThinWindowWithOneDeclineBreaksTheStreak() {
        warmUp();

        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();
        assertThat(d1.observe(window(Map.of("client-3", 1), Map.of("client-3", THIN)))).isEmpty();
        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();
    }

    @Test
    void aThinWindowThatIsFailingKeepsTheStreakButNamesNoOne() {
        warmUp();

        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).isEmpty();
        // Second divergent window in a row, but too thin to name a client on.
        assertThat(d1.observe(window(Map.of("client-3", THIN), Map.of("client-3", THIN)))).isEmpty();

        assertThat(d1.observe(window(Map.of("client-3", PER_CLIENT)))).singleElement()
                .satisfies(v -> assertThat(v.evidence().get("consecutiveWindows")).isEqualTo(3));
    }

    @Test
    void aThinWindowIsNeverLearned() {
        warmUp();
        BaselineStore.RateBaseline own = baselines.rate(ClientDivergencePrimitive.NAME, "client-3", HOUR);
        double transactionsBefore = own.transactions();

        d1.observe(window(Map.of(), Map.of("client-3", THIN)));

        assertThat(own.transactions()).isEqualTo(transactionsBefore);
    }

    private void warmUp() {
        for (int i = 0; i < 3; i++) {
            assertThat(d1.observe(window(Map.of()))).isEmpty();
        }
    }

    /** One window with PER_CLIENT transactions for every client; the map gives failures per client. */
    private static WindowSnapshot window(Map<String, Integer> failures) {
        return window(failures, Map.of());
    }

    /** As above, with some clients' transaction counts overridden; zero means absent from the window. */
    private static WindowSnapshot window(Map<String, Integer> failures, Map<String, Integer> totals) {
        Map<String, Long> success = new HashMap<>();
        Map<String, Long> failure = new HashMap<>();
        Map<String, Map<String, Long>> byClientStage = new HashMap<>();
        long processed = 0;
        long failed = 0;
        for (String client : CLIENTS) {
            int total = totals.getOrDefault(client, PER_CLIENT);
            int f = failures.getOrDefault(client, 0);
            if (total - f > 0) {
                success.put(client, (long) (total - f));
            }
            if (f > 0) {
                failure.put(client, (long) f);
                byClientStage.put(client, Map.of("authorization", (long) f));
            }
            processed += total;
            failed += f;
        }
        return new WindowSnapshot(HOUR, processed, processed, failed, success, failure,
                Map.of("authorization", failed), byClientStage, Map.of(), -1, 0.1);
    }
}
