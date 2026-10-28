package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.OpenmrsConfig;
import org.openphc.cce.emitter.config.EmitterProperties.ReferralDirectionConfig;
import org.openphc.cce.emitter.config.EmitterProperties.ReferralResponseConfig;
import org.openphc.cce.emitter.config.EmitterProperties.TestOrderCategoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Re-injects OpenMRS order metadata that the fhir2 module strips from ServiceRequest /
 * MedicationRequest payloads — namely {@code orderNumber} (e.g. {@code ORD-395}) and
 * {@code accessionNumber} — by querying the OpenMRS REST API
 * ({@code /ws/rest/v1/order/{uuid}}) and adding them as {@code identifier[]} entries on the
 * outgoing FHIR resource.
 *
 * <p>Mapping used follows the HL7 v2-0203 identifier-type code:
 * <ul>
 *   <li>{@code accessionNumber} → identifier with {@code type.coding.code = "ACSN"} (Accession)</li>
 * </ul>
 *
 * <p>Lookups are cached in-memory with TTL because OpenMRS order numbers and accession numbers
 * are immutable for a given order UUID.
 */
@Service
@RequiredArgsConstructor
public class OrderIdentifierEnricher {

    private static final Logger log = LoggerFactory.getLogger(OrderIdentifierEnricher.class);
    private static final String METRIC_LOOKUP = "openmrs.emitter.order.identifier.lookup";

    private static final String IDENTIFIER_TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v2-0203";
    private static final String OPENMRS_ACCESSION_NUMBER_SYSTEM = "urn:openmrs:accession-number";
    private static final long CACHE_TTL_SECONDS = 3600L;
    private static final int CACHE_MAX_SIZE = 10_000;

    private final EmitterProperties properties;
    private final RestTemplate restTemplate;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    /** Destination location uuid → performer reference; a location's name and facility code rarely change. */
    private final ConcurrentHashMap<String, DestinationEntry> destinationCache = new ConcurrentHashMap<>();

    /**
     * Adds OpenMRS order identifiers to the resource if it is a ServiceRequest or
     * MedicationRequest and the OpenMRS REST lookup succeeds. Failures are swallowed —
     * the resource is forwarded unchanged in that case.
     */
    public void enrich(IBaseResource resource) {
        if (resource == null) return;
        String type = resource.fhirType();
        if (!"ServiceRequest".equals(type) && !"MedicationRequest".equals(type)) return;

        String uuid = resource.getIdElement() != null ? resource.getIdElement().getIdPart() : null;
        if (uuid == null || uuid.isBlank()) return;

        OrderRefs refs = lookup(uuid);
        if (refs == null) return;

        if (resource instanceof ServiceRequest sr) {
            mergeIdentifier(sr.getIdentifier(), refs.accessionNumber(), "ACSN", "Accession ID",
                    OPENMRS_ACCESSION_NUMBER_SYSTEM, sr::addIdentifier);
            applyOrderFields(sr, refs);
            applyReferralResponseClassification(sr, refs);
            applyReferralDirection(sr, refs);
            applyTestOrderCategory(sr, refs);
        } else if (resource instanceof MedicationRequest mr) {
            mergeIdentifier(mr.getIdentifier(), refs.accessionNumber(), "ACSN", "Accession ID",
                    OPENMRS_ACCESSION_NUMBER_SYSTEM, mr::addIdentifier);
        }
    }

