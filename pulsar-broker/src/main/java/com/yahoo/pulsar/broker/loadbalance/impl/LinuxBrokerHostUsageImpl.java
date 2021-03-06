/**
 * Copyright 2016 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.pulsar.broker.loadbalance.impl;

import com.sun.management.OperatingSystemMXBean;
import com.yahoo.pulsar.broker.PulsarService;
import com.yahoo.pulsar.broker.loadbalance.BrokerHostUsage;
import com.yahoo.pulsar.common.policies.data.loadbalancer.ResourceUsage;
import com.yahoo.pulsar.common.policies.data.loadbalancer.SystemResourceUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Class that will return the broker host usage.
 *
 *
 */
public class LinuxBrokerHostUsageImpl implements BrokerHostUsage {
    // The interval for host usage check command
    private final int hostUsageCheckIntervalMin;
    private long lastCollection;
    private double lastTotalNicUsageTx;
    private double lastTotalNicUsageRx;
    private CpuStat lastCpuStat;
    private OperatingSystemMXBean systemBean;
    private SystemResourceUsage usage;

    private static final Logger LOG = LoggerFactory.getLogger(LinuxBrokerHostUsageImpl.class);

    public LinuxBrokerHostUsageImpl(PulsarService pulsar) {
        this.hostUsageCheckIntervalMin = pulsar.getConfiguration().getLoadBalancerHostUsageCheckIntervalMinutes();
        this.systemBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.lastCollection = 0L;
        this.usage = new SystemResourceUsage();
        pulsar.getLoadManagerExecutor().scheduleAtFixedRate(this::calculateBrokerHostUsage, 0, hostUsageCheckIntervalMin, TimeUnit.MINUTES);
    }

    @Override
    public SystemResourceUsage getBrokerHostUsage() {
        return usage;
    }

    private void calculateBrokerHostUsage() {
        List<String> nics = getNics();
        double totalNicLimit = getTotalNicLimitKbps(nics);
        double totalNicUsageTx = getTotalNicUsageTxKb(nics);
        double totalNicUsageRx = getTotalNicUsageRxKb(nics);
        double totalCpuLimit = getTotalCpuLimit();
        CpuStat cpuStat = getTotalCpuUsage();
        SystemResourceUsage usage = new SystemResourceUsage();
        long now = System.currentTimeMillis();

        if (lastCollection == 0L) {
            usage.setMemory(getMemUsage());
            usage.setBandwidthIn(new ResourceUsage(0d, totalNicLimit));
            usage.setBandwidthOut(new ResourceUsage(0d, totalNicLimit));
            usage.setCpu(new ResourceUsage(0d, totalCpuLimit));
        } else {
            double elapsedSeconds = (now - lastCollection) / 1000d;
            double nicUsageTx = (totalNicUsageTx - lastTotalNicUsageTx) / elapsedSeconds;
            double nicUsageRx = (totalNicUsageRx - lastTotalNicUsageRx) / elapsedSeconds;

            if (cpuStat != null && lastCpuStat != null) {
                // we need two non null stats to get a usage report
                long cpuTimeDiff = cpuStat.getTotalTime() - lastCpuStat.getTotalTime();
                long cpuUsageDiff = cpuStat.getUsage() - lastCpuStat.getUsage();
                double cpuUsage = ((double) cpuUsageDiff / (double) cpuTimeDiff) * totalCpuLimit;
                usage.setCpu(new ResourceUsage(cpuUsage, totalCpuLimit));
            }

            usage.setMemory(getMemUsage());
            usage.setBandwidthIn(new ResourceUsage(nicUsageRx, totalNicLimit));
            usage.setBandwidthOut(new ResourceUsage(nicUsageTx, totalNicLimit));
        }

        lastTotalNicUsageTx = totalNicUsageTx;
        lastTotalNicUsageRx = totalNicUsageRx;
        lastCpuStat = cpuStat;
        lastCollection = System.currentTimeMillis();
        this.usage = usage;
    }

    private double getTotalCpuLimit() {
        return (double) (100 * Runtime.getRuntime().availableProcessors());
    }

