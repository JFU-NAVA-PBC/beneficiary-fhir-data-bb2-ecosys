package gov.cms.bfd.pipeline.ccw.rif;

import gov.cms.bfd.pipeline.ccw.rif.extract.s3.DataSetManifest;
import gov.cms.bfd.pipeline.ccw.rif.extract.s3.DataSetManifest.PreValidationProperties;
import gov.cms.bfd.pipeline.sharedutils.PipelineApplicationState;
import gov.cms.bfd.sharedutils.exceptions.BadCodeMonkeyException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synthea version of a {@link CcwRifLoadPreValidateInterface} that performs whatever approprpriate
 * pre-validation of the manifest it feels is appropriate. In this case, it will perform assorted
 * range-checking of bene_id(s), clm_id(s) potential hash collisions.
 */
public class CcwRifLoadPreValidateSynthea implements CcwRifLoadPreValidateInterface {

  private static final Logger LOGGER = LoggerFactory.getLogger(CcwRifLoadPreValidateSynthea.class);

  /**
   * absolute lower-bound value for a clm_grp_id, as determined from initial Synthea setup; will not
   * change!
   */
  private static final long CLM_GRP_ID_END = -99999831003L;

  /** new a handle to the {@link PipelineApplicationState} provide by the init method. */
  private PipelineApplicationState appState = null;

  /** SQL used to perform range checking on provided begin-end bene_id values. */
  private static final String CHECK_BENE_RANGE =
      "select count(bene_id) from beneficiaries " + "where bene_id <= ? and bene_id > ? limit 1";

  /**
   * SQL used to check for collision of CARR_CLM_CNTL_NUM in CARRIER_CLAIMS table. This is sort of
   * interesting in that we don't really have any data for the CARR_CLM_CNTL_NUM and it really
   * shouldn't be a negative number since it is an identifier generated by MCS (Multi-Carrier
   * System) and denotes a carrier, plan code, year of claim, etc.
   *
   * <p>Will leave the query looking for a negative CARR_CLM_CNTL_NUM but may need to be changed
   * depending on Synthea generation.
   */
  private static final String CHECK_CARR_CLAIM_CNTL_NUM =
      "select count(clm_id) from carrier_claims "
          + "where clm_id <= ? and carr_clm_cntl_num <= ? limit 1";

  /** array of db table names that will be used to check range for CLM_GRP_ID values. */
  private static final String[] CLAIMS_TABLES_GRP = {
    "carrier_claims",
    "inpatient_claims",
    "outpatient_claims",
    "snf_claims",
    "dme_claims",
    "hha_claims",
    "hospice_claims"
  };

  /** SQL used to check potential collision of CLM_GRP_ID in several of the claims tables. */
  private static final String CHECK_CLAIMS_GROUP_ID =
      "select count(clm_id) from %s " + "where clm_grp_id <= ? and clm_grp_id > ? limit 1";

  /** SQL used to check potential collision of CLM_GRP_ID in PARTD_EVENTS tables. */
  private static final String CHECK_PDE_CLAIMS_GROUP_ID =
      "select pde_id from partd_events "
          + "where pde_id <= ? "
          + "and clm_grp_id <= ? and clm_grp_id > ? limit 1";

  /**
   * SQL used to check for potential collision of HICN_UNHASHED or MBI_NUM in BENEFICIARIES tables.
   */
  private static final String CHECK_HICN_MBI_HASH =
      "select count (bene_id) from beneficiaries "
          + "where (hicn_unhashed = ? or mbi_num = ?) limit 1 ";

  /** array of db table names that will be used to check range for FI_DOC_CLM_CNTL_NUM values. */
  private static final String[] CLAIMS_TABLES_DOC_CNTL = {
    "inpatient_claims", "outpatient_claims", "snf_claims", "hha_claims", "hospice_claims"
  };

  /** SQL used to check for potential collision of FI_DOC_CLM_CNTL_NUM in various claims tables. */
  private static final String CHECK_FI_DOC_CNTL =
      "select count(clm_id) from %s where fi_doc_clm_cntl_num = ? limit 1";

  /** SQL used to check for potential collision of MBI_NUM in various BENEFICIARY-related tables. */
  private static final String CHECK_MBI_DUPES =
      "select count(*) from ( "
          + "select count(*) bene_id_count from ("
          + "select distinct bene_id, mbi_num from public.beneficiaries_history "
          + "where bene_id < 0 and mbi_num IS NOT NULL "
          + "union "
          + "select distinct bene_id, mbi_num from public.medicare_beneficiaryid_history "
          + "where bene_id < 0 and mbi_num IS NOT NULL "
          + ") as foo group by mbi_num "
          + "having count(*) > 1) as s";

