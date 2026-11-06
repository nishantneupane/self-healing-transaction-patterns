package io.github.pawanpandey407.pipeline.detection;

import io.github.pawanpandey407.pipeline.config.DetectionProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * D1: per-client outcome divergence.
 *
 * Names a client whose failures leave its own normal rate while the rest
 * of the fleet stays inside the fleet's. Recovery isolates the client it
 * names, so a false positive here quarantines a healthy client and a miss
 * leaves a broken one running. The rules below exist because an earlier,
 * ratio-averaging version did both in its first live run with recovery.
 *
 * <ul>
 *   <li>Rates are learned from pooled counts, and a client with little
 *   history borrows the fleet's rate, so a few small windows cannot swing
 *   what "normal" means.</li>
 *   <li>Each window is judged by an exact binomial tail probability: how
 *   likely are at least this many failures, in this many transactions,
 *   at the client's normal rate.</li>
 *   <li>Only ordinary windows are learned, during warmup too. Suspicious
 *   and borderline windows never enter the baseline they will be judged
 *   against, so an incident cannot redefine normal.</li>
 *   <li>A client must diverge in consecutive windows before it is named.
 *   One unlucky window is not an incident. A client with no live traffic
 *   in a window loses its streak, which is also how an isolated client
 *   starts over after its release. A thin window still counts: the exact
 *   test already weighs its size, so a thin window that is failing keeps
 *   the streak alive and an ordinary one breaks it, but it is too little
 *   to learn from or to name a client on.</li>
 * </ul>
 */
@Component
@Order(1)
public class ClientDivergencePrimitive implements DetectionPrimitive {

    /** Verdict primitive name; recovery actions match on it. */
    public static final String NAME = "D1-client-divergence";

    private static final String FLEET = "fleet";

    private final BaselineStore baselines;
    private final DetectionProperties props;
    private final VerdictStore verdicts;
    private final ConcurrentMap<String, Integer> consecutiveDivergent = new ConcurrentHashMap<>();

    public ClientDivergencePrimitive(BaselineStore baselines, DetectionProperties props, VerdictStore verdicts) {
        this.baselines = baselines;
        this.props = props;
        this.verdicts = verdicts;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Verdict> observe(WindowSnapshot w) {
        List<Verdict> out = new ArrayList<>();
        BaselineStore.RateBaseline fleet = baselines.rate(name(), FLEET, w.hourBucket());
        double fleetRate = rateOf(fleet.failures(), fleet.transactions());
        boolean warmedUp = fleet.windows() >= props.getWarmupWindows();

        Set<String> clients = new HashSet<>(w.successByClient().keySet());
        clients.addAll(w.failureByClient().keySet());
        consecutiveDivergent.keySet().retainAll(clients);

        long learnedFailures = 0;
        long learnedTransactions = 0;
        for (String client : clients) {
            long n = w.clientTotal(client);
            long k = w.failureByClient().getOrDefault(client, 0L);
            BaselineStore.RateBaseline own = baselines.rate(name(), client, w.hourBucket());
            double expected = expectedRate(own, fleetRate);
            double tail = Binomial.upperTail(k, n, expected);
            double ratio = (double) k / n;

            boolean divergent = tail < props.getDivergenceAlpha() && ratio - expected >= props.getRatioFloor();
            int streak = divergent ? consecutiveDivergent.merge(client, 1, Integer::sum) : 0;
            if (!divergent) {
                consecutiveDivergent.remove(client);
            }
            if (n < props.getMinWindowSamples()) {
                continue;
            }

            if (tail >= props.getLearnAlpha()) {
                own.add(k, n);
                learnedFailures += k;
                learnedTransactions += n;
            }

            if (divergent && warmedUp && streak >= props.getConfirmWindows()
                    && restOfFleetQuiet(w, client, n, k, fleetRate)) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("windowTransactions", n);
                evidence.put("windowFailures", k);
                evidence.put("failingStages", w.failuresByClientStage().getOrDefault(client, Map.of()));
                evidence.put("tailProbability", tail);
                evidence.put("consecutiveWindows", streak);
                out.add(new Verdict(Instant.now(), name(), client, ratio, expected,
                        Math.sqrt(expected * (1 - expected) / n),
                        "client failures diverged from its normal rate for consecutive windows "
                                + "while the rest of the fleet stayed quiet",
                        evidence));
            }
        }
        if (learnedTransactions > 0) {
            fleet.add(learnedFailures, learnedTransactions);
        }

        out.forEach(verdicts::add);
        return out;
    }

    /** The client's pooled rate, shrunk toward the fleet's rate by the prior weight. */
    private double expectedRate(BaselineStore.RateBaseline own, double fleetRate) {
        double weight = props.getPriorWeight();
        double rate = (own.failures() + weight * fleetRate) / (own.transactions() + weight);
        return Math.max(props.getMinRate(), Math.min(rate, 1.0 - props.getMinRate()));
    }

    private double rateOf(double failures, double transactions) {
        double rate = transactions > 0 ? failures / transactions : 0.0;
        return Math.max(props.getMinRate(), rate);
    }

    /**
     * The rest of the fleet is quiet when its failures in this window are
     * ordinary at the fleet's rate. If everyone is failing, the fault is
     * not this client's, and isolating one client would be the wrong move.
     */
    private boolean restOfFleetQuiet(WindowSnapshot w, String candidate, long candidateN, long candidateK,
                                     double fleetRate) {
        long restN = w.processed() - candidateN;
        if (restN <= 0) {
            return false;
        }
        long restK = w.failed() - candidateK;
        return Binomial.upperTail(restK, restN, fleetRate) >= props.getDivergenceAlpha();
    }
}
