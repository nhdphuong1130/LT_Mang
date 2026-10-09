package vn.edu.ltm.agent.alert;

import vn.edu.ltm.common.model.RiskEvent;

@FunctionalInterface
public interface AlertSink { void show(RiskEvent event); }
