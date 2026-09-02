package dev.mars.peegeeq.cache.rest.server;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

/** Disables browser methods that do not own an explicitly selected scenario. */
public final class ManagementBrowserSelectionCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("No incompatible browser scenario selection");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Set<String> requested = ManagementBrowserSelection.requestedScenarioIds();
        if (requested.isEmpty()) return ENABLED;

        Optional<Method> method = context.getTestMethod();
        if (method.isEmpty()) return ENABLED;
        ManagementBrowserScenario scenario = method.orElseThrow()
                .getAnnotation(ManagementBrowserScenario.class);
        if (scenario != null) {
            return requested.contains(scenario.id())
                    ? ENABLED
                    : ConditionEvaluationResult.disabled("Scenario " + scenario.id() + " was not selected");
        }
        if (method.orElseThrow().isAnnotationPresent(ParameterizedTest.class)
                && !ManagementBrowserSelection.classHasRequestedScenario(context.getRequiredTestClass())) {
            return ConditionEvaluationResult.disabled("No selected scenario belongs to this catalogue");
        }
        return ENABLED;
    }
}
