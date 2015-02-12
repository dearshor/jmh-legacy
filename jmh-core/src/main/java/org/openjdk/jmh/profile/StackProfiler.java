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
package org.openjdk.jmh.profile;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.Aggregator;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ResultRole;
import org.openjdk.jmh.util.HashMultiset;
import org.openjdk.jmh.util.Multiset;
import org.openjdk.jmh.util.Multisets;

import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Very basic and naive stack profiler.
 */
public class StackProfiler implements InternalProfiler {

    /** Number of stack lines to save */
    private static final int SAMPLE_STACK_LINES = Integer.getInteger("jmh.stack.lines", 1);

    /** Number of top stacks to show */
    private static final int SAMPLE_TOP_STACKS =  Integer.getInteger("jmh.stack.top", 10);

    /** Sampling period */
    private static final int SAMPLE_PERIOD_MSEC = Integer.getInteger("jmh.stack.period", 10);

    /** Record detailed line info */
    private static final boolean SAMPLE_LINE =    Boolean.getBoolean("jmh.stack.detailLine");

    /** Threads to ignore (known system and harness threads) */
    private static final String[] IGNORED_THREADS = {
            "Finalizer",
            "Signal Dispatcher",
            "Reference Handler",
            "main",
            "Sampling Thread",
            "Attach Listener"
    };

    /** Whether or not filter the packages. */
    private static final Boolean EXCLUDE_PACKAGES = Boolean.getBoolean("jmh.stack.excludePackages");

    /**
     * Requested excluded packages from system properties. This is expected to be a comma (,) separated list
     * of the fully qualified package names to be excluded. Every stack line that starts with the provided
     * patterns will be excluded. If the default package exclusion is enabled, the list would be added.
     */
    private static final Set<String> EXCLUDE_PACKAGES_NAMES;

    static {
        if (EXCLUDE_PACKAGES) {
            String userNames = System.getProperty("jmh.stack.excludePackageNames");
            EXCLUDE_PACKAGES_NAMES = new HashSet<String>(
                    (userNames != null) ?
                        Arrays.asList(userNames.split(",")) :
                        Arrays.asList("java.", "javax.", "sun.", "sunw.", "com.sun.", "org.openjdk.jmh."));
        } else {
            EXCLUDE_PACKAGES_NAMES = Collections.emptySet();
        }
    }

    private volatile SamplingTask samplingTask;

