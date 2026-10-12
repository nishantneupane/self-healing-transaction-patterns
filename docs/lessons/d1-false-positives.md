# My failure detector quarantined three healthy clients and missed the broken one

I have spent the last few years as a backend engineer on card payment systems, and most of what I learned about reliability came from incidents rather than design documents. This year I started writing those lessons down as an open-source project: a taxonomy of failure modes in card transaction pipelines, a detection module that learns its own baselines, and a recovery module that acts on what detection finds. This post is about the first time the detection and recovery loop ran end to end, and did exactly the opposite of its job.

## Why per-client detection

Fleet averages hide the failures that matter. In my experience, when one client's failures diverged, the cause was almost always specific to that client: their option configuration, or the fact that their cards used chip features handled through an external vendor's service. Different clients genuinely run different code paths and dependency chains through the same pipeline. A fleet-level failure rate of 2.5% can look perfectly healthy while one client is failing half its traffic.

Fleet-level averages hid every gray failure I saw in production. A single client's failure rate diverging never lied. So the first detection primitive in the project, D1, compares each client's outcomes against its own history, and the first recovery action, R2, isolates a diverging client: its new transactions are held in arrival order while everyone else keeps flowing, probes test whether the path has healed, and the backlog replays safely afterward.

Recovery acting on detection raises the stakes. A false positive no longer just pages someone. It quarantines a healthy client's traffic.

## The first live run

The reference pipeline is a small Spring Boot service with a synthetic generator, three stages, five synthetic clients, and an injection API that breaks one client's path on demand. For the first run I used fast settings: a transaction every 100 milliseconds, 4-second detection windows, and a short warmup. Then I injected a 50% authorization failure into one client and waited.

Within 40 seconds of detection arming, D1 isolated client-5 at a 33% failure ratio against a learned baseline of 0.0000. Eight seconds later it isolated client-4 at 20%, also against a baseline of zero. A minute later it isolated client-1 at 20%, released it, and then isolated it again at 25%.

None of those clients had anything wrong with them. The one client whose path I had actually broken was never named.

## Why it happened

Two things combined.

The first was sample size. At that rate each client saw about six transactions per window. A single ordinary decline moved its failure ratio to 17%, and two declines to 33%. That is not evidence of anything. The healthy authorization decline rate in the pipeline is 2%, so a decline every few windows is expected behavior.

The second was the baseline, poisoned in the other direction. It learned from every window that did not trigger, and at six transactions per window most windows have zero failures. So it came to believe the normal failure rate was 0.0000 with almost no spread. Against that baseline, any failure at all looked like divergence.

The detector was comparing noise against a baseline that said noise was impossible. Meanwhile the broken client, which really was failing, got lost in the same noise, and once three healthy clients were isolated, the rule that the rest of the fleet must be quiet kept the real one from being named.

## The redesign

I changed how D1 counts, judges, learns and confirms.

It pools each client's counts across windows instead of averaging per-window ratios, so a six-transaction window no longer weighs as much as a sixty-transaction one. A client with little history has its rate pulled toward the fleet rate until it has earned its own. Each window is judged with an exact binomial test: given this client's normal rate and this many transactions, how likely is this many failures. It learns only from windows that look ordinary, so an incident never becomes its own baseline. And it names a client only after two divergent windows in a row.

Of those changes, the binomial test matters most to me, because it is the piece that makes the threshold depend on sample size. With six transactions, two failures is unremarkable. With sixty, it is a signal. A fixed ratio threshold cannot tell those apart, and a learned standard deviation cannot either when most of the learned windows had zero failures. Everything else in the redesign supports that one idea.

The second run, with the same injection, isolated only the broken client, kept it isolated while it was failing, released it a few seconds after I healed the path, and replayed every held transaction exactly once.

## The lesson

A detector that fires wrongly is worse than no detector, because people learn to ignore it. I had seen that in production: alerts that fired when nothing was wrong, because a job ran longer than the time someone had set years earlier, and nobody re-tuned the numbers, so the alerts cried wolf on schedule. That experience is why this project has no static thresholds anywhere. Every trigger is relative to a baseline the module maintains itself.

What the first run taught me is that a learned baseline can cry wolf too, if it learns from the wrong windows or trusts too few samples. The fix was not a better number. It was making the detector aware of how much evidence it actually had.

For anyone building auto-recovery on top of detection, this is the part I would underline. Detection has to be tested like the system it watches. The project's first tests were written for this redesign, and they include mutation checks: deliberately break the learning gate or the confirmation rule and confirm a test fails.

The second half of that lesson came after I published the repository. An outside engineer read the detection code and found two more detectors that were quietly blind: one fitting a trend line with arithmetic on large timestamps that lost its precision, and one that only alarmed on exactly zero traffic and learned any partial drop as the new normal. Neither showed up in the demo. Detectors fail silently, and the only way to know is to test them against the failures they claim to catch, and to let other people read them.

The redesigned primitive is `ClientDivergencePrimitive` in the reference pipeline, and the root README has a three-minute demo: break one client's path, and watch it get detected, isolated and replayed. If you work on payment or transaction systems and have seen a failure mode the taxonomy misses, open an issue. I would like to hear about it.
