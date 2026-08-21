package com.doob.mathagent.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InfrastructureDependenciesHealthIndicatorTest {

    @Test
    void reportsUpWhenAllRequiredDependenciesAreAvailable() {
        InfrastructureDependenciesHealthIndicator indicator = new InfrastructureDependenciesHealthIndicator(() -> true);

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    void reportsDownWhenARequiredDependencyIsUnavailable() {
        InfrastructureDependenciesHealthIndicator indicator = new InfrastructureDependenciesHealthIndicator(() -> false);

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void reportsDownWhenReadinessAggregationFails() {
        InfrastructureDependenciesHealthIndicator indicator = new InfrastructureDependenciesHealthIndicator(
                () -> {
                    throw new IllegalStateException("probe failed");
                });

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
    }
}
