package org.openphc.cce.emitter.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Validated
@ConfigurationProperties(prefix = "emitter")
public class EmitterProperties {

    @Valid
    @NotNull
    private OpenmrsConfig openmrs = new OpenmrsConfig();

    @Valid
    @NotNull
    private OpenhimConfig openhim = new OpenhimConfig();

    @Valid
    private PollingConfig polling = new PollingConfig();

    @Valid
    private CheckpointConfig checkpoint = new CheckpointConfig();

    @Valid
    private PatientConfig patient = new PatientConfig();

    @Valid
    private ReferralConfig referral = new ReferralConfig();

    @Valid
    private CareSettingConfig careSetting = new CareSettingConfig();

    @Valid
    private TestOrderCategoryConfig testOrderCategory = new TestOrderCategoryConfig();

    @Data
    public static class OpenmrsConfig {
        @NotBlank
        private String name = "openmrs";
        @NotBlank
        private String baseUrl;
        private String fhirPath = "/ws/fhir2/R4";
        @Valid
        private OpenmrsAuthConfig auth = new OpenmrsAuthConfig();
    }

    @Data
    public static class OpenmrsAuthConfig {
        /** basic | bearer | oauth2 */
        private String type = "basic";
        private String username;
        private String password;
        private String token;
        @Valid
        private OAuth2Config oauth2 = new OAuth2Config();
    }

    @Data
    public static class OAuth2Config {
        private String tokenUrl;
        private String clientId;
        private String clientSecret;
        private String scope;
    }

    @Data
    public static class OpenhimConfig {
        @NotBlank
        private String name = "openhim";
        @NotBlank
        private String baseUrl;
        @Valid
        private OpenhimAuthConfig auth = new OpenhimAuthConfig();
        private boolean sslTrustAll = false;
        private boolean appendResourceType = true;
        @Valid
        private RetryConfig retry = new RetryConfig();
    }

    @Data
    public static class OpenhimAuthConfig {
        /** none | basic | jwt | custom-token */
        private String type = "basic";
        private String username;
        private String password;
        private String token;
    }

    @Data
    public static class RetryConfig {
        private int maxAttempts = 3;
        private long backoffMs = 2000;
    }

    @Data
    public static class PollingConfig {
        @Valid
        private FhirPollingConfig fhir = new FhirPollingConfig();
    }

    @Data
    public static class FhirPollingConfig {
        private boolean enabled = true;
        private int intervalSeconds = 30;
        private int pageSize = 50;
        private int overlapSeconds = 5;
        /**
         * When a polled ServiceRequest/MedicationRequest carries a basedOn /
         * priorPrescription reference (i.e. it is a revision row), refetch the
         * prior order so its terminal status (revoked/completed) is observed
         * downstream. When the new revision itself is a discontinue tombstone
         * (status revoked/cancelled/stopped), the new resource is suppressed
         * since it carries no independent clinical content.
         */
        private boolean followPriorOrder = true;
        private List<String> resourceTypes = List.of(
                "Patient", "Encounter", "Observation", "Condition", "Immunization",
                "DiagnosticReport", "AllergyIntolerance", "Procedure", "MedicationRequest",
                "MedicationDispense", "MedicationAdministration", "ServiceRequest",
                "Location", "Practitioner"
        );
    }

    @Data
    public static class CheckpointConfig {
        /** file (database planned) */
        private String storeType = "file";
        private String filePath = "./data/checkpoints.json";
    }

    @Data
    public static class PatientConfig {
        @Valid
        private NationalIdConfig nationalId = new NationalIdConfig();
    }

    @Data
    public static class NationalIdConfig {
        private boolean enabled = true;
        private String system = "http://moh.gov.rw/fhir/identifier/national-id";
        private String code = "national-id";
        private long cacheTtlSeconds = 3600;
        private int cacheMaxSize = 10000;
        /** skip | forward-as-is | fail */
        private String onMissing = "skip";
    }

    @Data
    public static class ReferralConfig {
        @Valid
        private ReferralResponseConfig response = new ReferralResponseConfig();
        @Valid
        private ReferralDirectionConfig direction = new ReferralDirectionConfig();
    }