    /**
     * Fills the R4 ServiceRequest elements fhir2 leaves out, from the OpenMRS order, each only
     * when absent:
     * <ul>
     *   <li>{@code authoredOn} ("when the request transitioned to being actionable") — the order's
     *       {@code dateActivated};</li>
     *   <li>{@code priority} — the order's {@code urgency}: ROUTINE → routine, STAT → stat
     *       (ON_SCHEDULED_DATE has no R4 equivalent and is left out);</li>
     *   <li>{@code note} ("notes and comments made about the service request") — the order's
     *       {@code instructions}, e.g. instructions to the facility a referral is sent to.</li>
     * </ul>
     * It also empties {@code locationReference}, which fhir2 fills with the ordering location.
     */
    private void applyOrderFields(ServiceRequest sr, OrderRefs refs) {
        // fhir2 copies the ordering encounter's location into locationReference, but R4 defines it as
        // "the preferred location(s) where the procedure should actually happen"; that is only known for
        // a referral out (set from its destination below). The ordering facility is sent as the
        // source-facility extension instead (FacilityReferenceEnricher).
        sr.setLocationReference(new java.util.ArrayList<>());
        if (!sr.hasAuthoredOn() && refs.dateActivated() != null) {
            try {
                // OpenMRS REST writes the offset without a colon (…T09:01:09.000+0000); FHIR dateTime needs +00:00
                String fhirDateTime = refs.dateActivated().replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2");
                sr.setAuthoredOnElement(new org.hl7.fhir.r4.model.DateTimeType(fhirDateTime));
            } catch (Exception e) {
                log.debug("Unparseable dateActivated '{}' on order {}", refs.dateActivated(), sr.getIdPart());
            }
        }
        if (!sr.hasPriority() && refs.urgency() != null) {
            switch (refs.urgency()) {
                case "ROUTINE" -> sr.setPriority(ServiceRequest.ServiceRequestPriority.ROUTINE);
                case "STAT" -> sr.setPriority(ServiceRequest.ServiceRequestPriority.STAT);
                default -> { /* ON_SCHEDULED_DATE: no R4 priority */ }
            }
        }
        if (!sr.hasNote() && refs.instructions() != null && !refs.instructions().isBlank()) {
            sr.addNote(new Annotation().setText(refs.instructions()));
        }
    }

    /**
     * If the order is a referral and has reached a terminal status, classify the resource as a
     * referral response so that downstream PlanDefinition triggers can match on
     * {@code category} + {@code status} and resolve the placer order via {@code basedOn}.
     */
    private void applyReferralResponseClassification(ServiceRequest sr, OrderRefs refs) {
        ReferralResponseConfig cfg = properties.getReferral().getResponse();
        if (cfg == null || !cfg.isEnabled()) return;
        if (refs.orderTypeName() == null
                || !refs.orderTypeName().equalsIgnoreCase(cfg.getOrderTypeName())) {
            return;
        }
        ServiceRequest.ServiceRequestStatus status = sr.getStatus();
        if (status != ServiceRequest.ServiceRequestStatus.COMPLETED
                && status != ServiceRequest.ServiceRequestStatus.REVOKED) {
            return;
        }

        // 1) category coding — PlanDefinition action.trigger codeFilter target
        addCategoryIfAbsent(sr, cfg.getCategorySystem(), cfg.getCategoryCode(), cfg.getCategoryDisplay());

        // 2) basedOn — logical reference to the placer's ServiceRequest, sourced from the
        //    OpenMRS accessionNumber (which the placer system stamped with its own SR id).
        if (refs.accessionNumber() != null && !refs.accessionNumber().isBlank()) {
            addBasedOnIdentifierIfAbsent(sr, cfg.getBasedOnIdentifierSystem(), refs.accessionNumber());
        }

        // 3) intent flip — placer sent 'order'; fulfiller responds with 'filler-order'
        if (cfg.isFlipIntent() && sr.getIntent() == ServiceRequest.ServiceRequestIntent.ORDER) {
            sr.setIntent(ServiceRequest.ServiceRequestIntent.FILLERORDER);
        }

        // 4) Include full instructions text as orderDetail
        addInstructionsOrderDetail(sr, refs, cfg);
    }

    /**
     * Marks a referral ServiceRequest as inbound (created by the receiver adaptor in a
     * "Referral In" encounter) or outbound (placed by a clinician in this EMR, e.g. a downward
     * referral back to a PHC), whatever its status. An outbound referral also gets its
     * destination facility as {@code performer} (see {@link #applyReferralDestination}).
     */
    private void applyReferralDirection(ServiceRequest sr, OrderRefs refs) {
        ReferralDirectionConfig cfg = properties.getReferral().getDirection();
        if (cfg == null || !cfg.isEnabled()) return;
        String referralOrderType = properties.getReferral().getResponse().getOrderTypeName();
        if (refs.orderTypeName() == null || !refs.orderTypeName().equalsIgnoreCase(referralOrderType)) {
            return;
        }
        boolean inbound = cfg.getInboundEncounterTypeUuid() != null
                && cfg.getInboundEncounterTypeUuid().equals(refs.encounterTypeUuid());
        String code = inbound ? cfg.getInboundCode() : cfg.getOutboundCode();
        addCategoryIfAbsent(sr, cfg.getCategorySystem(), code, inbound ? "Inbound referral" : "Outbound referral");
        if (!inbound) {
            applyReferralDestination(sr, refs, cfg);
        }
    }

