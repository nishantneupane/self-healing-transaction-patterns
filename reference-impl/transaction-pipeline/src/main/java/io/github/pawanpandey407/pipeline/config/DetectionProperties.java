package io.github.pawanpandey407.pipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for the detection module.
 *
 * Every trigger is relative to a learned baseline; there are no static
 * thresholds. The floors exist to keep tiny-sample noise from alarming
 * when a baseline's variance is near zero.
 */
@ConfigurationProperties(prefix = "detection")
public class DetectionProperties {

    /** Evaluation window length in milliseconds. */
    private long windowMs = 10000;

    /** Windows a baseline must have seen before it can trigger verdicts. */
    private int warmupWindows = 12;

    /** Deviation multiplier: trigger beyond mean + sigma * stddev. */
    private double sigma = 3.0;

    /** Minimum observations in a window before a ratio is evaluated. */
    private int minWindowSamples = 10;

    /** Minimum absolute ratio change (D1, D2) worth a verdict. */
    private double ratioFloor = 0.05;

    /** Minimum relative change (D3, D4) worth a verdict. */
    private double relativeFloor = 0.3;

    /** Utilization level treated as saturation (D5). */
    private double saturationThreshold = 0.9;

    /** D5 alarms when the trend reaches saturation within this horizon. */
    private long projectionHorizonSeconds = 600;

    /** Minimum seconds between repeated verdicts for the same subject and condition. */
    private long cooldownSeconds = 60;

    /**
     * D1: a client window is divergent when failures at least this
     * extreme would happen with probability below this value at the
     * client's normal rate.
     */
    private double divergenceAlpha = 0.001;

    /**
     * D1: a client window is learned only when its failures are ordinary
     * at the client's normal rate, meaning a tail probability of at least
     * this value. Windows between the two alphas are neither flagged nor
     * learned, so a borderline start to an incident cannot poison the
     * baseline it will be judged against.
     */
    private double learnAlpha = 0.01;

    /** D1 and D4: consecutive divergent (D1) or low (D4 partial drop) windows required before a verdict. */
    private int confirmWindows = 2;

    /**
     * D1: weight, in transactions, of the fleet's rate in a client's
     * expected rate. A client with little history borrows the fleet's
     * rate; a client with a long history is judged mostly on its own.
     */
    private double priorWeight = 50;

    /** D1: floor on any expected failure rate, so a quiet stretch never implies zero. */
    private double minRate = 0.005;

    public long getWindowMs() {
        return windowMs;
    }

    public void setWindowMs(long windowMs) {
        this.windowMs = windowMs;
    }

    public int getWarmupWindows() {
        return warmupWindows;
    }

    public void setWarmupWindows(int warmupWindows) {
        this.warmupWindows = warmupWindows;
    }

    public double getSigma() {
        return sigma;
    }

    public void setSigma(double sigma) {
        this.sigma = sigma;
    }

    public int getMinWindowSamples() {
        return minWindowSamples;
    }

    public void setMinWindowSamples(int minWindowSamples) {
        this.minWindowSamples = minWindowSamples;
    }

    public double getRatioFloor() {
        return ratioFloor;
    }

    public void setRatioFloor(double ratioFloor) {
        this.ratioFloor = ratioFloor;
    }

    public double getRelativeFloor() {
        return relativeFloor;
    }

    public void setRelativeFloor(double relativeFloor) {
        this.relativeFloor = relativeFloor;
    }

    public double getSaturationThreshold() {
        return saturationThreshold;
    }

    public void setSaturationThreshold(double saturationThreshold) {
        this.saturationThreshold = saturationThreshold;
    }

    public long getProjectionHorizonSeconds() {
        return projectionHorizonSeconds;
    }

    public void setProjectionHorizonSeconds(long projectionHorizonSeconds) {
        this.projectionHorizonSeconds = projectionHorizonSeconds;
    }

    public long getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(long cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public double getDivergenceAlpha() {
        return divergenceAlpha;
    }

    public void setDivergenceAlpha(double divergenceAlpha) {
        this.divergenceAlpha = divergenceAlpha;
    }

    public double getLearnAlpha() {
        return learnAlpha;
    }

    public void setLearnAlpha(double learnAlpha) {
        this.learnAlpha = learnAlpha;
    }

    public int getConfirmWindows() {
        return confirmWindows;
    }

    public void setConfirmWindows(int confirmWindows) {
        this.confirmWindows = confirmWindows;
    }

    public double getPriorWeight() {
        return priorWeight;
    }

    public void setPriorWeight(double priorWeight) {
        this.priorWeight = priorWeight;
    }

    public double getMinRate() {
        return minRate;
    }

    public void setMinRate(double minRate) {
        this.minRate = minRate;
    }
}
