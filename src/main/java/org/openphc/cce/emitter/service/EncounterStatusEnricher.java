package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Encounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Replaces {@code Encounter.status = unknown}, which fhir2 sends for every OpenMRS encounter and
 * visit, with the R4 status the visit actually has: {@code finished} once the visit has a stop
 * time, {@code in-progress} while it is open.
 *
 * <p>The visit is the encounter itself when fhir2 sends an OpenMRS visit as an Encounter (no
 * {@code partOf}), otherwise the encounter's {@code partOf}. Example: a "Referral Out" encounter
 * with {@code partOf = Encounter/8c5520d3-…} whose visit has no stopDatetime → {@code status =
 * in-progress}. When the visit cannot be read, the status is left as is.
 */
@Service
@RequiredArgsConstructor
public class EncounterStatusEnricher {

    private static final Logger log = LoggerFactory.getLogger(EncounterStatusEnricher.class);

    private final OpenmrsRestReader openmrsRestReader;

    public void enrich(IBaseResource resource) {
        if (!(resource instanceof Encounter encounter)) return;
        if (encounter.hasStatus() && encounter.getStatus() != Encounter.EncounterStatus.UNKNOWN) return;
        String visitUuid = encounter.hasPartOf()
                ? encounter.getPartOf().getReferenceElement().getIdPart()
                : encounter.getIdPart();
        if (visitUuid == null) return;
        try {
            JsonNode visit = openmrsRestReader.get("/visit/" + visitUuid + "?v=custom:(uuid,stopDatetime)");
            if (visit == null || !visit.hasNonNull("uuid")) return;
            boolean ended = visit.hasNonNull("stopDatetime") && !visit.get("stopDatetime").asText().isBlank();
            encounter.setStatus(ended ? Encounter.EncounterStatus.FINISHED : Encounter.EncounterStatus.INPROGRESS);
        } catch (Exception e) {
            log.debug("Could not read visit {} for Encounter/{} status: {}", visitUuid, encounter.getIdPart(), e.getMessage());
        }
    }
}