    @Override
    public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
        samplingTask = new SamplingTask();
        samplingTask.start();
    }

    @Override
    public Collection<? extends Result> afterIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
        samplingTask.stop();
        return Collections.singleton(new StackResult(samplingTask.stacks));
    }

    @Override
    public boolean checkSupport(List<String> msg) {
        return true;
    }

    @Override
    public String label() {
        return "stack";
    }

    @Override
    public String getDescription() {
        return "Simple and naive Java stack profiler";
    }

    public static class SamplingTask implements Runnable {

        private final Thread thread;
        private final Map<Thread.State, Multiset<StackRecord>> stacks;

        public SamplingTask() {
            stacks = new EnumMap<Thread.State, Multiset<StackRecord>>(Thread.State.class);
            for (Thread.State s : Thread.State.values()) {
                stacks.put(s, new HashMultiset<StackRecord>());
            }
            thread = new Thread(this);
            thread.setName("Sampling Thread");
        }

        @Override
        public void run() {

            while (!Thread.interrupted()) {
                ThreadInfo[] infos = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);

                info:
                for (ThreadInfo info : infos) {

                    // filter out ignored threads
                    for (String ignore : IGNORED_THREADS) {
                        if (info.getThreadName().equalsIgnoreCase(ignore)) {
                            continue info;
                        }
                    }

                    //   - Discard everything that matches excluded patterns from the top of the stack
                    //   - Get the remaining number of stack lines and build the stack record

                    StackTraceElement[] stack = info.getStackTrace();
                    List<String> stackLines = new ArrayList<String>();

                    for (StackTraceElement l : stack) {
                        String className = l.getClassName();
                        if (!isExcluded(className)) {
                            stackLines.add(className + '.' + l.getMethodName()
                                    + (SAMPLE_LINE ? ":" + l.getLineNumber() : ""));

                            if (stackLines.size() >= SAMPLE_STACK_LINES) {
                                break;
                            }
                        }
                    }

                    if (stackLines.isEmpty()) {
                        stackLines.add("<stack is empty, everything is filtered?>");
                    }

                    Thread.State state = info.getThreadState();
                    stacks.get(state).add(new StackRecord(stackLines));
                }

                try {
                    TimeUnit.MILLISECONDS.sleep(SAMPLE_PERIOD_MSEC);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        public void start() {
            thread.start();
        }

        public void stop() {
            thread.interrupt();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean isExcluded(String className) {
            for (String p : EXCLUDE_PACKAGES_NAMES) {
                if (className.startsWith(p)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class StackRecord implements Serializable {
        private static final long serialVersionUID = -1829626661894754733L;

        public final List<String> lines;

        private StackRecord(List<String> lines) {
            this.lines = lines;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            StackRecord that = (StackRecord) o;

            return lines.equals(that.lines);
        }

        @Override
        public int hashCode() {
            return lines.hashCode();
        }
    }

    public static class StackResult extends Result<StackResult> {
        private static final long serialVersionUID = 2609170863630346073L;

        private final Map<Thread.State, Multiset<StackRecord>> stacks;

        public StackResult(Map<Thread.State, Multiset<StackRecord>> stacks) {
            super(ResultRole.SECONDARY, "@stack", of(Double.NaN), "---", AggregationPolicy.AVG);
            this.stacks = stacks;
        }

        @Override
        protected Aggregator<StackResult> getThreadAggregator() {
            return new StackResultAggregator();
        }

        @Override
        protected Aggregator<StackResult> getIterationAggregator() {
            return new StackResultAggregator();
        }

        @Override
        public String toString() {
            return "<delayed till summary>";
        }

        @Override
        public String extendedInfo(String label) {
            return getStack(stacks);
        }

        public String getStack(final Map<Thread.State, Multiset<StackRecord>> stacks) {
            List<Thread.State> sortedStates = new ArrayList<Thread.State>(stacks.keySet());
            Collections.sort(sortedStates, new Comparator<Thread.State>() {

                private long stateSize(Thread.State state) {
                    Multiset<StackRecord> set = stacks.get(state);
                    return (set == null) ? 0 : set.size();
                }

                @Override
                public int compare(Thread.State s1, Thread.State s2) {
                    return Long.valueOf(stateSize(s2)).compareTo(stateSize(s1));
                }

            });

            long totalSize = getTotalSize(stacks);

            StringBuilder builder = new StringBuilder();
            builder.append("Stack profiler:\n\n");

            builder.append(dottedLine("Thread state distributions"));
            for (Thread.State state : sortedStates) {
                if (isSignificant(stacks.get(state).size(), totalSize)) {
                    builder.append(String.format("%5.1f%% %7s %s%n", stacks.get(state).size() * 100.0 / totalSize, "", state));
                }
            }
            builder.append("\n");

            for (Thread.State state : sortedStates) {
                Multiset<StackRecord> stateStacks = stacks.get(state);
                if (isSignificant(stateStacks.size(), totalSize)) {
                    builder.append(dottedLine("Thread state: " + state.toString()));

                    int totalDisplayed = 0;
                    for (StackRecord s : Multisets.countHighest(stateStacks, SAMPLE_TOP_STACKS)) {
                        List<String> lines = s.lines;
                        if (!lines.isEmpty()) {
                            totalDisplayed += stateStacks.count(s);
                            builder.append(String.format("%5.1f%% %5.1f%% %s%n",
                                stateStacks.count(s) * 100.0 / totalSize,
                                stateStacks.count(s) * 100.0 / stateStacks.size(),
                                lines.get(0)));
                            if (lines.size() > 1) {
                                for (int i = 1; i < lines.size(); i++) {
                                    builder.append(String.format("%13s %s%n", "", lines.get(i)));
                                }
                                builder.append("\n");
                            }
                        }
                    }
                    if (isSignificant((stateStacks.size() - totalDisplayed), stateStacks.size())) {
                        builder.append(String.format("%5.1f%% %5.1f%% %s%n",
                            (stateStacks.size() - totalDisplayed) * 100.0 / totalSize,
                            (stateStacks.size() - totalDisplayed) * 100.0 / stateStacks.size(),
                            "<other>"));
                    }

                    builder.append("\n");
                }
            }
            return builder.toString();
        }

        // returns true, if part is >0.1% of total
        private boolean isSignificant(long part, long total) {
            // returns true if part*100.0/total is greater or equals to 0.1
            return part * 1000 >= total;
        }

        private long getTotalSize(Map<Thread.State, Multiset<StackRecord>> stacks) {
            long sum = 0;
            for (Multiset<StackRecord> set : stacks.values()) {
                sum += set.size();
            }
            return sum;
        }
    }

    static String dottedLine(String header) {
        final int HEADER_WIDTH = 100;

        StringBuilder sb = new StringBuilder();
        sb.append("....");
        if (header != null) {
            header = "[" + header + "]";
            sb.append(header);
        } else {
            header = "";
        }

        for (int c = 0; c < HEADER_WIDTH - 4 - header.length(); c++) {
            sb.append(".");
        }
        sb.append("\n");
        return sb.toString();
    }

    public static class StackResultAggregator implements Aggregator<StackResult> {
        @Override
        public StackResult aggregate(Collection<StackResult> results) {
            Map<Thread.State, Multiset<StackRecord>> sum = new EnumMap<Thread.State, Multiset<StackRecord>>(Thread.State.class);
            for (StackResult r : results) {
                for (Map.Entry<Thread.State, Multiset<StackRecord>> entry : r.stacks.entrySet()) {
                    if (!sum.containsKey(entry.getKey())) {
                        sum.put(entry.getKey(), new HashMultiset<StackRecord>());
                    }
                    Multiset<StackRecord> sumSet = sum.get(entry.getKey());
                    for (StackRecord rec : entry.getValue().keys()) {
                        sumSet.add(rec, entry.getValue().count(rec));
                    }
                }
            }
            return new StackResult(sum);
        }
    }

}
