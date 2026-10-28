package org.openphc.cce.emitter.service;

import lombok.RequiredArgsConstructor;
import org.hl7.fhir.instance.model.api.IBaseCoding;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.CareSettingConfig;
import org.springframework.stereotype.Service;

/**
 * Stamps every outgoing resource with a {@code meta.tag} naming the care setting this OpenMRS
 * instance serves (e.g. {@code care-setting|cluster-hospital}).
 *
 * <p>CCE trigger conditions only see the FHIR resource, not the CloudEvent source, so when
 * several EMRs send resources with the same codes (an HbA1c Observation from the PHC and from the
 * hospital) the tag is what lets a PlanDefinition condition attribute an event to a journey stage.
 */
@Service
@RequiredArgsConstructor
public class CareSettingTagEnricher {

    private final EmitterProperties properties;

    public void enrich(IBaseResource resource) {
        if (resource == null) return;
        CareSettingConfig cfg = properties.getCareSetting();
        if (cfg == null || !cfg.isEnabled() || isBlank(cfg.getSystem()) || isBlank(cfg.getCode())) return;

        for (IBaseCoding tag : resource.getMeta().getTag()) {
            if (cfg.getSystem().equals(tag.getSystem()) && cfg.getCode().equals(tag.getCode())) return;
        }
        IBaseCoding tag = resource.getMeta().addTag()
                .setSystem(cfg.getSystem())
                .setCode(cfg.getCode());
        if (!isBlank(cfg.getDisplay())) {
            tag.setDisplay(cfg.getDisplay());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