    /**
     * Reads first line of /proc/stat to get total cpu usage.
     * <pre>
     *     cpu  user   nice system idle    iowait irq softirq steal guest guest_nice
     *     cpu  317808 128  58637  2503692 7634   0   13472   0     0     0
     * </pre>
     * Line is split in "words", filtering the first.
     * The sum of all numbers give the amount of cpu cycles used this far.
     * Real CPU usage should equal the sum substracting the idle cycles,
     * this would include iowait, irq and steal.
     */
    private CpuStat getTotalCpuUsage() {
        try (Stream<String> stream = Files.lines(Paths.get("/proc/stat"))) {
            String[] words = stream
                    .findFirst()
                    .get().split("\\s+");

            long total = Arrays.stream(words)
                    .filter(s -> !s.contains("cpu"))
                    .mapToLong(Long::parseLong)
                    .sum();

            long idle = Long.parseLong(words[4]);

            return new CpuStat(total, total - idle);
        } catch (IOException e) {
            LOG.error("Failed to read CPU usage from /proc/stat", e);
            return null;
        }
    }

    private ResourceUsage getMemUsage() {
        double total = ((double) systemBean.getTotalPhysicalMemorySize()) / (1024 * 1024);
        double free = ((double) systemBean.getFreePhysicalMemorySize()) / (1024 * 1024);
        return new ResourceUsage(total - free, total);
    }

    private List<String> getNics() {
        try (Stream<Path> stream = Files.list(Paths.get("/sys/class/net/"))) {
            return stream
                    .filter(this::isPhysicalNic)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LOG.error("Failed to find NICs", e);
            return Collections.emptyList();
        }
    }

    private boolean isPhysicalNic(Path path) {
        try {
            path = Files.isSymbolicLink(path) ? Files.readSymbolicLink(path) : path;
            if (!path.toString().contains("/virtual/")) {
                try {
                    Files.readAllBytes(path.resolve("speed"));
                    return true;
                } catch (Exception e) {
                    // wireless nics don't report speed, ignore them.
                    return false;
                }
            }
            return false;
        } catch (IOException e) {
            LOG.error("Failed to read link target for NIC " + path, e);
            return false;
        }
    }

    private Path getNicSpeedPath(String nic) {
        return Paths.get(String.format("/sys/class/net/%s/speed", nic));
    }

    private double getTotalNicLimitKbps(List<String> nics) {
        // Nic speed is in Mbits/s, return kbits/s
        return nics.stream().mapToDouble(s -> {
            try {
                return Double.parseDouble(new String(Files.readAllBytes(getNicSpeedPath(s))));
            } catch (IOException e) {
                LOG.error("Failed to read speed for nic " + s, e);
                return 0d;
            }
        }).sum() * 1024;
    }

    private Path getNicTxPath(String nic) {
        return Paths.get(String.format("/sys/class/net/%s/statistics/tx_bytes", nic));
    }

    private Path getNicRxPath(String nic) {
        return Paths.get(String.format("/sys/class/net/%s/statistics/rx_bytes", nic));
    }

    private double getTotalNicUsageRxKb(List<String> nics) {
        return nics.stream().mapToDouble(s -> {
            try {
                return Double.parseDouble(new String(Files.readAllBytes(getNicRxPath(s))));
            } catch (IOException e) {
                LOG.error("Failed to read rx_bytes for NIC " + s, e);
                return 0d;
            }
        }).sum() * 8 / 1024;
    }

    private double getTotalNicUsageTxKb(List<String> nics) {
        return nics.stream().mapToDouble(s -> {
            try {
                return Double.parseDouble(new String(Files.readAllBytes(getNicTxPath(s))));
            } catch (IOException e) {
                LOG.error("Failed to read tx_bytes for NIC " + s, e);
                return 0d;
            }
        }).sum() * 8 / 1024;
    }

    private class CpuStat {
        private long totalTime;
        private long usage;

        CpuStat(long totalTime, long usage) {
            this.totalTime = totalTime;
            this.usage = usage;
        }

        long getTotalTime() {
            return totalTime;
        }

        long getUsage() {
            return usage;
        }
    }
}
