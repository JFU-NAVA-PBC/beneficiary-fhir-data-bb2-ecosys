package gov.cms.bfd.server.war.r4.providers;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.newrelic.api.agent.Trace;
import gov.cms.bfd.model.codebook.data.CcwCodebookVariable;
import gov.cms.bfd.model.rif.entities.Beneficiary;
import gov.cms.bfd.server.war.commons.CommonTransformerUtils;
import gov.cms.bfd.server.war.commons.CoverageClass;
import gov.cms.bfd.server.war.commons.MedicareSegment;
import gov.cms.bfd.server.war.commons.ProfileConstants;
import gov.cms.bfd.server.war.commons.SubscriberPolicyRelationship;
import gov.cms.bfd.server.war.commons.TransformerConstants;
import gov.cms.bfd.sharedutils.exceptions.BadCodeMonkeyException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Coverage.CoverageStatus;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

/** Transforms CCW {@link Beneficiary} instances into FHIR {@link Coverage} resources. */
@Component
final class CoverageTransformerV2 {

  /** Helper to record metric information. */
  private final MetricRegistry metricRegistry;

  /**
   * Instantiates a new {@link CoverageTransformerV2}.
   *
   * <p>Spring will wire this into a singleton bean during the initial component scan, and it will
   * be injected properly into places that need it, so this constructor should only be explicitly
   * called by tests.
   *
   * @param metricRegistry the metric registry
   */
  public CoverageTransformerV2(MetricRegistry metricRegistry) {
    this.metricRegistry = metricRegistry;
  }

  /**
   * Transforms a beneficiary and medicare segment into a {@link Coverage} resource.
   *
   * @param medicareSegment the {@link MedicareSegment} to generate a {@link Coverage} resource for
   * @param beneficiary the {@link Beneficiary} to generate a {@link Coverage} resource for
   * @return the {@link Coverage} resource that was generated
   */
  @Trace
  public Coverage transform(MedicareSegment medicareSegment, Beneficiary beneficiary) {
    Objects.requireNonNull(medicareSegment);
    Objects.requireNonNull(beneficiary);

    return switch (medicareSegment) {
      case PART_A -> transformPartA(beneficiary);
      case PART_B -> transformPartB(beneficiary);
      case PART_C -> transformPartC(beneficiary);
      case PART_D -> transformPartD(beneficiary);
      default -> throw new BadCodeMonkeyException();
    };
  }

  /**
   * Transforms a beneficiary into a {@link Coverage} resource.
   *
   * @param beneficiary the CCW {@link Beneficiary} to generate the {@link Coverage}s for
   * @return the FHIR {@link Coverage} resources that can be generated from the specified {@link
   *     Beneficiary}
   */
  @Trace
  public List<IBaseResource> transform(Beneficiary beneficiary) {
    return Arrays.stream(MedicareSegment.values())
        .map(s -> transform(s, beneficiary))
        .collect(Collectors.toList());
  }