    /** Marks a test (lab) order with the configured category, whatever its status. */
    private void applyTestOrderCategory(ServiceRequest sr, OrderRefs refs) {
        TestOrderCategoryConfig cfg = properties.getTestOrderCategory();
        if (cfg == null || !cfg.isEnabled()) return;
        if (cfg.getOrderTypeUuid() == null || !cfg.getOrderTypeUuid().equals(refs.orderTypeUuid())) return;
        addCategoryIfAbsent(sr, cfg.getSystem(), cfg.getCode(), cfg.getDisplay());
    }

    /**
     * Sends the facility an outbound referral is referred to, per FHIR R4: {@code performer} is
     * "the desired performer for doing the requested service" (the receiving facility, as an
     * Organization) and {@code locationReference} is "the preferred location(s) where the
     * procedure should actually happen" (that facility's Location). {@code requester} stays the
     * referring clinician. The destination is the location recorded on the referral's encounter;
     * its facility code becomes the identifier of both references.
     *
     * <p>Example: a referral out to "Test PHC A — Kibera Health Centre" (facility code
     * TEST-PHC-001) gets {@code performer = [{reference: "Organization/TEST-PHC-001", type:
     * "Organization", identifier: {system: <destination-identifier-system>, value:
     * "TEST-PHC-001"}, display: "Test PHC A — Kibera Health Centre"}]} and {@code
     * locationReference = [{reference: "Location/<location uuid>", type: "Location", identifier:
     * {…TEST-PHC-001}, display: "Test PHC A — Kibera Health Centre"}]}. Both replace what fhir2
     * put there; with no destination recorded, neither is set.
     */
    private void applyReferralDestination(ServiceRequest sr, OrderRefs refs, ReferralDirectionConfig cfg) {
        if (refs.destinationLocationUuid() == null) return;
        Destination destination = lookupDestination(refs.destinationLocationUuid(), cfg);
        if (destination == null) return;
        sr.setPerformer(new java.util.ArrayList<>(List.of(destination.organization().copy())));
        sr.setLocationReference(new java.util.ArrayList<>(List.of(destination.location().copy())));
        log.debug("Set ServiceRequest/{} performer and locationReference to referral destination {}",
                sr.getIdPart(), destination.location().getDisplay());
    }

    private Destination lookupDestination(String locationUuid, ReferralDirectionConfig cfg) {
        Instant now = Instant.now();
        DestinationEntry cached = destinationCache.get(locationUuid);
        if (cached != null && cached.expiresAt.isAfter(now)) return cached.destination;
        try {
            OpenmrsConfig openmrs = properties.getOpenmrs();
            String url = stripTrailingSlash(openmrs.getBaseUrl()) + "/ws/rest/v1/location/" + locationUuid
                    + "?v=custom:(uuid,display,attributes:(attributeType:(uuid),value,voided))";
            JsonNode location = getRestJson(url, openmrs);
            if (location == null) return null;
            String facilityCode = null;
            for (JsonNode attribute : location.path("attributes")) {
                if (cfg.getFacilityCodeAttributeTypeUuid() != null
                        && cfg.getFacilityCodeAttributeTypeUuid().equals(attribute.path("attributeType").path("uuid").asText())
                        && !attribute.path("voided").asBoolean(false)) {
                    facilityCode = textOrNull(attribute, "value");
                }
            }
            String name = textOrNull(location, "display");
            Reference destinationLocation = new Reference("Location/" + locationUuid).setType("Location").setDisplay(name);
            Reference performer = new Reference().setType("Organization").setDisplay(name);
            if (facilityCode != null) {
                Identifier facilityIdentifier = new Identifier().setSystem(cfg.getDestinationIdentifierSystem()).setValue(facilityCode);
                performer.setReference("Organization/" + facilityCode).setIdentifier(facilityIdentifier);
                destinationLocation.setIdentifier(facilityIdentifier.copy());
            } else {
                // no facility code: the organization is known by name only
                log.warn("Referral destination location {} has no facility code", locationUuid);
            }
            Destination destination = new Destination(performer, destinationLocation);
            destinationCache.put(locationUuid, new DestinationEntry(destination, now.plus(Duration.ofSeconds(CACHE_TTL_SECONDS))));
            return destination;
        } catch (Exception e) {
            log.warn("Failed to resolve referral destination location {}: {}", locationUuid, e.getMessage());
            return null;
        }
    }

