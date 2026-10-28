package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Quantity;
import org.springframework.stereotype.Service;

/**
 * Gives every coding fhir2 sends without a {@code system} the system it belongs to. fhir2 codes
 * OpenMRS concepts by concept uuid with no system (e.g. {@code {code:
 * "119481AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", display: "Diabetes mellitus"}}), and R4 needs a system to
 * know what a code means — for a Quantity it is mandatory once a code is present (invariant qty-3).
 *
 * <ul>
 *   <li>{@code Identifier.type} codings (an OpenMRS identifier-type uuid) get {@value #IDENTIFIER_TYPE_SYSTEM};</li>
 *   <li>every other system-less coding, and a Quantity's unit code, gets {@value #CONCEPT_SYSTEM}.</li>
 * </ul>
 * Codings that already carry a system (CIEL, SNOMED, LOINC, HL7) are left untouched.
 */
@Service
@RequiredArgsConstructor
public class CodingSystemNormalizer {

    static final String CONCEPT_SYSTEM = "urn:openmrs:concept";
    static final String IDENTIFIER_TYPE_SYSTEM = "urn:openmrs:patient-identifier-type";

    private final FhirContext fhirContext;

    public void normalize(IBaseResource resource) {
        if (resource == null) return;
        var terser = fhirContext.newTerser();
        for (Identifier identifier : terser.getAllPopulatedChildElementsOfType(resource, Identifier.class)) {
            if (identifier.hasType()) {
                identifier.getType().getCoding().stream()
                        .filter(coding -> coding.hasCode() && !coding.hasSystem())
                        .forEach(coding -> coding.setSystem(IDENTIFIER_TYPE_SYSTEM));
            }
        }
        for (Coding coding : terser.getAllPopulatedChildElementsOfType(resource, Coding.class)) {
            if (coding.hasCode() && !coding.hasSystem()) {
                coding.setSystem(CONCEPT_SYSTEM);
            }
        }
        for (Quantity quantity : terser.getAllPopulatedChildElementsOfType(resource, Quantity.class)) {
            if (quantity.hasCode() && !quantity.hasSystem()) {
                quantity.setSystem(CONCEPT_SYSTEM);
            }
        }
    }
}
