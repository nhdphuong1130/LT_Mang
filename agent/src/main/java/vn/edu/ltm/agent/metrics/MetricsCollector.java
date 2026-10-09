package vn.edu.ltm.agent.metrics;

import vn.edu.ltm.common.model.SystemMetrics;

@FunctionalInterface
public interface MetricsCollector { SystemMetrics collect(); }
