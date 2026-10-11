package org.unicam.intermediate.listener;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.bpmn.listener.DelegateExpressionExecutionListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.ConditionalEventBehavior;
import org.camunda.bpm.engine.impl.bpmn.parser.ConditionalEventDefinition;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.el.ExpressionManager;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityBehavior;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.unicam.intermediate.activity.GuardedSendTaskActivity;
import org.unicam.intermediate.activity.WaitStateActivity;
import org.unicam.intermediate.service.environmental.EnvironmentalTaskRegistry;
import org.unicam.intermediate.service.participant.ParticipantService;
import org.unicam.intermediate.utils.Constants;

import java.util.Collections;

import static org.unicam.intermediate.utils.Constants.*;

@Component
@Slf4j
public class DynamicParseListener extends AbstractBpmnParseListener {

    private final EnvironmentalTaskRegistry environmentalTaskRegistry;
    private final ParticipantService participantService;

    public DynamicParseListener(@Lazy EnvironmentalTaskRegistry environmentalTaskRegistry,
                                @Lazy ParticipantService participantService) {
        this.environmentalTaskRegistry = environmentalTaskRegistry;
        this.participantService = participantService;
    }

    @Override
    public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
        try {
            ExecutionListener listener = createDelegateExpressionListener(genericTaskExecutionListenerBeanName);
            if (listener != null) {
                processDefinition.addListener(ExecutionListener.EVENTNAME_START, listener);
                log.info("[DynamicParseListener] Attached generic execution listener to the START of process '{}'", processDefinition.getId());
            }
        } catch (Exception e) {
            log.error("[DynamicParseListener] Failed to attach generic listener to the start of process '{}': {}",
                    processDefinition.getId(), e.getMessage(), e);
        }
    }

    /**
     * Ensures early initialization on BPMN Start Events.
     */
    @Override
    public void parseStartEvent(Element startEventElement, ScopeImpl scope, ActivityImpl activity) {
        addDiscordanceCheckListener(activity);
        addGenericTaskListener(activity);
        log.info("[DynamicParseListener] Configured start event '{}' with generic listener for early attribute initialization", activity.getId());
    }

    @Override
    public void parseTask(Element taskElement, ScopeImpl scope, ActivityImpl activity) {
        // Add the global discordance check listener to ALL tasks
        addDiscordanceCheckListener(activity);

        Element extensions = taskElement.element("extensionElements");
        if (extensions == null) {
            log.debug("[DynamicParseListener] No extensions found for activity: {}", activity.getId());
            return;
        }

        Element typeElement = extensions.elementNS(Constants.SPACE_NS, "type");
        Element guardElement = extensions.elementNS(Constants.SPACE_NS, "guard");

        // Base BPMN task with guard and no explicit space:type: evaluate guard with dedicated listener.
        if (typeElement == null) {
            if (guardElement == null || guardElement.getText() == null || guardElement.getText().trim().isEmpty()) {
                log.debug("[DynamicParseListener] No space:type/space:guard found for activity: {}", activity.getId());
                return;
            }

            try {
                activity.setActivityBehavior(new WaitStateActivity());

                ExpressionManager exprMgr = Context.getProcessEngineConfiguration().getExpressionManager();
                String exprString = "${" + genericTaskExecutionListenerBeanName + "}";
                var expression = exprMgr.createExpression(exprString);

                ExecutionListener listener = new DelegateExpressionExecutionListener(expression, Collections.emptyList());
                activity.addListener(ExecutionListener.EVENTNAME_START, listener);
                activity.addListener(ExecutionListener.EVENTNAME_END, listener);

                log.info("[DynamicParseListener] Configured base BPMN task '{}' with guard listener ${{{}}}",
                        activity.getId(), genericTaskExecutionListenerBeanName);
            } catch (Exception e) {
                log.error("[DynamicParseListener] Failed to configure base BPMN guard listener for activity '{}': {}",
                        activity.getId(), e.getMessage(), e);
            }
            return;
        }

        String typeValue = typeElement.getText();
        if (typeValue == null || typeValue.trim().isEmpty()) {
            log.warn("[DynamicParseListener] Empty space:type found for activity: {}", activity.getId());
            return;
        }

        typeValue = typeValue.trim();
        String listenerBeanName = getListenerBeanName(typeValue);

        if (listenerBeanName == null) {
            log.warn("[DynamicParseListener] No listener configured for task type '{}' on activity '{}'",
                    typeValue, activity.getId());
            return;
        }

        try {
            // Set wait state behavior for all dynamic task types
            activity.setActivityBehavior(new WaitStateActivity());

            // Attach domain-specific execution listener for START and END events
            ExecutionListener domainListener = createDelegateExpressionListener(listenerBeanName);
            if (domainListener != null) {
                activity.addListener(ExecutionListener.EVENTNAME_START, domainListener);
                activity.addListener(ExecutionListener.EVENTNAME_END, domainListener);
            }

            // Attach generic task listener for Data Objects and Attribute management
            addGenericTaskListener(activity);

            log.info("[DynamicParseListener] Configured activity '{}' with type '{}' and generic listener",
                    activity.getId(), typeValue);

        } catch (Exception e) {
            log.error("[DynamicParseListener] Failed to configure task type '{}' for activity '{}': {}",
                    typeValue, activity.getId(), e.getMessage(), e);
        }
    }

    /**
     * Configures BPMN User Tasks with discordance checks and generic listeners.
     */
    @Override
    public void parseUserTask(Element userTaskElement, ScopeImpl scope, ActivityImpl activity) {
        addDiscordanceCheckListener(activity);
        addGenericTaskListener(activity);
        log.info("[DynamicParseListener] Configured user task '{}' with generic listener", activity.getId());
    }

    @Override
    public void parseSendTask(Element sendTaskElement, ScopeImpl scope, ActivityImpl activity) {
        addDiscordanceCheckListener(activity);

        Element extensions = sendTaskElement.element("extensionElements");
        if (extensions == null) {
            return;
        }

        Element guardElement = extensions.elementNS(Constants.SPACE_NS, "guard");
        if (guardElement == null || guardElement.getText() == null || guardElement.getText().trim().isEmpty()) {
            return;
        }

        ActivityBehavior currentBehavior = activity.getActivityBehavior();
        if (currentBehavior == null) {
            log.warn("[DynamicParseListener] Send task '{}' has guard but no activity behavior to wrap", activity.getId());
            return;
        }

        activity.setActivityBehavior(new GuardedSendTaskActivity(
                currentBehavior,
                environmentalTaskRegistry,
                participantService
        ));

        log.info("[DynamicParseListener] Configured guarded send task '{}' with wait-for-guard behavior",
                activity.getId());
    }

    @Override
    public void parseExclusiveGateway(Element exclusiveGwElement, ScopeImpl scope, ActivityImpl activity) {
        try {
            activity.setActivityBehavior(new WaitStateActivity());

            ExpressionManager exprMgr = Context.getProcessEngineConfiguration().getExpressionManager();
            String exprString = "${" + exclusiveGatewayExecutionListenerBeanName + "}";
            var expression = exprMgr.createExpression(exprString);

            ExecutionListener listener = new DelegateExpressionExecutionListener(expression, Collections.emptyList());
            activity.addListener(ExecutionListener.EVENTNAME_START, listener);
            activity.addListener(ExecutionListener.EVENTNAME_END, listener);

            log.debug("[DynamicParseListener] Configured exclusive gateway '{}' with wait-state listener ${{{}}}",
                    activity.getId(), exclusiveGatewayExecutionListenerBeanName);
        } catch (Exception e) {
            log.error("[DynamicParseListener] Failed to configure exclusive gateway '{}': {}",
                    activity.getId(), e.getMessage(), e);
        }
    }

    @Override
    public void parseIntermediateThrowEvent(Element intermediateEventElement, ScopeImpl scope, ActivityImpl activity) {
        if (hasMessageEventDefinition(intermediateEventElement)) {
            addMessageThrowListener(activity);
        }
    }

    @Override
    public void parseIntermediateCatchEvent(Element intermediateEventElement, ScopeImpl scope, ActivityImpl activity) {
        Element conditionalEventDefinitionElement = intermediateEventElement.element("conditionalEventDefinition");

        if (conditionalEventDefinitionElement != null) {
            Element conditionElement = conditionalEventDefinitionElement.element("condition");
            if (conditionElement != null) {
                String originalExpression = conditionElement.getText();

                if (originalExpression != null && !originalExpression.trim().isEmpty()
                        && originalExpression.contains("myPlace()")) {
                    try {
                        String wrappedExpression = String.format(
                                "${conditionalEventGuardEvaluator.evaluate(execution, '%s')}",
                                originalExpression.replace("'", "\\'")
                        );

                        ExpressionManager exprMgr = Context.getProcessEngineConfiguration().getExpressionManager();
                        var expression = exprMgr.createExpression(wrappedExpression);
                        var newCondition = new org.camunda.bpm.engine.impl.el.UelExpressionCondition(expression);

                        ConditionalEventDefinition conditionalEventDefinition =
                                ((ConditionalEventBehavior) activity.getActivityBehavior())
                                        .getConditionalEventDefinition();
                        java.lang.reflect.Field conditionField = ConditionalEventDefinition.class
                                .getDeclaredField("condition");
                        conditionField.setAccessible(true);
                        conditionField.set(conditionalEventDefinition, newCondition);

                        log.info("[DynamicParseListener] Riscritta condizione per il Conditional Catch Event '{}': {} -> {}",
                                activity.getId(), originalExpression, wrappedExpression);
                    } catch (Exception e) {
                        log.error("[DynamicParseListener] Errore durante la riscrittura del Conditional Event per l'attività '{}': {}",
                                activity.getId(), e.getMessage(), e);
                    }
                }
            }
        }
    }

    @Override
    public void parseEndEvent(Element endEventElement, ScopeImpl scope, ActivityImpl activity) {
        if (hasMessageEventDefinition(endEventElement)) {
            addMessageThrowListener(activity);
        }
    }

    private String getListenerBeanName(String typeValue) {
        return switch (typeValue.toLowerCase()) {
            case "movement" -> movementExecutionListenerBeanName;
            case "binding" -> bindingExecutionListenerBeanName;
            case "unbinding" -> unbindingExecutionListenerBeanName;
            case "environmental" -> environmentalExecutionListenerBeanName;
            default -> {
                log.warn("[DynamicParseListener] Unknown task type: {}", typeValue);
                yield null;
            }
        };
    }

    /**
     * Add the global discordance check listener to a task.
     * This listener checks if there are bound participants with discordant positions
     * and raises a BpmnError if the grace window has expired.
     */
    private void addDiscordanceCheckListener(ActivityImpl activity) {
        try {
            ExpressionManager exprMgr = Context.getProcessEngineConfiguration().getExpressionManager();
            String exprString = "${" + discordanceCheckExecutionListenerBeanName + "}";
            var expression = exprMgr.createExpression(exprString);

            ExecutionListener listener = new DelegateExpressionExecutionListener(expression, Collections.emptyList());
            activity.addListener(ExecutionListener.EVENTNAME_START, listener);

            log.debug("[DynamicParseListener] Added global discordance check listener to task '{}'", 
                    activity.getId());
        } catch (Exception e) {
            log.error("[DynamicParseListener] Failed to add discordance check listener to task '{}': {}",
                    activity.getId(), e.getMessage(), e);
        }
    }

    private boolean hasMessageEventDefinition(Element eventElement) {
        return eventElement != null && eventElement.element("messageEventDefinition") != null;
    }

    private void addMessageThrowListener(ActivityImpl activity) {
        try {
            ExpressionManager exprMgr = Context.getProcessEngineConfiguration().getExpressionManager();
            String exprString = "${" + messageThrowExecutionListenerBeanName + "}";
            var expression = exprMgr.createExpression(exprString);

            ExecutionListener listener = new DelegateExpressionExecutionListener(expression, Collections.emptyList());
            activity.addListener(ExecutionListener.EVENTNAME_START, listener);

            log.debug("[DynamicParseListener] Added message throw listener to event '{}'", activity.getId());
        } catch (Exception e) {
            log.error("[DynamicParseListener] Failed to add message throw listener to event '{}': {}",
                    activity.getId(), e.getMessage(), e);
        }
    }

    /**
     * Adds the generic task listener to an activity for both START and END events.
     */
    private void addGenericTaskListener(ActivityImpl activity) {
        try {
            ExecutionListener listener = createDelegateExpressionListener(genericTaskExecutionListenerBeanName);
            if (listener != null) {
                activity.addListener(ExecutionListener.EVENTNAME_START, listener);
                activity.addListener(ExecutionListener.EVENTNAME_END, listener);
            }
        } catch (Exception e) {
            log.error("[DynamicParseListener] Failed to add generic listener to activity '{}': {}",
                    activity.getId(), e.getMessage(), e);
        }
    }

    /**
     * Helper method to dynamically construct a {@link DelegateExpressionExecutionListener} instance.
     */
    private ExecutionListener createDelegateExpressionListener(String beanName) {
        if (beanName == null || beanName.isBlank()) {
            return null;
        }

        if (Context.getProcessEngineConfiguration() == null) {
            log.warn("[DynamicParseListener] ProcessEngineConfiguration is null when resolving bean '{}'", beanName);
            return null;
        }

        ExpressionManager exprMgr = Context.getProcessEngineConfiguration().getExpressionManager();
        if (exprMgr == null) {
            log.warn("[DynamicParseListener] ExpressionManager is null when resolving bean '{}'", beanName);
            return null;
        }

        var expression = exprMgr.createExpression("${" + beanName + "}");
        return new DelegateExpressionExecutionListener(expression, Collections.emptyList());
    }
}