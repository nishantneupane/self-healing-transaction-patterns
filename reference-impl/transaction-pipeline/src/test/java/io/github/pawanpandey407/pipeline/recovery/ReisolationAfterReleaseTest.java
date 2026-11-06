package io.github.pawanpandey407.pipeline.recovery;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import io.github.pawanpandey407.pipeline.config.PipelineProperties;
import io.github.pawanpandey407.pipeline.config.RecoveryProperties;
import io.github.pawanpandey407.pipeline.core.TransactionPipeline;
import io.github.pawanpandey407.pipeline.detection.BaselineStore;
import io.github.pawanpandey407.pipeline.detection.ClientDivergencePrimitive;
import io.github.pawanpandey407.pipeline.detection.DetectionEngine;
import io.github.pawanpandey407.pipeline.detection.VerdictRecorded;
import io.github.pawanpandey407.pipeline.detection.VerdictStore;
import io.github.pawanpandey407.pipeline.metrics.PipelineMetrics;
import io.github.pawanpandey407.pipeline.model.Transaction;
import io.github.pawanpandey407.pipeline.stage.AuthorizationStage;
import io.github.pawanpandey407.pipeline.stage.SettlementStage;
import io.github.pawanpandey407.pipeline.stage.ValidationStage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An isolated client has no live traffic, so it cannot carry a confirm
 * streak through its quarantine. Once released it is held to the same
 * rule as every other client: one bad window is not an incident.
 */
class ReisolationAfterReleaseTest {

    private static final int PER_CLIENT = 12;
    private static final List<String> CLIENTS = List.of("client-1", "client-2", "client-3", "client-4", "client-5");

    private PipelineProperties.Stage authorization;
    private ClientQuarantine quarantine;
    private TransactionPipeline pipeline;
    private ClientIsolationAction r2;
    private RecoveryEngine recovery;
    private DetectionEngine detection;

    @BeforeEach
    void isolateAndReleaseClient3() {
        PipelineProperties pipelineProps = new PipelineProperties();
        authorization = new PipelineProperties.Stage();
        pipelineProps.getStages().put("authorization", authorization);
        PipelineMetrics metrics = new PipelineMetrics(new SimpleMeterRegistry());

        RecoveryProperties recoveryProps = new RecoveryProperties();
        quarantine = new ClientQuarantine(recoveryProps);
        pipeline = new TransactionPipeline(
                List.of(new ValidationStage(pipelineProps), new AuthorizationStage(pipelineProps),
                        new SettlementStage(pipelineProps)),
                metrics, List.of(quarantine));
        r2 = new ClientIsolationAction(quarantine, pipeline, metrics, recoveryProps);
        recovery = new RecoveryEngine(List.of(r2), recoveryProps);

        DetectionProperties detectionProps = new DetectionProperties();
        detectionProps.setWarmupWindows(3);
        detectionProps.setMinWindowSamples(10);
        // Stands in for an episode that outlasts the verdict cooldown; here
        // the whole episode takes milliseconds.
        detectionProps.setCooldownSeconds(0);
        VerdictStore verdicts = new VerdictStore(detectionProps,
                event -> recovery.onVerdict((VerdictRecorded) event));
        detection = new DetectionEngine(metrics,
                List.of(new ClientDivergencePrimitive(new BaselineStore(), detectionProps, verdicts)));

        for (int i = 0; i < 4; i++) {
            window();
        }
        authorization.getClientFailureProbability().put("client-3", 1.0);
        window();
        window();
        assertThat(quarantine.isIsolated("client-3")).isTrue();

        window();
        window();
        authorization.getClientFailureProbability().remove("client-3");
        for (int i = 0; i < 20 && quarantine.isIsolated("client-3"); i++) {
            r2.probeRound();
        }
        assertThat(quarantine.views().get(0).status()).isEqualTo("RELEASED");
        assertThat(recovery.decisions()).hasSize(1);
    }

    @Test
    void oneBadWindowAfterReleaseDoesNotReisolate() {
        spike("client-3");

        assertThat(quarantine.isIsolated("client-3")).isFalse();
        assertThat(recovery.decisions()).hasSize(1);

        // The same spike on a client that was never isolated gets the same answer.
        spike("client-1");

        assertThat(quarantine.isIsolated("client-1")).isFalse();
        assertThat(recovery.decisions()).hasSize(1);
    }

    @Test
    void twoBadWindowsInARowAfterReleaseDoReisolate() {
        authorization.getClientFailureProbability().put("client-3", 1.0);
        window();
        assertThat(quarantine.isIsolated("client-3")).isFalse();

        window();

        assertThat(quarantine.isIsolated("client-3")).isTrue();
        assertThat(recovery.decisions()).hasSize(2);
    }

    /** One window with every transaction from this client failing. */
    private void spike(String client) {
        authorization.getClientFailureProbability().put(client, 1.0);
        window();
        authorization.getClientFailureProbability().remove(client);
    }

    private void window() {
        for (int i = 0; i < PER_CLIENT; i++) {
            for (String client : CLIENTS) {
                pipeline.submit(new Transaction(client, BigDecimal.TEN));
            }
        }
        detection.tick();
    }
}
