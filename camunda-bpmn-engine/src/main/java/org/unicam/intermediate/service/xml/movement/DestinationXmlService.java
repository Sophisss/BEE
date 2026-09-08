package org.unicam.intermediate.service.xml.movement;

import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import org.unicam.intermediate.models.enums.ExtendedElementTaskType;
import org.unicam.intermediate.models.enums.TaskType;
import org.unicam.intermediate.service.xml.AbstractXmlService;

import java.util.List;
import java.util.Map;

import static org.unicam.intermediate.utils.Constants.SPACE_NS;

@Slf4j
@Component
public class DestinationXmlService extends AbstractXmlService {

    public DestinationXmlService() {
        super(ExtendedElementTaskType.DESTINATION, SPACE_NS.getNamespaceUri());
    }

    @Override
    public TaskType getTypeKey() {
        return TaskType.MOVEMENT;
    }

    @Override
    public String getNamespaceUri() {
        return namespaceUri;
    }

    @Override
    public ExtendedElementTaskType getLocalName() {
        return localName;
    }

    @Override
    public String extractRaw(DelegateExecution execution) {
        String rawXmlValue = super.extractRaw(execution);

        String settingsName = resolveSettingsName(rawXmlValue);
        Object settingsObj = execution.getVariable(settingsName);
        if (settingsObj instanceof Map<?, ?> settings) {
            Object targetIdsObj = settings.get("targetPlaceIds");
            if (targetIdsObj instanceof List<?> targetIds && !targetIds.isEmpty()) {
                String dynamicDestination = targetIds.get(0).toString();
                log.info("[DestinationXmlService] Dynamically resolved destination from {}[0] on {} -> '{}' (raw XML was '{}')",
                        settingsName, execution.getCurrentActivityId(), dynamicDestination, rawXmlValue);
                return dynamicDestination;
            }
        }

        log.debug("[DestinationXmlService] Fallback to raw XML on {} → {}",
                execution.getCurrentActivityId(), rawXmlValue);
        return rawXmlValue;
    }

    @Override
    public void patchInstanceValue(DelegateExecution execution, String newValue) {
        super.patchInstanceValue(execution, newValue);
        log.info("[DestinationXmlService] patched <space:destination>='{}' on {}",
                newValue, execution.getCurrentActivityId());
    }

    @Override
    public void restoreInstanceValue(DelegateExecution execution, String rawValue) {
        super.restoreInstanceValue(execution, rawValue);
        log.info("[DestinationXmlService] restored <space:destination>='{}' on {}",
                rawValue, execution.getCurrentActivityId());
    }

    private String resolveSettingsName(String rawXmlValue) {
        if (rawXmlValue != null && rawXmlValue.contains("Guided_Tour_Settings")) {
            return "Guided_Tour_Settings";
        }
        return "Tour_settings";
    }
}