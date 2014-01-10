/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jmh.runner.parameters;


import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.runner.ActionMode;
import org.openjdk.jmh.runner.BenchmarkRecord;
import org.openjdk.jmh.runner.MicroBenchmarkHandlers;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.util.ClassUtils;
import org.openjdk.jmh.util.Utils;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Arrays;

public class BenchmarkParams implements Serializable {

    private final boolean synchIterations;
    private final int threads;
    private final int[] threadGroups;
    private final int forks;
    private final int warmupForks;
    private final IterationParams warmup;
    private final IterationParams measurement;

    private int decideForks(int optionForks, int benchForks) {
        if (optionForks == -1) {
            if (benchForks == -1) {
                return Defaults.DEFAULT_FORK_TIMES;
            } else {
                return benchForks;
            }
        } else {
            return optionForks;
        }
    }

    private int decideWarmupForks(int optionWarmupForks, int benchWarmupForks) {
        if (optionWarmupForks == -1) {
            if (benchWarmupForks == -1) {
                return Defaults.DEFAULT_WARMUP_FORK_TIMES;
            } else {
                return benchWarmupForks;
            }
        } else {
            return optionWarmupForks;
        }
    }

    /**
     * Test entry method
     */
    public BenchmarkParams(boolean synchIterations, int threads, int[] threadGroups, int forks, int warmupForks, int warmupIters, TimeValue warmupTime, int measureIters, TimeValue measureTime) {
        this.synchIterations = synchIterations;
        this.threads = threads;
        this.threadGroups = threadGroups;
        this.forks = forks;
        this.warmupForks = warmupForks;
        this.warmup = new IterationParams(this, warmupIters, warmupTime);
        this.measurement = new IterationParams(this, measureIters, measureTime);
    }

    public BenchmarkParams(Options options, BenchmarkRecord benchmark, ActionMode mode) {
        this.threadGroups = decideThreadGroups(options.getThreadGroups(), benchmark.getThreadGroups());

        int threads = options.getThreads() > Integer.MIN_VALUE ? options.getThreads() : benchmark.getThreads().orElse(Defaults.THREADS);
        if (threads == Threads.MAX) {
            threads = Runtime.getRuntime().availableProcessors();
        }
        this.threads = Utils.roundUp(threads, Utils.sum(threadGroups));

        this.synchIterations = getBoolean(options.shouldSyncIterations(), Defaults.SHOULD_SYNCH_ITERATIONS);

        this.measurement = mode.doMeasurement() ?
                getMeasurement(options, benchmark) :
                new IterationParams(this, 0, TimeValue.NONE);

        this.warmup = mode.doWarmup() ?
                getWarmup(options, benchmark) :
                new IterationParams(this, 0, TimeValue.NONE);

        this.forks = decideForks(options.getForkCount(), benchmark.getForks().orElse(Defaults.DEFAULT_FORK_TIMES));
        this.warmupForks = decideWarmupForks(options.getWarmupForkCount(), benchmark.getWarmupForks().orElse(Defaults.DEFAULT_WARMUP_FORK_TIMES));
    }

    private IterationParams getWarmup(Options options, BenchmarkRecord benchmark) {
        boolean isSingleShot = (benchmark.getMode() == Mode.SingleShotTime);
        int iters = benchmark.getWarmupIterations().orElse(-1);
        if (isSingleShot) {
            return new IterationParams(
                    this,
                    getInteger(options.getWarmupIterations(), iters, Defaults.SINGLE_SHOT_WARMUP_COUNT),
                    TimeValue.NONE);
        } else {
            TimeValue timeValue = options.getWarmupTime();
            if (timeValue == null || timeValue.getTime() == -1) {
                timeValue = benchmark.getWarmupTime().orElse(Defaults.WARMUP_TIME);
            }
            return new IterationParams(
                    this,
                    getInteger(options.getWarmupIterations(), iters, Defaults.WARMUP_ITERATION_COUNT),
                    timeValue);
        }
    }

    private IterationParams getMeasurement(Options options, BenchmarkRecord benchmark) {
        boolean isSingleShot = (benchmark.getMode() == Mode.SingleShotTime);
        int iters = benchmark.getMeasurementIterations().orElse(-1);
        if (isSingleShot) {
            return new IterationParams(
                    this,
                    getInteger(options.getMeasurementIterations(), iters, Defaults.SINGLE_SHOT_ITERATION_COUNT),
                    TimeValue.NONE);

        } else {
            TimeValue timeValue = options.getMeasurementTime();
            if (timeValue == null || timeValue.getTime() == -1) {
                timeValue = benchmark.getMeasurementTime().orElse(Defaults.ITERATION_TIME);
            }
            return new IterationParams(
                    this,
                    getInteger(options.getMeasurementIterations(), iters, Defaults.MEASUREMENT_ITERATION_COUNT),
                    timeValue);
        }
    }

    public boolean shouldSynchIterations() {
        return synchIterations;
    }

    public IterationParams getWarmup() {
        return warmup;
    }

    public IterationParams getMeasurement() {
        return measurement;
    }

    public int getThreads() {
        return threads;
    }

    public int[] getThreadGroups() {
        return threadGroups;
    }

    public int getForks() {
        return forks;
    }

    public int getWarmupForks() {
        return warmupForks;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BenchmarkParams that = (BenchmarkParams) o;

        if (forks != that.forks) return false;
        if (synchIterations != that.synchIterations) return false;
        if (threads != that.threads) return false;
        if (warmupForks != that.warmupForks) return false;
        if (!measurement.equals(that.measurement)) return false;
        if (!Arrays.equals(threadGroups, that.threadGroups)) return false;
        if (!warmup.equals(that.warmup)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (synchIterations ? 1 : 0);
        result = 31 * result + threads;
        result = 31 * result + Arrays.hashCode(threadGroups);
        result = 31 * result + forks;
        result = 31 * result + warmupForks;
        result = 31 * result + warmup.hashCode();
        result = 31 * result + measurement.hashCode();
        return result;
    }

    private static int[] decideThreadGroups(int[] first, int[] second) {
        if (first.length == 1 && first[0] == 1) {
            return second;
        } else {
            return first;
        }
    }

    private static boolean getBoolean(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static int getInteger(int first, int second, int third) {
        return first >= 0 ? first : (second >= 0 ? second : third);
    }

}