    @Data
    public static class ReferralDirectionConfig {
        /**
         * When enabled, every ServiceRequest whose OpenMRS order type is the referral order type
         * ({@code referral.response.order-type-name}) gets a category coding saying whether it is
         * an inbound referral (created by the receiver adaptor inside a "Referral In" encounter)
         * or an outbound referral placed by a clinician in this EMR — so a PlanDefinition can
         * trigger on outbound referrals without matching the echo of inbound ones.
         */
        private boolean enabled = true;
        private String categorySystem = "http://cce.openphc.org/CodeSystem/referral-direction";
        private String inboundCode = "inbound";
        private String outboundCode = "outbound";
        /** Encounter type the receiver adaptor creates inbound referral orders in ("Referral In"). */
        private String inboundEncounterTypeUuid = "de0f5558-9813-4fa3-8613-467015f9fabd";
        /**
         * Text concept on an outbound referral's encounter holding the destination location uuid
         * (recorded by the referral-fulfillment ESM's "New referral out"). The destination is sent
         * as {@code ServiceRequest.performer}; when the obs is absent, performer is left as is.
         */
        private String destinationConceptUuid = "5f8a2c6e-3b9d-4e1f-a7c4-0d6e9b2a8f15";
        /** Location attribute type holding the destination's facility code, sent as the performer identifier. */
        private String facilityCodeAttributeTypeUuid = "9e2b7c40-5d1a-4f8e-b3c6-1a7d0e4f9c52";
        /** Identifier system of the destination facility code on {@code ServiceRequest.performer}. */
        private String destinationIdentifierSystem = "http://cce.openphc.org/fhir/NamingSystem/facility-code";
    }

    @Data
    public static class TestOrderCategoryConfig {
        /**
         * When enabled, every ServiceRequest whose OpenMRS order type is the test order type gets a
         * category coding (by default SNOMED 108252007 "Laboratory procedure", the R4 example), so a
         * PlanDefinition can trigger on lab orders with a codeFilter. fhir2 sends test orders with no
         * category, and the only other way to tell them from referral orders is a condition on the
         * category being absent.
         */
        private boolean enabled = true;
        /** OpenMRS core "Test Order" order type. */
        private String orderTypeUuid = "52a447d3-a64a-11e3-9aeb-50e549534c5e";
        private String system = "http://snomed.info/sct";
        private String code = "108252007";
        private String display = "Laboratory procedure";
    }

    @Data
    public static class CareSettingConfig {
        /**
         * When enabled, every forwarded resource carries a {@code meta.tag} naming the care setting
         * this EMR serves (e.g. the cluster hospital), so PlanDefinition conditions can tell its
         * events from other systems' events carrying the same codes (CloudEvent source is not
         * visible to trigger conditions).
         */
        private boolean enabled = false;
        private String system = "http://cce.openphc.org/CodeSystem/care-setting";
        private String code;
        private String display;
    }

    @Data
    public static class ReferralResponseConfig {
        /**
         * When enabled, ServiceRequest/MedicationRequest resources whose OpenMRS
         * order type display name matches {@link #orderTypeName} and whose status
         * is {@code completed} or {@code revoked} are classified as a referral
         * response: a category coding is added, {@code basedOn} is populated with
         * a logical reference to the placer's ServiceRequest id (sourced from the
         * OpenMRS {@code accessionNumber}), and {@code intent} is flipped to
         * {@code filler-order}. This lets the downstream Compliance Service match
         * the event via standard PlanDefinition action.trigger / action.condition
         * expressions.
         */
        private boolean enabled = true;
        /** OpenMRS order type display name to treat as a referral. */
        private String orderTypeName = "Referral";
        /** CodeSystem URL for the response classification coding added to category. */
        private String categorySystem = "http://cce.openphc.org/CodeSystem/event-type";
        private String categoryCode = "referral-response";
        private String categoryDisplay = "Referral Response";
        /**
         * Identifier system used in {@code basedOn[].identifier.system} when
         * referencing the placer's ServiceRequest id. Must match what the
         * placer system (e.g. SPICE) advertises so the Compliance Service can
         * resolve back to the original placer order.
         */
        private String basedOnIdentifierSystem = "http://mdtlabs.com/service-request-id";
        /** Flip {@code intent} from {@code order} to {@code filler-order}. */
        private boolean flipIntent = false;

        /**
         * When enabled, the full {@code instructions} text from the OpenMRS order
         * is added as a coded {@code orderDetail} on the outgoing ServiceRequest.
         * This lets downstream consumers read referral context (e.g. ANC visit info)
         * directly from the resource.
         */
        private boolean includeInstructions = true;
        /** CodeSystem for the instructions orderDetail coding. */
        private String instructionsSystem = "http://cce.openphc.org/CodeSystem/referral-instructions";
        /** Code for the instructions orderDetail coding. */
        private String instructionsCode = "referral-instructions";
        /** Display for the instructions orderDetail coding. */
        private String instructionsDisplay = "Referral Instructions";
        /**
         * OpenMRS encounter type UUID for "Referral Response". Used to resolve the
         * fulfilling practitioner from a sibling encounter under the same visit when
         * the ServiceRequest's linked encounter has no participant.
         */
        private String responseEncounterTypeUuid = "479e4805-90d9-44f5-b252-333d8a687e52";
    }
}
