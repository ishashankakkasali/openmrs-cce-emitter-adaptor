package org.openphc.cce.emitter.service;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Condition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Keeps {@code Condition.clinicalStatus} within its FHIR R4 required value set
 * (http://hl7.org/fhir/ValueSet/condition-clinical: active, recurrence, relapse, inactive,
 * remission, resolved).
 *
 * <p>The OpenMRS fhir2 module sends {@code clinicalStatus = unknown} for an encounter diagnosis
 * whose clinical status was never recorded, and {@code unknown} is not an R4 code. R4 allows an
 * encounter diagnosis to carry no clinical status (invariant con-3 only expects one on a
 * problem-list item), so the element is removed rather than guessed.
 *
 * <p>Example: an encounter-diagnosis Condition "Diabetes mellitus" with {@code clinicalStatus =
 * condition-clinical|unknown} and {@code verificationStatus = confirmed} is forwarded with
 * verificationStatus only.
 */
@Service
public class ConditionClinicalStatusNormalizer {

    private static final Logger log = LoggerFactory.getLogger(ConditionClinicalStatusNormalizer.class);
    private static final String CLINICAL_STATUS_SYSTEM = "http://terminology.hl7.org/CodeSystem/condition-clinical";
    private static final Set<String> R4_CLINICAL_STATUS_CODES =
            Set.of("active", "recurrence", "relapse", "inactive", "remission", "resolved");

    public void normalize(IBaseResource resource) {
        if (!(resource instanceof Condition condition) || !condition.hasClinicalStatus()) return;
        boolean hasR4Code = condition.getClinicalStatus().getCoding().stream()
                .anyMatch(coding -> CLINICAL_STATUS_SYSTEM.equals(coding.getSystem())
                        && R4_CLINICAL_STATUS_CODES.contains(coding.getCode()));
        if (!hasR4Code) {
            condition.setClinicalStatus(null);
            log.debug("Removed non-R4 clinicalStatus from Condition/{}", condition.getIdPart());
        }
    }
}
