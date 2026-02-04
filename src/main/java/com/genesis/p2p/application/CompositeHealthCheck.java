package com.genesis.p2p.application;

import java.util.*; /**
 * Composite health check - aggregates multiple checks.
 */
public class CompositeHealthCheck implements HealthCheckStrategy {
    private final String name;
    private final Map<String, HealthCheckStrategy> childChecks;

    public CompositeHealthCheck(String name) {
        this.name = name;
        this.childChecks = new LinkedHashMap<>();
    }

    public void addCheck(String name, HealthCheckStrategy check) {
        childChecks.put(name, check);
    }

    @Override
    public HealthResult check() {
        if (childChecks.isEmpty()) {
            return HealthResult.healthy("No child checks");
        }

        HealthStatus worstStatus = HealthStatus.HEALTHY;
        Map<String, HealthResult> childResults = new HashMap<>();
        List<String> issues = new ArrayList<>();

        for (Map.Entry<String, HealthCheckStrategy> entry : childChecks.entrySet()) {
            String checkName = entry.getKey();
            HealthCheckStrategy strategy = entry.getValue();

            try {
                HealthResult result = strategy.check();
                childResults.put(checkName, result);

                // Track worst status
                if (result.getStatus().ordinal() > worstStatus.ordinal()) {
                    worstStatus = result.getStatus();
                }

                // Collect issues
                if (result.getStatus() != HealthStatus.HEALTHY) {
                    issues.add(checkName + ": " + result.getMessage());
                }
            } catch (Exception e) {
                worstStatus = HealthStatus.UNHEALTHY;
                issues.add(checkName + ": Exception - " + e.getMessage());
            }
        }

        String message = issues.isEmpty()
                ? "All checks passed"
                : String.join("; ", issues);

        HealthResult result;
        switch (worstStatus) {
            case UNHEALTHY:
                result = HealthResult.unhealthy(message);
                break;
            case DEGRADED:
                result = HealthResult.degraded(message);
                break;
            default:
                result = HealthResult.healthy(message);
                break;
        }

        // Add child results as details
        childResults.forEach((key, value) ->
                result.withDetail(key, value.getStatus()));

        return result;
    }
}