    private JsonNode getRestJson(String url, OpenmrsConfig openmrs) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String authHeader = authService.resolveOpenmrsAuthHeader(openmrs.getAuth());
        if (authHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        }
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        String body = response.getBody();
        return body == null || body.isBlank() ? null : objectMapper.readTree(body);
    }

    /**
     * Adds the full order instructions text as a coded {@code orderDetail} entry on the
     * ServiceRequest so downstream consumers can read referral context directly.
     */
    private void addInstructionsOrderDetail(ServiceRequest sr, OrderRefs refs, ReferralResponseConfig cfg) {
        if (!cfg.isIncludeInstructions()) return;
        if (refs.instructions() == null || refs.instructions().isBlank()) return;

        String system = cfg.getInstructionsSystem();
        String code = cfg.getInstructionsCode();
        String instructions = refs.instructions();

        // Idempotency check — skip if an orderDetail with same system/code and text already exists
        for (CodeableConcept cc : sr.getOrderDetail()) {
            if (cc == null) continue;
            for (Coding c : cc.getCoding()) {
                if (system.equals(c.getSystem()) && code.equals(c.getCode())
                        && instructions.equals(cc.getText())) {
                    return;
                }
            }
        }

        sr.addOrderDetail(new CodeableConcept()
                .addCoding(new Coding().setSystem(system).setCode(code).setDisplay(cfg.getInstructionsDisplay()))
                .setText(instructions));
        log.debug("Added referral instructions orderDetail to ServiceRequest/{}", sr.getIdPart());
    }

    private void addCategoryIfAbsent(ServiceRequest sr, String system, String code, String display) {
        if (system == null || code == null) return;
        for (CodeableConcept cc : sr.getCategory()) {
            if (cc == null) continue;
            for (Coding c : cc.getCoding()) {
                if (system.equals(c.getSystem()) && code.equals(c.getCode())) return;
            }
        }
        sr.addCategory(new CodeableConcept().addCoding(
                new Coding().setSystem(system).setCode(code).setDisplay(display)));
    }

    private void addBasedOnIdentifierIfAbsent(ServiceRequest sr, String system, String value) {
        if (system == null) return;
        for (Reference ref : sr.getBasedOn()) {
            Identifier id = ref != null ? ref.getIdentifier() : null;
            if (id != null && system.equals(id.getSystem()) && value.equals(id.getValue())) return;
        }
        sr.addBasedOn(new Reference()
                .setType("ServiceRequest")
                .setIdentifier(new Identifier().setSystem(system).setValue(value)));
    }

    private void mergeIdentifier(List<Identifier> existing, String value, String typeCode,
                                 String typeDisplay, String system,
                                 java.util.function.Supplier<Identifier> adder) {
        if (value == null || value.isBlank()) return;
        // Skip if an identifier with the same system+value already exists (idempotent).
        for (Identifier id : existing) {
            if (id != null && system.equals(id.getSystem()) && value.equals(id.getValue())) {
                return;
            }
        }
        Identifier id = adder.get();
        id.setSystem(system);
        id.setValue(value);
        id.setType(new CodeableConcept().addCoding(new Coding()
                .setSystem(IDENTIFIER_TYPE_SYSTEM)
                .setCode(typeCode)
                .setDisplay(typeDisplay)));
    }

    private OrderRefs lookup(String orderUuid) {
        evictIfOverCapacity();
        Instant now = Instant.now();
        CacheEntry cached = cache.get(orderUuid);
        if (cached != null && cached.expiresAt.isAfter(now)) {
            recordOutcome("cache-hit");
            return cached.refs;
        }
        try {
            OrderRefs refs = fetchFromOpenmrs(orderUuid);
            cache.put(orderUuid, new CacheEntry(refs, now.plus(Duration.ofSeconds(CACHE_TTL_SECONDS))));
            recordOutcome(refs == null ? "miss" : "hit");
            return refs;
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Order {} not found via OpenMRS REST — skipping identifier enrichment", orderUuid);
            recordOutcome("miss");
            return null;
        } catch (ResourceAccessException | HttpClientErrorException e) {
            log.warn("Failed to fetch order {} for identifier enrichment: {}", orderUuid, e.getMessage());
            recordOutcome("error");
            return null;
        } catch (Exception e) {
            log.warn("Unexpected error enriching order {}: {}", orderUuid, e.getMessage());
            recordOutcome("error");
            return null;
        }
    }

    private OrderRefs fetchFromOpenmrs(String orderUuid) throws Exception {
        OpenmrsConfig openmrs = properties.getOpenmrs();
        String url = stripTrailingSlash(openmrs.getBaseUrl())
                + "/ws/rest/v1/order/" + orderUuid
                + "?v=custom:(uuid,accessionNumber,instructions,urgency,dateActivated,orderType:(uuid,display),"
                + "encounter:(encounterType:(uuid),obs:(concept:(uuid),value)))";

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String authHeader = authService.resolveOpenmrsAuthHeader(openmrs.getAuth());
        if (authHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authHeader);
        }
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        String body = response.getBody();
        if (body == null || body.isBlank()) return null;
        JsonNode node = objectMapper.readTree(body);
        String accessionNumber = textOrNull(node, "accessionNumber");
        String instructions = textOrNull(node, "instructions");
        JsonNode orderTypeNode = node.get("orderType");
        String orderTypeName = orderTypeNode != null ? textOrNull(orderTypeNode, "display") : null;
        String orderTypeUuid = orderTypeNode != null ? textOrNull(orderTypeNode, "uuid") : null;
        JsonNode encounterTypeNode = node.path("encounter").get("encounterType");
        String encounterTypeUuid = encounterTypeNode != null ? textOrNull(encounterTypeNode, "uuid") : null;
        String destinationLocationUuid = null;
        String destinationConceptUuid = properties.getReferral().getDirection().getDestinationConceptUuid();
        for (JsonNode obs : node.path("encounter").path("obs")) {
            if (destinationConceptUuid != null && destinationConceptUuid.equals(obs.path("concept").path("uuid").asText())) {
                // the REST API returns a location-uuid text value as the Location itself ({uuid, display, …})
                JsonNode value = obs.path("value");
                destinationLocationUuid = value.isObject() ? textOrNull(value, "uuid") : textOrNull(obs, "value");
            }
        }
        if (accessionNumber == null && orderTypeName == null) return null;
        return new OrderRefs(accessionNumber, orderTypeName, orderTypeUuid, instructions, encounterTypeUuid,
                destinationLocationUuid, textOrNull(node, "urgency"), textOrNull(node, "dateActivated"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private void evictIfOverCapacity() {
        if (cache.size() >= CACHE_MAX_SIZE) {
            cache.clear();
        }
    }

    private void recordOutcome(String outcome) {
        meterRegistry.counter(METRIC_LOOKUP, Tags.of("outcome", outcome)).increment();
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record OrderRefs(String accessionNumber, String orderTypeName, String orderTypeUuid,
                               String instructions, String encounterTypeUuid, String destinationLocationUuid,
                               String urgency, String dateActivated) { }

    /** The receiving facility of a referral out: as performer (Organization) and as requested location. */
    private record Destination(Reference organization, Reference location) { }

    private record DestinationEntry(Destination destination, Instant expiresAt) { }

    private record CacheEntry(OrderRefs refs, Instant expiresAt) { }
}