  /**
   * Initializes the {@link CcwRifLoadPreValidateInterface} prior to pre-validating Synthea data
   * properties. Mainly used to borrow a database {@link Connection}.
   *
   * @param appState the {@link PipelineApplicationState} for the overall application
   */
  @Override
  public void init(PipelineApplicationState appState) {
    this.appState = appState;
  }

  /**
   * Override of {@link CcwRifLoadPreValidateInterface#isValid(DataSetManifest)} which preforms
   * assorted checks to assert that Synthea load can proceed.
   *
   * @param manifest the {@link DataSetManifest} which will provide the various Synthea end-state
   *     property values that can be used to perform the pre-validation.
   * @return {@link boolean} asserting that the pre-validation succeeded (true) or failed (false).
   */
  @Override
  public boolean isValid(DataSetManifest manifest) throws Exception {
    if (appState == null) {
      throw new BadCodeMonkeyException("Invalid state; missing PipelineApplicationState");
    }
    if (manifest == null) {
      throw new BadCodeMonkeyException(
          String.format("null DataSetManifest object passed to isValid"));
    }
    if (!manifest.getPreValidationProperties().isPresent()) {
      return true;
    }
    return validateValues(manifest.getPreValidationProperties().get());
  }

  /**
   * private method that performs a multitude of database checks to verify that the Synthea data for
   * this {@link DataSetManifest} can proceed.
   *
   * @param props the {@link PreValidationProperties} specific to a Synthea pre-validation.
   * @return {@link boolean} asserting that the pre-validation succeeded (true) or failed (false).
   */
  boolean validateValues(PreValidationProperties props) throws Exception {
    boolean isValid = true;
    Connection dbConn = null;
    PreparedStatement ps = null;
    ResultSet rs = null;
    // by convention (implied), we'll ignore a validation step if the manifest
    // element has an empty value (xs:string) or 0 (zero for xs:long).
    try {
      // a dbConnection is a sparse resource...use carefully!
      dbConn = appState.getPooledDataSource().getConnection();
      // and we only need to read data
      dbConn.setReadOnly(true);
      // we'll wrap in a while loop to allow easy short-circuiting
      // if we hit a failure condition.
      while (isValid) {
        //
        // Range check BENEFICIARIES for value constraints on BENE_ID.
        //
        if (props.getBeneIdStart() != 0 && props.getBeneIdEnd() != 0) {
          LOGGER.debug("pre-validation starting for CHECK_PDE_CLAIMS_GROUP_ID");
          ps = dbConn.prepareStatement(CHECK_BENE_RANGE);
          ps.setLong(1, props.getBeneIdStart());
          ps.setLong(2, props.getBeneIdEnd());
          rs = ps.executeQuery();
          // we only have one possible column in the result set so we
          // can cheat using an index value of 1! True for all queries!
          isValid = (rs != null && rs.next() && (rs.getInt(1) < 1));
          ps.close();
          ps = null;
          rs = null;
          if (!isValid) {
            LOGGER.warn("pre-validation failed for CHECK_BENE_RANGE");
            break;
          }
        }
        // Range check CARRIER_CLAIMS table for acceptable CLM_ID and CARR_CLM_CNTL_NUM
        //
        if (props.getCarrClmCntlNumStart() != 0) {
          LOGGER.debug("pre-validation starting for CHECK_CARR_CLAIM_CNTL_NUM");
          String carr_claim_cntl_num = String.valueOf(props.getCarrClmCntlNumStart());
          ps = dbConn.prepareStatement(CHECK_CARR_CLAIM_CNTL_NUM);
          ps.setLong(1, props.getClmIdStart());
          ps.setString(2, carr_claim_cntl_num);
          rs = ps.executeQuery();
          isValid = (rs != null && rs.next() && (rs.getInt(1) < 1));
          ps.close();
          ps = null;
          rs = null;
          if (!isValid) {
            LOGGER.warn("pre-validation failed for CHECK_CARR_CLAIM_CNTL_NUM");
            break;
          }
        }
        // Range check of CLM_GRP_ID in a bunch of tables.
        //
        if (props.getClmGrpIdStart() != 0) {
          int i = 0;
          for (; i < CLAIMS_TABLES_GRP.length && isValid; i++) {
            LOGGER.debug(
                "pre-validation starting for CHECK_CLAIMS_GROUP_ID [{}]", CLAIMS_TABLES_GRP[i]);
            String sql = String.format(CHECK_CLAIMS_GROUP_ID, CLAIMS_TABLES_GRP[i]);
            ps = dbConn.prepareStatement(sql);
            ps.setLong(1, props.getClmGrpIdStart());
            ps.setLong(2, CLM_GRP_ID_END);
            rs = ps.executeQuery();
            isValid = (rs != null && rs.next() && (rs.getInt(1) < 1));
            ps.close();
            ps = null;
            rs = null;
          }
          if (!isValid) {
            LOGGER.warn(
                "pre-validation failed for CHECK_CLAIMS_GROUP_ID [{}]", CLAIMS_TABLES_GRP[i]);
            break;
          }
        }
        // Check PARTD_EVENTS for collision in PDE_ID and CLM_GRP_ID.
        //
        if (props.getPdeIdStart() != 0 && props.getClmGrpIdStart() != 0) {
          LOGGER.debug("pre-validation starting for CHECK_PDE_CLAIMS_GROUP_ID");
          ps = dbConn.prepareStatement(CHECK_PDE_CLAIMS_GROUP_ID);
          ps.setLong(1, props.getPdeIdStart());
          ps.setLong(2, props.getClmGrpIdStart());
          ps.setLong(3, CLM_GRP_ID_END);
          rs = ps.executeQuery();
          isValid = (rs != null && rs.next() && (rs.getInt(1) < 1));
          ps.close();
          ps = null;
          rs = null;
          if (!isValid) {
            LOGGER.warn("pre-validation failed for CHECK_PDE_CLAIMS_GROUP_ID");
            break;
          }
        }
        // Check BENEFICIARIES table for a collision HICN_UNHASHED or MBI_NUM.
        //
        if (props.getHicnStart() != null && props.getMbiStart() != null) {
          LOGGER.debug("pre-validation starting for CHECK_HICN_MBI_HASH");
          ps = dbConn.prepareStatement(CHECK_HICN_MBI_HASH);
          ps.setString(1, props.getHicnStart());
          ps.setString(2, props.getMbiStart());
          rs = ps.executeQuery();
          isValid = (rs != null && rs.next() && (rs.getInt(1) < 1));
          ps.close();
          ps = null;
          rs = null;
          if (!isValid) {
            LOGGER.warn("pre-validation failed for CHECK_HICN_MBI_HASH");
            break;
          }
        }
        // Range check of FI_DOC_CLM_CNTL_NUM in a bunch of tables.
        //
        if (props.getFiDocCntlNumStart() != null) {
          int i = 0;
          for (; i < CLAIMS_TABLES_DOC_CNTL.length && isValid; i++) {
            LOGGER.debug(
                "pre-validation starting for CHECK_FI_DOC_CNTL [{}]", CLAIMS_TABLES_DOC_CNTL[i]);
            String sql = String.format(CHECK_FI_DOC_CNTL, CLAIMS_TABLES_DOC_CNTL[i]);
            ps = dbConn.prepareStatement(sql);
            ps.setString(1, props.getFiDocCntlNumStart());
            rs = ps.executeQuery();
            isValid = (rs != null && rs.next() && (rs.getInt(1) < 1));
            ps.close();
            ps = null;
            rs = null;
          }
          if (!isValid) {
            LOGGER.warn(
                "pre-validation failed for CHECK_FI_DOC_CNTL [{}]", CLAIMS_TABLES_DOC_CNTL[i]);
            break;
          }
        }
        // Check for HICN or MBI hash collisions
        //
        LOGGER.debug("pre-validation starting for CHECK_MBI_DUPES");
        ps = dbConn.prepareStatement(CHECK_MBI_DUPES);
        rs = ps.executeQuery();
        isValid = (rs != null && rs.next() && (rs.getInt(1) < 1));
        ps.close();
        ps = null;
        rs = null;
        if (!isValid) {
          LOGGER.warn("pre-validation failed for CHECK_HICN_MBI_HASH");
        }
        break;
      }
    } catch (Exception e) {
      LOGGER.error(e.getMessage(), e);
      isValid = false;
    } finally {
      // clean up and/all db resources
      if (ps != null) {
        try {
          ps.close();
          ps = null;
        } catch (Exception ex) {
        }
      }
      if (dbConn != null) {
        try {
          dbConn.close();
          dbConn = null;
        } catch (Exception ex) {
        }
      }
    }
    return isValid;
  }
}