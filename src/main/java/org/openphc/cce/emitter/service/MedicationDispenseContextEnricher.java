package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.MedicationDispense;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sets {@code MedicationDispense.context} — R4: "the encounter or episode of care that establishes
 * the context for this event" — which fhir2 leaves out, to the visit the dispense belongs to.
 *
 * <p>This OpenMRS has no REST resource for a dispense, so the visit is read through the prescription
 * the dispense is made against ({@code authorizingPrescription}, an OpenMRS drug order): the order's
 * encounter's visit. fhir2 publishes an OpenMRS visit as an Encounter with the same uuid, so the
 * context is a resolvable {@code Encounter} reference. Example: a dispense against
 * MedicationRequest/150f1c6d-… ordered in a visit 8c5520d3-… gets {@code context = {reference:
 * "Encounter/8c5520d3-…", type: "Encounter"}}.
 */
@Service
@RequiredArgsConstructor
public class MedicationDispenseContextEnricher {

    private static final Logger log = LoggerFactory.getLogger(MedicationDispenseContextEnricher.class);

    private final OpenmrsRestReader openmrsRestReader;

    public void enrich(IBaseResource resource) {
        if (!(resource instanceof MedicationDispense dispense) || dispense.hasContext()) return;
        if (!dispense.hasAuthorizingPrescription()) return;
        String prescriptionUuid = dispense.getAuthorizingPrescriptionFirstRep().getReferenceElement().getIdPart();
        if (prescriptionUuid == null) return;
        try {
            JsonNode order = openmrsRestReader.get("/order/" + prescriptionUuid + "?v=custom:(encounter:(visit:(uuid)))");
            String visitUuid = order == null ? null : order.path("encounter").path("visit").path("uuid").asText(null);
            if (visitUuid != null && !visitUuid.isBlank()) {
                dispense.setContext(new Reference("Encounter/" + visitUuid).setType("Encounter"));
            }
        } catch (Exception e) {
            log.debug("Could not read the visit of MedicationDispense/{}: {}", dispense.getIdPart(), e.getMessage());
        }
    }
}