  /**
   * Transforms a medicare part A beneficiary into a {@link Coverage} resource.
   *
   * @param beneficiary the {@link Beneficiary} to generate a {@link MedicareSegment#PART_A} {@link
   *     Coverage} resource for
   * @return {@link MedicareSegment#PART_A} {@link Coverage} resource for the specified {@link
   *     Beneficiary}
   */
  private Coverage transformPartA(Beneficiary beneficiary) {
    Timer.Context timer = createTimerContext("part_a");
    Coverage coverage = new Coverage();

    coverage.getMeta().addProfile(ProfileConstants.C4BB_COVERAGE_URL);

    coverage.setId(CommonTransformerUtils.buildCoverageId(MedicareSegment.PART_A, beneficiary));

    setCoverageStatus(coverage, beneficiary.getPartATerminationCode());
    TransformerUtilsV2.setPeriodStart(
        coverage.getPeriod(), beneficiary.getPartACoverageStartDate());
    TransformerUtilsV2.setPeriodEnd(coverage.getPeriod(), beneficiary.getPartACoverageEndDate());

    beneficiary.getMedicareBeneficiaryId().ifPresent(value -> coverage.setSubscriberId(value));

    setTypeAndIssuer(coverage);

    setCoverageRelationship(coverage, SubscriberPolicyRelationship.SELF);

    createCoverageClass(
        coverage, CoverageClass.GROUP, TransformerConstants.COVERAGE_PLAN, Optional.empty());

    createCoverageClass(
        coverage, CoverageClass.PLAN, TransformerConstants.COVERAGE_PLAN_PART_A, Optional.empty());

    coverage.setBeneficiary(TransformerUtilsV2.referencePatient(beneficiary));

    addCoverageExtension(
        coverage, CcwCodebookVariable.MS_CD, beneficiary.getMedicareEnrollmentStatusCode());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.OREC, beneficiary.getEntitlementCodeOriginal());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.CREC, beneficiary.getEntitlementCodeCurrent());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.ESRD_IND, beneficiary.getEndStageRenalDiseaseCode());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.A_TRM_CD, beneficiary.getPartATerminationCode());

    if (beneficiary.getBeneEnrollmentReferenceYear().isPresent()) {
      addCoverageDecimalExtension(
          coverage, CcwCodebookVariable.RFRNC_YR, beneficiary.getBeneEnrollmentReferenceYear());
      // Monthly Medicare-Medicaid dual eligibility codes
      transformEntitlementDualEligibility(coverage, beneficiary);

      // Medicare Entitlement Buy In Indicator
      transformEntitlementBuyInIndicators(coverage, beneficiary);
    }

    // update Coverage.meta.lastUpdated
    TransformerUtilsV2.setLastUpdated(coverage, beneficiary.getLastUpdated());

    timer.stop();
    return coverage;
  }

  /**
   * Transforms a medicare part B beneficiary into a {@link Coverage} resource.
   *
   * @param beneficiary the {@link Beneficiary} to generate a {@link MedicareSegment#PART_B} {@link
   *     Coverage} resource for
   * @return {@link MedicareSegment#PART_B} {@link Coverage} resource for the specified {@link
   *     Beneficiary}
   */
  private Coverage transformPartB(Beneficiary beneficiary) {
    Timer.Context timer = createTimerContext("part_b");
    Coverage coverage = new Coverage();

    coverage.getMeta().addProfile(ProfileConstants.C4BB_COVERAGE_URL);
    coverage.setId(CommonTransformerUtils.buildCoverageId(MedicareSegment.PART_B, beneficiary));
    setCoverageStatus(coverage, beneficiary.getPartBTerminationCode());

    TransformerUtilsV2.setPeriodStart(
        coverage.getPeriod(), beneficiary.getPartBCoverageStartDate());
    TransformerUtilsV2.setPeriodEnd(coverage.getPeriod(), beneficiary.getPartBCoverageEndDate());

    beneficiary.getMedicareBeneficiaryId().ifPresent(value -> coverage.setSubscriberId(value));

    setTypeAndIssuer(coverage);

    setCoverageRelationship(coverage, SubscriberPolicyRelationship.SELF);

    createCoverageClass(
        coverage, CoverageClass.GROUP, TransformerConstants.COVERAGE_PLAN, Optional.empty());

    createCoverageClass(
        coverage, CoverageClass.PLAN, TransformerConstants.COVERAGE_PLAN_PART_B, Optional.empty());

    coverage.setBeneficiary(TransformerUtilsV2.referencePatient(beneficiary));

    addCoverageExtension(
        coverage, CcwCodebookVariable.MS_CD, beneficiary.getMedicareEnrollmentStatusCode());

    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.B_TRM_CD, beneficiary.getPartBTerminationCode());

    if (beneficiary.getBeneEnrollmentReferenceYear().isPresent()) {
      addCoverageDecimalExtension(
          coverage, CcwCodebookVariable.RFRNC_YR, beneficiary.getBeneEnrollmentReferenceYear());
      // Monthly Medicare-Medicaid dual eligibility codes
      transformEntitlementDualEligibility(coverage, beneficiary);

      // Medicare Entitlement Buy In Indicator
      transformEntitlementBuyInIndicators(coverage, beneficiary);
    }

    // update Coverage.meta.lastUpdated
    TransformerUtilsV2.setLastUpdated(coverage, beneficiary.getLastUpdated());

    timer.stop();
    return coverage;
  }

  /**
   * Transforms a medicare part C beneficiary into a {@link Coverage} resource.
   *
   * @param beneficiary the {@link Beneficiary} to generate a {@link MedicareSegment#PART_C} {@link
   *     Coverage} resource for
   * @return {@link MedicareSegment#PART_C} {@link Coverage} resource for the specified {@link
   *     Beneficiary}
   */
  private Coverage transformPartC(Beneficiary beneficiary) {
    Timer.Context timer = createTimerContext("part_c");
    Coverage coverage = new Coverage();

    coverage.getMeta().addProfile(ProfileConstants.C4BB_COVERAGE_URL);
    coverage.setId(CommonTransformerUtils.buildCoverageId(MedicareSegment.PART_C, beneficiary));
    coverage.setStatus(CoverageStatus.ACTIVE);

    beneficiary.getMedicareBeneficiaryId().ifPresent(value -> coverage.setSubscriberId(value));

    setTypeAndIssuer(coverage);

    setCoverageRelationship(coverage, SubscriberPolicyRelationship.SELF);

    createCoverageClass(
        coverage, CoverageClass.GROUP, TransformerConstants.COVERAGE_PLAN, Optional.empty());

    createCoverageClass(
        coverage, CoverageClass.PLAN, TransformerConstants.COVERAGE_PLAN_PART_C, Optional.empty());

    coverage.setBeneficiary(TransformerUtilsV2.referencePatient(beneficiary));

    if (beneficiary.getBeneEnrollmentReferenceYear().isPresent()) {
      transformPartCContractNumber(coverage, beneficiary);

      tranformsPartCPbpNumber(coverage, beneficiary);

      transformPartCPlanType(coverage, beneficiary);

      transformHMOIndicator(coverage, beneficiary);

      // The reference year of the enrollment data
      addCoverageDecimalExtension(
          coverage, CcwCodebookVariable.RFRNC_YR, beneficiary.getBeneEnrollmentReferenceYear());

      // Monthly Medicare-Medicaid dual eligibility codes
      transformEntitlementDualEligibility(coverage, beneficiary);
    }

    // update Coverage.meta.lastUpdated
    TransformerUtilsV2.setLastUpdated(coverage, beneficiary.getLastUpdated());

    timer.stop();
    return coverage;
  }

  /**
   * Transforms a medicare part D beneficiary into a {@link Coverage} resource.
   *
   * @param beneficiary the {@link Beneficiary} to generate a {@link MedicareSegment#PART_D} {@link
   *     Coverage} resource for
   * @return {@link MedicareSegment#PART_D} {@link Coverage} resource for the specified {@link
   *     Beneficiary}
   */
  private Coverage transformPartD(Beneficiary beneficiary) {
    Timer.Context timer = createTimerContext("part_d");
    Coverage coverage = new Coverage();

    coverage.getMeta().addProfile(ProfileConstants.C4BB_COVERAGE_URL);
    coverage.setId(CommonTransformerUtils.buildCoverageId(MedicareSegment.PART_D, beneficiary));

    TransformerUtilsV2.setPeriodStart(
        coverage.getPeriod(), beneficiary.getPartDCoverageStartDate());
    TransformerUtilsV2.setPeriodEnd(coverage.getPeriod(), beneficiary.getPartDCoverageEndDate());

    beneficiary.getMedicareBeneficiaryId().ifPresent(value -> coverage.setSubscriberId(value));

    setTypeAndIssuer(coverage);

    setCoverageRelationship(coverage, SubscriberPolicyRelationship.SELF);

    createCoverageClass(
        coverage, CoverageClass.GROUP, TransformerConstants.COVERAGE_PLAN, Optional.empty());

    createCoverageClass(
        coverage, CoverageClass.PLAN, TransformerConstants.COVERAGE_PLAN_PART_D, Optional.empty());

    coverage.setStatus(CoverageStatus.ACTIVE);

    coverage.setBeneficiary(TransformerUtilsV2.referencePatient(beneficiary));

    addCoverageExtension(
        coverage, CcwCodebookVariable.MS_CD, beneficiary.getMedicareEnrollmentStatusCode());

    if (beneficiary.getBeneEnrollmentReferenceYear().isPresent()) {

      transformPartDContractNumber(coverage, beneficiary);

      // Beneficiary Monthly Data
      beneficiary
          .getBeneficiaryMonthlys()
          .forEach(
              beneMonthly -> {
                int month = beneMonthly.getYearMonth().getMonthValue();
                String yearMonth =
                    String.format("%s-%s", beneMonthly.getYearMonth().getYear(), month);

                Map<Integer, CcwCodebookVariable> mapOfMonth =
                    CommonTransformerUtils.getPartDCcwCodebookMonthMap();

                if (mapOfMonth.containsKey(month)) {
                  if (beneMonthly.getPartDContractNumberId().isEmpty()
                      || beneMonthly.getPartDContractNumberId().get().isEmpty()) {
                    beneMonthly.setPartDContractNumberId(Optional.of("0"));
                  }

                  coverage.addExtension(
                      TransformerUtilsV2.createExtensionCoding(
                          coverage,
                          mapOfMonth.get(month),
                          yearMonth,
                          beneMonthly.getPartDContractNumberId()));
                }
              });

      transformPartDPbpNumber(coverage, beneficiary);

      transformPartDSegmentNumber(coverage, beneficiary);

      transformPartDLowIncomeCostShareGroup(coverage, beneficiary);

      transformPartDRetireeDrugSubsidy(coverage, beneficiary);

      // The reference year of the enrollment data
      addCoverageDecimalExtension(
          coverage, CcwCodebookVariable.RFRNC_YR, beneficiary.getBeneEnrollmentReferenceYear());

      // Monthly Medicare-Medicaid dual eligibility codes
      transformEntitlementDualEligibility(coverage, beneficiary);
    }

    // update Coverage.meta.lastUpdated
    TransformerUtilsV2.setLastUpdated(coverage, beneficiary.getLastUpdated());

    timer.stop();
    return coverage;
  }

  /**
   * Adds Monthly Medicare Advantage (MA) enrollment indicator (HMO) extensions to the provided
   * {@link Coverage} resource.
   *
   * @param coverage the FHIR {@link Coverage} resource to add to
   * @param beneficiary the value for {@link Beneficiary}
   */
  private void transformHMOIndicator(Coverage coverage, Beneficiary beneficiary) {
    // Monthly Medicare Advantage (MA) enrollment indicators:
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_01, beneficiary.getHmoIndicatorJanInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_02, beneficiary.getHmoIndicatorFebInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_03, beneficiary.getHmoIndicatorMarInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_04, beneficiary.getHmoIndicatorAprInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_05, beneficiary.getHmoIndicatorMayInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_06, beneficiary.getHmoIndicatorJunInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_07, beneficiary.getHmoIndicatorJulInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_08, beneficiary.getHmoIndicatorAugInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_09, beneficiary.getHmoIndicatorSeptInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_10, beneficiary.getHmoIndicatorOctInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_11, beneficiary.getHmoIndicatorNovInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.HMO_IND_12, beneficiary.getHmoIndicatorDecInd());
  }

  /**
   * Adds Medicare plan type extensions to the provided {@link Coverage} resource.
   *
   * @param coverage the FHIR {@link Coverage} resource to add to
   * @param beneficiary the value for {@link Beneficiary}
   */
  private void transformPartCPlanType(Coverage coverage, Beneficiary beneficiary) {
    // Plan Type
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_01, beneficiary.getPartCPlanTypeJanCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_02, beneficiary.getPartCPlanTypeFebCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_03, beneficiary.getPartCPlanTypeMarCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_04, beneficiary.getPartCPlanTypeAprCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_05, beneficiary.getPartCPlanTypeMayCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_06, beneficiary.getPartCPlanTypeJunCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_07, beneficiary.getPartCPlanTypeJulCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_08, beneficiary.getPartCPlanTypeAugCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_09, beneficiary.getPartCPlanTypeSeptCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_10, beneficiary.getPartCPlanTypeOctCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_11, beneficiary.getPartCPlanTypeNovCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PLAN_TYPE_CD_12, beneficiary.getPartCPlanTypeDecCode());
  }

  /**
   * Adds Medicare pbp number extensions to the provided {@link Coverage} resource.
   *
   * @param coverage the FHIR {@link Coverage} resource to add to
   * @param beneficiary the value for {@link Beneficiary}
   */
  private void tranformsPartCPbpNumber(Coverage coverage, Beneficiary beneficiary) {
    // PBP
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_01, beneficiary.getPartCPbpNumberJanId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_02, beneficiary.getPartCPbpNumberFebId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_03, beneficiary.getPartCPbpNumberMarId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_04, beneficiary.getPartCPbpNumberAprId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_05, beneficiary.getPartCPbpNumberMayId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_06, beneficiary.getPartCPbpNumberJunId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_07, beneficiary.getPartCPbpNumberJulId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_08, beneficiary.getPartCPbpNumberAugId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_09, beneficiary.getPartCPbpNumberSeptId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_10, beneficiary.getPartCPbpNumberOctId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_11, beneficiary.getPartCPbpNumberNovId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_PBP_ID_12, beneficiary.getPartCPbpNumberDecId());
  }

  /**
   * Adds Medicare part C contract id extensions to the provided {@link Coverage} resource.
   *
   * @param coverage the FHIR {@link Coverage} resource to add to
   * @param beneficiary the value for {@link Beneficiary}
   */
  private void transformPartCContractNumber(Coverage coverage, Beneficiary beneficiary) {
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_01, beneficiary.getPartCContractNumberJanId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_02, beneficiary.getPartCContractNumberFebId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_03, beneficiary.getPartCContractNumberMarId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_04, beneficiary.getPartCContractNumberAprId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_05, beneficiary.getPartCContractNumberMayId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_06, beneficiary.getPartCContractNumberJunId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_07, beneficiary.getPartCContractNumberJulId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_08, beneficiary.getPartCContractNumberAugId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_09, beneficiary.getPartCContractNumberSeptId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_10, beneficiary.getPartCContractNumberOctId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_11, beneficiary.getPartCContractNumberNovId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTC_CNTRCT_ID_12, beneficiary.getPartCContractNumberDecId());
  }

  /**
   * Adds Medicare entitlement buy in indicator extensions to the provided {@link Coverage}
   * resource.
   *
   * @param coverage the {@link Coverage} to generate
   * @param beneficiary the {@link Beneficiary} to generate Coverage for
   */
  private void transformEntitlementBuyInIndicators(Coverage coverage, Beneficiary beneficiary) {

    // Medicare Entitlement Buy In Indicator
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN01, beneficiary.getEntitlementBuyInJanInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN02, beneficiary.getEntitlementBuyInFebInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN03, beneficiary.getEntitlementBuyInMarInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN04, beneficiary.getEntitlementBuyInAprInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN05, beneficiary.getEntitlementBuyInMayInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN06, beneficiary.getEntitlementBuyInJunInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN07, beneficiary.getEntitlementBuyInJulInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN08, beneficiary.getEntitlementBuyInAugInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN09, beneficiary.getEntitlementBuyInSeptInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN10, beneficiary.getEntitlementBuyInOctInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN11, beneficiary.getEntitlementBuyInNovInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.BUYIN12, beneficiary.getEntitlementBuyInDecInd());
  }

  /**
   * Adds monthly Medicare-Medicaid dual eligibility code extensions to the provided {@link
   * Coverage} resource.
   *
   * @param coverage the {@link Coverage} to generate
   * @param beneficiary the {@link Beneficiary} to generate Coverage for
   */
  private void transformEntitlementDualEligibility(Coverage coverage, Beneficiary beneficiary) {

    // Monthly Medicare-Medicaid dual eligibility codes
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_01, beneficiary.getMedicaidDualEligibilityJanCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_02, beneficiary.getMedicaidDualEligibilityFebCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_03, beneficiary.getMedicaidDualEligibilityMarCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_04, beneficiary.getMedicaidDualEligibilityAprCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_05, beneficiary.getMedicaidDualEligibilityMayCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_06, beneficiary.getMedicaidDualEligibilityJunCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_07, beneficiary.getMedicaidDualEligibilityJulCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_08, beneficiary.getMedicaidDualEligibilityAugCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_09, beneficiary.getMedicaidDualEligibilitySeptCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_10, beneficiary.getMedicaidDualEligibilityOctCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_11, beneficiary.getMedicaidDualEligibilityNovCode());
    addCoverageExtension(
        coverage, CcwCodebookVariable.DUAL_12, beneficiary.getMedicaidDualEligibilityDecCode());
  }

  /**
   * Adds monthly part D retiree drug subsidy indicators extensions to the provided {@link Coverage}
   * resource.
   *
   * @param coverage the {@link Coverage} to generate
   * @param beneficiary the {@link Beneficiary} to generate Coverage for
   */
  private void transformPartDRetireeDrugSubsidy(Coverage coverage, Beneficiary beneficiary) {
    // Monthly Part D Retiree Drug Subsidy Indicators
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND01, beneficiary.getPartDRetireeDrugSubsidyJanInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND02, beneficiary.getPartDRetireeDrugSubsidyFebInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND03, beneficiary.getPartDRetireeDrugSubsidyMarInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND04, beneficiary.getPartDRetireeDrugSubsidyAprInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND05, beneficiary.getPartDRetireeDrugSubsidyMayInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND06, beneficiary.getPartDRetireeDrugSubsidyJunInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND07, beneficiary.getPartDRetireeDrugSubsidyJulInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND08, beneficiary.getPartDRetireeDrugSubsidyAugInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND09, beneficiary.getPartDRetireeDrugSubsidySeptInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND10, beneficiary.getPartDRetireeDrugSubsidyOctInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND11, beneficiary.getPartDRetireeDrugSubsidyNovInd());
    addCoverageCodeExtension(
        coverage, CcwCodebookVariable.RDSIND12, beneficiary.getPartDRetireeDrugSubsidyDecInd());
  }

  /**
   * Adds monthly cost sharing group extensions to the provided {@link Coverage} resource.
   *
   * @param coverage the FHIR {@link Coverage} resource to add to
   * @param beneficiary the value for {@link Beneficiary}
   */
  private void transformPartDLowIncomeCostShareGroup(Coverage coverage, Beneficiary beneficiary) {
    // Monthly cost sharing group
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR01,
        beneficiary.getPartDLowIncomeCostShareGroupJanCode());
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR02,
        beneficiary.getPartDLowIncomeCostShareGroupFebCode());
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR03,
        beneficiary.getPartDLowIncomeCostShareGroupMarCode());
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR04,
        beneficiary.getPartDLowIncomeCostShareGroupAprCode());
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR05,
        beneficiary.getPartDLowIncomeCostShareGroupMayCode());
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR06,
        beneficiary.getPartDLowIncomeCostShareGroupJunCode());
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR07,
        beneficiary.getPartDLowIncomeCostShareGroupJulCode());
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR08,
        beneficiary.getPartDLowIncomeCostShareGroupAugCode());
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR09,
        beneficiary.getPartDLowIncomeCostShareGroupSeptCode());
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR10,
        beneficiary.getPartDLowIncomeCostShareGroupOctCode());
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR11,
        beneficiary.getPartDLowIncomeCostShareGroupNovCode());
    addCoverageExtension(
        coverage,
        CcwCodebookVariable.CSTSHR12,
        beneficiary.getPartDLowIncomeCostShareGroupDecCode());
  }

  /**
   * Adds segment number extensions to the provided {@link Coverage} resource.
   *
   * @param coverage the FHIR {@link Coverage} resource to add to
   * @param beneficiary the value for {@link Beneficiary}
   */
  private void transformPartDSegmentNumber(Coverage coverage, Beneficiary beneficiary) {
    // Segment Number
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID01, beneficiary.getPartDSegmentNumberJanId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID02, beneficiary.getPartDSegmentNumberFebId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID03, beneficiary.getPartDSegmentNumberMarId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID04, beneficiary.getPartDSegmentNumberAprId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID05, beneficiary.getPartDSegmentNumberMayId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID06, beneficiary.getPartDSegmentNumberJunId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID07, beneficiary.getPartDSegmentNumberJulId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID08, beneficiary.getPartDSegmentNumberAugId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID09, beneficiary.getPartDSegmentNumberSeptId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID10, beneficiary.getPartDSegmentNumberOctId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID11, beneficiary.getPartDSegmentNumberNovId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.SGMTID12, beneficiary.getPartDSegmentNumberDecId());
  }

  /**
   * Adds Medicare part D PBP extensions to the provided {@link Coverage} resource.
   *
   * @param coverage the FHIR {@link Coverage} resource to add to
   * @param beneficiary the value for {@link Beneficiary}
   */
  private void transformPartDPbpNumber(Coverage coverage, Beneficiary beneficiary) {
    // PBP
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID01, beneficiary.getPartDPbpNumberJanId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID02, beneficiary.getPartDPbpNumberFebId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID03, beneficiary.getPartDPbpNumberMarId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID04, beneficiary.getPartDPbpNumberAprId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID05, beneficiary.getPartDPbpNumberMayId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID06, beneficiary.getPartDPbpNumberJunId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID07, beneficiary.getPartDPbpNumberJulId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID08, beneficiary.getPartDPbpNumberAugId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID09, beneficiary.getPartDPbpNumberSeptId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID10, beneficiary.getPartDPbpNumberOctId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID11, beneficiary.getPartDPbpNumberNovId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDPBPID12, beneficiary.getPartDPbpNumberDecId());
  }

  /**
   * Adds Medicare part D contract number extensions to the provided {@link Coverage} resource.
   *
   * @param coverage the FHIR {@link Coverage} resource to add to
   * @param beneficiary the value for {@link Beneficiary}
   */
  private void transformPartDContractNumber(Coverage coverage, Beneficiary beneficiary) {
    // Contract Number
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT01, beneficiary.getPartDContractNumberJanId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT02, beneficiary.getPartDContractNumberFebId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT03, beneficiary.getPartDContractNumberMarId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT04, beneficiary.getPartDContractNumberAprId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT05, beneficiary.getPartDContractNumberMayId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT06, beneficiary.getPartDContractNumberJunId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT07, beneficiary.getPartDContractNumberJulId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT08, beneficiary.getPartDContractNumberAugId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT09, beneficiary.getPartDContractNumberSeptId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT10, beneficiary.getPartDContractNumberOctId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT11, beneficiary.getPartDContractNumberNovId());
    addCoverageExtension(
        coverage, CcwCodebookVariable.PTDCNTRCT12, beneficiary.getPartDContractNumberDecId());
  }

  /**
   * Sets the Coverage.status Looks up or adds a contained {@link Identifier} object to the current
   * {@link Patient}. This is used to store Identifier slices related to the Provider organization.
   *
   * @param coverage The {@link Coverage} to Coverage details
   * @param terminationCode The {@link Character} that denotes if Part is active
   */
  void setCoverageStatus(Coverage coverage, Optional<Character> terminationCode) {
    if (terminationCode.isPresent() && terminationCode.get().equals('0')) {
      coverage.setStatus(CoverageStatus.ACTIVE);
    } else {
      coverage.setStatus(CoverageStatus.CANCELLED);
    }
  }

  /**
   * Sets the Coverage.type creates {@link CodeableConcept} object and sets the Coverage {@link
   * Coverage} type.
   *
   * @param coverage The {@link Coverage} to Coverage details
   */
  void setTypeAndIssuer(Coverage coverage) {
    coverage.setType(
        new CodeableConcept()
            .addCoding(
                new Coding()
                    .setCode("SUBSIDIZ")
                    .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")));
    coverage
        .addPayor()
        .setIdentifier(new Identifier().setValue(TransformerConstants.COVERAGE_ISSUER));
  }

  /**
   * Looks up or adds a contained {@link Identifier} object to the current {@link Patient}. This is
   * used to store Identifier slices related to the Provider organization.
   *
   * @param coverage The {@link Coverage} to Coverage details to
   * @param coverageClass The {@link CoverageClass} of the type
   * @param value The value associated with the {@link CoverageClass}
   * @param name The name associated with the {@link CoverageClass}
   */
  void createCoverageClass(
      Coverage coverage, CoverageClass coverageClass, String value, Optional<String> name) {

    if (value == null || value.isEmpty()) {
      return;
    }

    if (name.isPresent()) {
      coverage
          .addClass_()
          .setValue(value)
          .setName(name.get())
          .getType()
          .addCoding()
          .setSystem(coverageClass.getSystem())
          .setCode(coverageClass.toCode())
          .setDisplay(coverageClass.getDisplay());
    } else {
      coverage
          .addClass_()
          .setValue(value)
          .getType()
          .addCoding()
          .setSystem(coverageClass.getSystem())
          .setCode(coverageClass.toCode())
          .setDisplay(coverageClass.getDisplay());
    }
  }

  /**
   * Sets the Coverage.relationship Looks up or adds a contained {@link Identifier} object to the
   * current {@link Patient}. This is used to store Identifier slices related to the Provider
   * organization.
   *
   * @param coverage The {@link Coverage} to Coverage details
   * @param policyRelationship The {@link SubscriberPolicyRelationship} associated with the Coverage
   */
  void setCoverageRelationship(Coverage coverage, SubscriberPolicyRelationship policyRelationship) {
    coverage.setRelationship(
        new CodeableConcept()
            .addCoding(
                new Coding()
                    .setCode(policyRelationship.toCode())
                    .setSystem(policyRelationship.getSystem())
                    .setDisplay(policyRelationship.getDisplay())));
  }

  /**
   * Sets the Coverage.relationship Looks up or adds a contained {@link Identifier} object to the
   * current {@link Patient}. This is used to store Identifier slices related to the Provider
   * organization.
   *
   * @param coverage The {@link Coverage} to Coverage details
   * @param ccwVariable The {@link CcwCodebookVariable} variable associated with the Coverage
   * @param optVal The {@link String} value associated with the Coverage
   */
  void addCoverageExtension(
      Coverage coverage, CcwCodebookVariable ccwVariable, Optional<String> optVal) {
    optVal.ifPresent(
        value ->
            coverage.addExtension(
                TransformerUtilsV2.createExtensionCoding(coverage, ccwVariable, value)));
  }

  /**
   * Sets the Coverage.relationship Looks up or adds a contained {@link Identifier} object to the
   * current {@link Patient}. This is used to store Identifier slices related to the Provider
   * organization.
   *
   * @param coverage The {@link Coverage} to Coverage details
   * @param ccwVariable The {@link CcwCodebookVariable} variable associated with the Coverage
   * @param optVal The {@link Character} value associated with the Coverage
   */
  void addCoverageCodeExtension(
      Coverage coverage, CcwCodebookVariable ccwVariable, Optional<Character> optVal) {
    optVal.ifPresent(
        value ->
            coverage.addExtension(
                TransformerUtilsV2.createExtensionCoding(coverage, ccwVariable, value)));
  }

  /**
   * Sets the Coverage.relationship Looks up or adds a contained {@link Identifier} object to the
   * current {@link Patient}. This is used to store Identifier slices related to the Provider
   * organization.
   *
   * @param coverage The {@link Coverage} to Coverage details
   * @param ccwVariable The {@link CcwCodebookVariable} variable associated with the Coverage
   * @param optVal The {@link BigDecimal} value associated with the Coverage
   */
  void addCoverageDecimalExtension(
      Coverage coverage, CcwCodebookVariable ccwVariable, Optional<BigDecimal> optVal) {
    optVal.ifPresent(
        value ->
            coverage.addExtension(
                TransformerUtilsV2.createExtensionDate(ccwVariable, optVal.get())));
  }

  /**
   * Constructs a Timer context {@link Timer.Context} suitable for measuring compute duration.
   *
   * @param partId The context string {@link String}
   * @return the timer context
   */
  Timer.Context createTimerContext(String partId) {
    return CommonTransformerUtils.createMetricsTimer(
        metricRegistry, getClass().getSimpleName(), "transform", partId);
  }
}
