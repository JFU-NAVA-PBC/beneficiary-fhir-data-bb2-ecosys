package gov.cms.bfd.pipeline.ccw.rif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import gov.cms.bfd.model.rif.RifFileType;
import gov.cms.bfd.model.rif.entities.S3ManifestFile;
import gov.cms.bfd.model.rif.samples.StaticRifResource;
import gov.cms.bfd.pipeline.AbstractLocalStackS3Test;
import gov.cms.bfd.pipeline.PipelineTestUtils;
import gov.cms.bfd.pipeline.ccw.rif.extract.ExtractionOptions;
import gov.cms.bfd.pipeline.ccw.rif.extract.s3.DataSetManifest;
import gov.cms.bfd.pipeline.ccw.rif.extract.s3.DataSetManifest.DataSetManifestEntry;
import gov.cms.bfd.pipeline.ccw.rif.extract.s3.DataSetQueue;
import gov.cms.bfd.pipeline.ccw.rif.extract.s3.DataSetTestUtilities;
import gov.cms.bfd.pipeline.ccw.rif.extract.s3.MockDataSetMonitorListener;
import gov.cms.bfd.pipeline.ccw.rif.extract.s3.S3FileManager;
import gov.cms.bfd.pipeline.ccw.rif.extract.s3.S3ManifestDbDao;
import gov.cms.bfd.pipeline.ccw.rif.extract.s3.task.S3TaskManager;
import gov.cms.bfd.pipeline.sharedutils.PipelineJobOutcome;
import java.net.URL;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.utils.StringUtils;

/** Integration tests for {@link CcwRifLoadJob}. */
@ExtendWith(MockitoExtension.class)
final class CcwRifLoadJobIT extends AbstractLocalStackS3Test {
  private static final Logger LOGGER = LoggerFactory.getLogger(CcwRifLoadJobIT.class);

  /** Used to capture status updates from the job. */
  @Mock private CcwRifLoadJobStatusReporter statusReporter;

  /**
   * Tests {@link CcwRifLoadJob} when run against an empty bucket.
   *
   * @throws Exception (exceptions indicate test failure)
   */
  @Test
  public void emptyBucketTest() throws Exception {
    String bucket = null;
    try {
      // Create the (empty) bucket to run against.
      bucket = s3Dao.createTestBucket();
      ExtractionOptions options =
          new ExtractionOptions(bucket, Optional.empty(), Optional.empty(), s3ClientConfig);
      LOGGER.info("Bucket created: '{}:{}'", s3Dao.readListBucketsOwner(), bucket);

      // Run the job.
      final var listener = new MockDataSetMonitorListener();
      final var s3TaskManager = spy(new S3TaskManager(options, s3ClientFactory));
      final var pipelineAppState = PipelineTestUtils.get().getPipelineApplicationState();
      final var s3FilesDao = new S3ManifestDbDao(pipelineAppState.getEntityManagerFactory());
      final var s3FileCache = spy(new S3FileManager(pipelineAppState.getMetrics(), s3Dao, bucket));
      final var dataSetQueue =
          new DataSetQueue(
              pipelineAppState.getClock(),
              pipelineAppState.getMetrics(),
              s3FilesDao,
              s3FileCache,
              s3TaskManager);
      try (CcwRifLoadJob ccwJob =
          new CcwRifLoadJob(
              pipelineAppState,
              options,
              dataSetQueue,
              listener,
              false,
              Optional.empty(),
              statusReporter)) {
        ccwJob.call();
      }

      // Verify that no data sets were generated.
      assertEquals(1, listener.getNoDataAvailableEvents());
      assertEquals(0, listener.getDataEvents().size());

      // verifies that status events were published in the correct order
      final var statusOrder = Mockito.inOrder(statusReporter);
      statusOrder.verify(statusReporter).reportCheckingBucketForManifest();
      statusOrder.verify(statusReporter).reportNothingToDo();
      statusOrder.verifyNoMoreInteractions();

      // verifies that close called close on AutoCloseable dependencies
      verify(s3TaskManager).close();
      verify(s3FileCache).close();
    } finally {
      if (StringUtils.isNotBlank(bucket)) s3Dao.deleteTestBucket(bucket);
    }
  }

  /**
   * Tests {@link CcwRifLoadJob} when run against a bucket with a single data set.
   *
   * @throws Exception (exceptions indicate test failure)
   */
  @Test
  public void singleDataSetTest() throws Exception {
    validateLoadAtLocations(
        CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
        CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS,
        List.of(
            StaticRifResource.SAMPLE_A_BENES.getResourceUrl(),
            StaticRifResource.SAMPLE_A_CARRIER.getResourceUrl()));
  }

  /**
   * Tests {@link CcwRifLoadJob} when run against a bucket with a single data set within
   * Synthetic/Incoming.
   *
   * @throws Exception (exceptions indicate test failure)
   */
  @Test
  public void singleSyntheticDataSetTest() throws Exception {
    validateLoadAtLocations(
        CcwRifLoadJob.S3_PREFIX_PENDING_SYNTHETIC_DATA_SETS,
        CcwRifLoadJob.S3_PREFIX_COMPLETED_SYNTHETIC_DATA_SETS,
        List.of(
            StaticRifResource.SAMPLE_SYNTHEA_BENES2011.getResourceUrl(),
            StaticRifResource.SAMPLE_SYNTHEA_CARRIER.getResourceUrl()));
  }

  /**
   * Tests {@link CcwRifLoadJob} when run with data in the Synthetic/Incoming and Incoming folders.
   * Data should be read and moved into the respective Done and Synthetic/Done folders.
   *
   * @throws Exception (exceptions indicate test failure)
   */
  @Test
  public void multipleDataSetsWithSyntheticTest() throws Exception {
    String bucket = null;
    try {
      /*
       * Create the (empty) bucket to run against, and populate it with
       * two data sets.
       */
      bucket = s3Dao.createTestBucket();
      ExtractionOptions options =
          new ExtractionOptions(bucket, Optional.empty(), Optional.of(1), s3ClientConfig);
      LOGGER.info("Bucket created: '{}:{}'", s3Dao.readListBucketsOwner(), bucket);

      DataSetManifest manifest =
          new DataSetManifest(
              Instant.now(),
              0,
              false,
              CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS,
              new DataSetManifestEntry("beneficiaries.rif", RifFileType.BENEFICIARY));
      DataSetManifest manifestSynthetic =
          new DataSetManifest(
              Instant.now().minus(1, ChronoUnit.DAYS),
              0,
              true,
              CcwRifLoadJob.S3_PREFIX_PENDING_SYNTHETIC_DATA_SETS,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_SYNTHETIC_DATA_SETS,
              new DataSetManifestEntry("carrier.rif", RifFileType.CARRIER),
              new DataSetManifestEntry("inpatient.rif", RifFileType.INPATIENT));

      // Add files to each location the test wants them in
      final String manifest1Key =
          putSampleFilesInTestBucket(
              bucket,
              CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
              manifest,
              List.of(StaticRifResource.SAMPLE_A_BENES.getResourceUrl()));
      final String manifest2Key =
          putSampleFilesInTestBucket(
              bucket,
              CcwRifLoadJob.S3_PREFIX_PENDING_SYNTHETIC_DATA_SETS,
              manifestSynthetic,
              List.of(
                  StaticRifResource.SAMPLE_A_CARRIER.getResourceUrl(),
                  StaticRifResource.SAMPLE_A_INPATIENT.getResourceUrl()));

      // Run the job.
      final var listener = new MockDataSetMonitorListener();
      final var s3TaskManager = spy(new S3TaskManager(options, s3ClientFactory));
      final var pipelineAppState = PipelineTestUtils.get().getPipelineApplicationState();
      final var s3FilesDao = new S3ManifestDbDao(pipelineAppState.getEntityManagerFactory());
      final var s3FileCache = spy(new S3FileManager(pipelineAppState.getMetrics(), s3Dao, bucket));
      final var dataSetQueue =
          new DataSetQueue(
              pipelineAppState.getClock(),
              pipelineAppState.getMetrics(),
              s3FilesDao,
              s3FileCache,
              s3TaskManager);
      try (CcwRifLoadJob ccwJob =
          new CcwRifLoadJob(
              pipelineAppState,
              options,
              dataSetQueue,
              listener,
              false,
              Optional.empty(),
              statusReporter)) {
        // Process both sets
        ccwJob.call();
        ccwJob.call();
      }

      // Verify what was handed off to the DataSetMonitorListener.
      assertEquals(0, listener.getNoDataAvailableEvents());
      assertEquals(2, listener.getDataEvents().size());

      // verifies that status events were published
      verify(statusReporter, times(2)).reportCheckingBucketForManifest();
      verify(statusReporter, atLeast(1)).reportAwaitingManifestData(manifest1Key);
      verify(statusReporter).reportProcessingManifestData(manifest1Key);
      verify(statusReporter).reportCompletedManifest(manifest1Key);
      verify(statusReporter, atLeast(1)).reportAwaitingManifestData(manifest2Key);
      verify(statusReporter).reportProcessingManifestData(manifest2Key);
      verify(statusReporter).reportCompletedManifest(manifest2Key);
      verify(statusReporter, times(2)).reportCheckingBucketForManifest();
      verifyNoMoreInteractions(statusReporter);

      verifyManifestFileStatus(s3FilesDao, manifest1Key, S3ManifestFile.ManifestStatus.COMPLETED);
      verifyManifestFileStatus(s3FilesDao, manifest2Key, S3ManifestFile.ManifestStatus.COMPLETED);

      // verifies that close called close on AutoCloseable dependencies
      verify(s3TaskManager).close();
      verify(s3FileCache).close();

      // TODO BEGIN remove once S3 file moves are no longer necessary.
      // Expected to be changed as part of BFD-3129.
      /*
       * Verify that the datasets were moved to their respective locations.
       */
      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao,
          bucket,
          CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
          0,
          java.time.Duration.ofSeconds(10));
      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao,
          bucket,
          CcwRifLoadJob.S3_PREFIX_PENDING_SYNTHETIC_DATA_SETS,
          0,
          java.time.Duration.ofSeconds(10));
      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao,
          bucket,
          CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS,
          1 + manifest.getEntries().size(),
          java.time.Duration.ofSeconds(10));
      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao,
          bucket,
          CcwRifLoadJob.S3_PREFIX_COMPLETED_SYNTHETIC_DATA_SETS,
          1 + manifestSynthetic.getEntries().size(),
          java.time.Duration.ofSeconds(10));
      assertTrue(
          s3Dao.objectExists(
              bucket,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_SYNTHETIC_DATA_SETS
                  + "/"
                  + manifestSynthetic.getTimestampText()
                  + "/0_manifest.xml"));
      assertTrue(
          s3Dao.objectExists(
              bucket,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_SYNTHETIC_DATA_SETS
                  + "/"
                  + manifestSynthetic.getTimestampText()
                  + "/carrier.rif"));
      assertTrue(
          s3Dao.objectExists(
              bucket,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_SYNTHETIC_DATA_SETS
                  + "/"
                  + manifestSynthetic.getTimestampText()
                  + "/inpatient.rif"));
      assertTrue(
          s3Dao.objectExists(
              bucket,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS
                  + "/"
                  + manifest.getTimestampText()
                  + "/0_manifest.xml"));
      assertTrue(
          s3Dao.objectExists(
              bucket,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS
                  + "/"
                  + manifest.getTimestampText()
                  + "/beneficiaries.rif"));
      // TODO END remove once S3 file moves are no longer necessary.
    } finally {
      if (StringUtils.isNotBlank(bucket)) s3Dao.deleteTestBucket(bucket);
    }
  }

  /**
   * Tests {@link CcwRifLoadJob} when run once against a bucket with three non-synthetic data sets
   * the job processes the first one and exits.
   *
   * @throws Exception (exceptions indicate test failure)
   */
  @Test
  public void multipleDataSetsTest() throws Exception {
    String bucket = null;
    try {
      /*
       * Create the (empty) bucket to run against, and populate it with
       * two data sets.
       */
      bucket = s3Dao.createTestBucket();
      ExtractionOptions options =
          new ExtractionOptions(bucket, Optional.empty(), Optional.of(1), s3ClientConfig);
      LOGGER.info("Bucket created: '{}:{}'", s3Dao.readListBucketsOwner(), bucket);
      DataSetManifest manifestA =
          new DataSetManifest(
              Instant.now().minus(1L, ChronoUnit.HOURS),
              0,
              true,
              CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS,
              new DataSetManifestEntry("beneficiaries.rif", RifFileType.BENEFICIARY));
      final String manifestAKey = DataSetTestUtilities.putObject(s3Dao, bucket, manifestA);
      DataSetTestUtilities.putObject(
          s3Dao,
          bucket,
          manifestA,
          manifestA.getEntries().getFirst(),
          StaticRifResource.SAMPLE_A_BENES.getResourceUrl());
      DataSetManifest manifestB =
          new DataSetManifest(
              manifestA.getTimestampText(),
              1,
              true,
              CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS,
              new DataSetManifestEntry("pde.rif", RifFileType.PDE));
      final String manifestBKey = DataSetTestUtilities.putObject(s3Dao, bucket, manifestB);
      DataSetTestUtilities.putObject(
          s3Dao,
          bucket,
          manifestB,
          manifestB.getEntries().getFirst(),
          StaticRifResource.SAMPLE_A_BENES.getResourceUrl());
      DataSetManifest manifestC =
          new DataSetManifest(
              Instant.now(),
              0,
              true,
              CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS,
              new DataSetManifestEntry("carrier.rif", RifFileType.CARRIER));
      DataSetTestUtilities.putObject(s3Dao, bucket, manifestC);
      DataSetTestUtilities.putObject(
          s3Dao,
          bucket,
          manifestC,
          manifestC.getEntries().getFirst(),
          StaticRifResource.SAMPLE_A_CARRIER.getResourceUrl());

      // Run the job.
      final var listener = new MockDataSetMonitorListener();
      final var s3TaskManager = spy(new S3TaskManager(options, s3ClientFactory));
      final var pipelineAppState = PipelineTestUtils.get().getPipelineApplicationState();
      final var s3FilesDao = new S3ManifestDbDao(pipelineAppState.getEntityManagerFactory());
      final var s3FileCache = spy(new S3FileManager(pipelineAppState.getMetrics(), s3Dao, bucket));
      final var dataSetQueue =
          new DataSetQueue(
              pipelineAppState.getClock(),
              pipelineAppState.getMetrics(),
              s3FilesDao,
              s3FileCache,
              s3TaskManager);
      try (CcwRifLoadJob ccwJob =
          new CcwRifLoadJob(
              pipelineAppState,
              options,
              dataSetQueue,
              listener,
              false,
              Optional.empty(),
              statusReporter)) {
        // process only the first data set
        assertEquals(PipelineJobOutcome.WORK_DONE, ccwJob.call());
      }

      // Verify what was handed off to the DataSetMonitorListener.
      assertEquals(0, listener.getNoDataAvailableEvents());
      assertEquals(1, listener.getDataEvents().size());
      assertEquals(manifestA.getTimestamp(), listener.getDataEvents().get(0).getTimestamp());
      assertEquals(
          manifestA.getEntries().size(), listener.getDataEvents().get(0).getFileEvents().size());

      // verifies that status events were published
      verify(statusReporter).reportCheckingBucketForManifest();
      verify(statusReporter, atLeast(1)).reportAwaitingManifestData(manifestAKey);
      verify(statusReporter).reportProcessingManifestData(manifestAKey);
      verify(statusReporter).reportCompletedManifest(manifestAKey);
      verifyNoMoreInteractions(statusReporter);

      verifyManifestFileStatus(s3FilesDao, manifestAKey, S3ManifestFile.ManifestStatus.COMPLETED);
      verifyManifestFileStatus(s3FilesDao, manifestBKey, S3ManifestFile.ManifestStatus.DISCOVERED);

      // verifies that close called close on AutoCloseable dependencies
      verify(s3TaskManager).close();
      verify(s3FileCache).close();

      // TODO BEGIN remove once S3 file moves are no longer necessary.
      // Expected to be changed as part of BFD-3129.
      /*
       * Verify that the first data set was renamed and the second is
       * still there.
       */
      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao,
          bucket,
          CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
          1 + manifestB.getEntries().size() + 1 + manifestC.getEntries().size(),
          java.time.Duration.ofSeconds(10));
      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao,
          bucket,
          CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS,
          1 + manifestA.getEntries().size(),
          java.time.Duration.ofSeconds(10));
      // TODO END remove once S3 file moves are no longer necessary.
    } finally {
      if (StringUtils.isNotBlank(bucket)) s3Dao.deleteTestBucket(bucket);
    }
  }

  /**
   * Tests {@link CcwRifLoadJob} when run against a bucket with a single data set that should be
   * skipped (per {@link ExtractionOptions#getDataSetFilter()}).
   *
   * @throws Exception (exceptions indicate test failure)
   */
  @Test
  public void skipDataSetTest() throws Exception {
    String bucket = null;
    try {
      /*
       * Create the (empty) bucket to run against, and populate it with a
       * data set.
       */
      bucket = s3Dao.createTestBucket();
      ExtractionOptions options =
          new ExtractionOptions(
              bucket, Optional.of(RifFileType.PDE), Optional.empty(), s3ClientConfig);
      LOGGER.info("Bucket created: '{}:{}'", s3Dao.readListBucketsOwner(), bucket);
      DataSetManifest manifest =
          new DataSetManifest(
              Instant.now(),
              0,
              true,
              CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS,
              new DataSetManifestEntry("beneficiaries.rif", RifFileType.BENEFICIARY),
              new DataSetManifestEntry("carrier.rif", RifFileType.CARRIER));
      final var manifestKey = DataSetTestUtilities.putObject(s3Dao, bucket, manifest);
      DataSetTestUtilities.putObject(
          s3Dao,
          bucket,
          manifest,
          manifest.getEntries().get(0),
          StaticRifResource.SAMPLE_A_BENES.getResourceUrl());
      DataSetTestUtilities.putObject(
          s3Dao,
          bucket,
          manifest,
          manifest.getEntries().get(1),
          StaticRifResource.SAMPLE_A_CARRIER.getResourceUrl());

      // Run the job.
      final var listener = new MockDataSetMonitorListener();
      final var s3TaskManager = spy(new S3TaskManager(options, s3ClientFactory));
      final var pipelineAppState = PipelineTestUtils.get().getPipelineApplicationState();
      final var s3FilesDao = new S3ManifestDbDao(pipelineAppState.getEntityManagerFactory());
      final var s3FileCache = spy(new S3FileManager(pipelineAppState.getMetrics(), s3Dao, bucket));
      final var dataSetQueue =
          new DataSetQueue(
              pipelineAppState.getClock(),
              pipelineAppState.getMetrics(),
              s3FilesDao,
              s3FileCache,
              s3TaskManager);
      try (CcwRifLoadJob ccwJob =
          new CcwRifLoadJob(
              pipelineAppState,
              options,
              dataSetQueue,
              listener,
              false,
              Optional.empty(),
              statusReporter)) {
        ccwJob.call();
      }

      // Verify what was handed off to the DataSetMonitorListener.
      assertEquals(1, listener.getNoDataAvailableEvents());
      assertEquals(0, listener.getDataEvents().size());

      // verifies that status events were published in the correct order
      final var statusOrder = Mockito.inOrder(statusReporter);
      statusOrder.verify(statusReporter).reportCheckingBucketForManifest();
      statusOrder.verify(statusReporter).reportNothingToDo();
      statusOrder.verifyNoMoreInteractions();

      verifyManifestFileStatus(s3FilesDao, manifestKey, S3ManifestFile.ManifestStatus.DISCOVERED);

      // verifies that close called close on AutoCloseable dependencies
      verify(s3TaskManager).close();
      verify(s3FileCache).close();

      // TODO BEGIN remove once S3 file moves are no longer necessary.
      // Expected to be changed as part of BFD-3129.
      // Verify that the data set was not renamed.
      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao,
          bucket,
          CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
          1 + manifest.getEntries().size(),
          java.time.Duration.ofSeconds(10));
      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao,
          bucket,
          CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS,
          0,
          java.time.Duration.ofSeconds(10));
      // TODO END remove once S3 file moves are no longer necessary.
    } finally {
      if (StringUtils.isNotBlank(bucket)) s3Dao.deleteTestBucket(bucket);
    }
  }

  /**
   * Tests {@link CcwRifLoadJob} when run against a bucket with a single data set that should be
   * skipped due to a future date.
   *
   * @throws Exception (exceptions indicate test failure)
   */
  @Test
  public void skipDataSetTestForFutureManifestDate() throws Exception {
    String bucket = null;
    try {
      /*
       * Create the (empty) bucket to run against, and populate it with a
       * data set.
       */
      bucket = s3Dao.createTestBucket();
      ExtractionOptions options =
          new ExtractionOptions(bucket, Optional.empty(), Optional.empty(), s3ClientConfig);
      LOGGER.info("Bucket created: '{}:{}'", s3Dao.readListBucketsOwner(), bucket);
      DataSetManifest manifest =
          new DataSetManifest(
              Instant.now().plus(3, ChronoUnit.DAYS),
              0,
              true,
              CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
              CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS,
              new DataSetManifestEntry("beneficiaries.rif", RifFileType.BENEFICIARY),
              new DataSetManifestEntry("carrier.rif", RifFileType.CARRIER));
      final var manifestKey = DataSetTestUtilities.putObject(s3Dao, bucket, manifest);
      DataSetTestUtilities.putObject(
          s3Dao,
          bucket,
          manifest,
          manifest.getEntries().get(0),
          StaticRifResource.SAMPLE_A_BENES.getResourceUrl());
      DataSetTestUtilities.putObject(
          s3Dao,
          bucket,
          manifest,
          manifest.getEntries().get(1),
          StaticRifResource.SAMPLE_A_CARRIER.getResourceUrl());

      // Run the job.
      final var listener = new MockDataSetMonitorListener();
      final var s3TaskManager = spy(new S3TaskManager(options, s3ClientFactory));
      final var pipelineAppState = PipelineTestUtils.get().getPipelineApplicationState();
      final var s3FilesDao = new S3ManifestDbDao(pipelineAppState.getEntityManagerFactory());
      final var s3FileCache = spy(new S3FileManager(pipelineAppState.getMetrics(), s3Dao, bucket));
      final var dataSetQueue =
          new DataSetQueue(
              pipelineAppState.getClock(),
              pipelineAppState.getMetrics(),
              s3FilesDao,
              s3FileCache,
              s3TaskManager);
      try (CcwRifLoadJob ccwJob =
          new CcwRifLoadJob(
              pipelineAppState,
              options,
              dataSetQueue,
              listener,
              false,
              Optional.empty(),
              statusReporter)) {
        ccwJob.call();
      }

      // Verify what was handed off to the DataSetMonitorListener.
      assertEquals(1, listener.getNoDataAvailableEvents());
      assertEquals(0, listener.getDataEvents().size());

      // verifies that status events were published in the correct order
      final var statusOrder = Mockito.inOrder(statusReporter);
      statusOrder.verify(statusReporter).reportCheckingBucketForManifest();
      statusOrder.verify(statusReporter).reportNothingToDo();
      statusOrder.verifyNoMoreInteractions();

      // Verify that the data set was not processed.
      verifyManifestFileStatus(s3FilesDao, manifestKey, null);

      // verifies that close called close on AutoCloseable dependencies
      verify(s3TaskManager).close();
      verify(s3FileCache).close();

      // TODO BEGIN remove once S3 file moves are no longer necessary.
      // Expected to be changed as part of BFD-3129.
      // Verify that the data set was not renamed.
      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao,
          bucket,
          CcwRifLoadJob.S3_PREFIX_PENDING_DATA_SETS,
          1 + manifest.getEntries().size(),
          java.time.Duration.ofSeconds(10));
      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao,
          bucket,
          CcwRifLoadJob.S3_PREFIX_COMPLETED_DATA_SETS,
          0,
          java.time.Duration.ofSeconds(10));
      // TODO END remove once S3 file moves are no longer necessary.
    } finally {
      if (StringUtils.isNotBlank(bucket)) s3Dao.deleteTestBucket(bucket);
    }
  }

  /**
   * Validate load given the input location to load files and output location to look for the files
   * once they're loaded.
   *
   * @param inputLocation the input location (bucket key) where files should be placed initially
   * @param expectedOutputLocation the expected output location (bucket key) where files are
   *     expected to be moved after processing
   * @param fileList the file list
   * @throws Exception the exception
   */
  private void validateLoadAtLocations(
      String inputLocation, String expectedOutputLocation, List<URL> fileList) throws Exception {
    String bucket = null;
    try {
      /*
       * Create the (empty) bucket to run against, and populate it with a
       * data set.
       */
      bucket = s3Dao.createTestBucket();
      ExtractionOptions options =
          new ExtractionOptions(bucket, Optional.empty(), Optional.empty(), s3ClientConfig);
      LOGGER.info("Bucket created: '{}:{}'", s3Dao.readListBucketsOwner(), bucket);

      DataSetManifest manifest =
          new DataSetManifest(
              Instant.now(),
              0,
              false,
              inputLocation,
              expectedOutputLocation,
              new DataSetManifestEntry("beneficiaries.rif", RifFileType.BENEFICIARY),
              new DataSetManifestEntry("carrier.rif", RifFileType.CARRIER));

      // Add files to each location the test wants them in
      var manifestS3Key = putSampleFilesInTestBucket(bucket, inputLocation, manifest, fileList);

      // Run the job.
      final var listener = new MockDataSetMonitorListener();
      final var s3TaskManager = spy(new S3TaskManager(options, s3ClientFactory));
      final var pipelineAppState = PipelineTestUtils.get().getPipelineApplicationState();
      final var s3FilesDao = new S3ManifestDbDao(pipelineAppState.getEntityManagerFactory());
      final var s3FileCache = spy(new S3FileManager(pipelineAppState.getMetrics(), s3Dao, bucket));
      final var dataSetQueue =
          new DataSetQueue(
              pipelineAppState.getClock(),
              pipelineAppState.getMetrics(),
              s3FilesDao,
              s3FileCache,
              s3TaskManager);
      try (CcwRifLoadJob ccwJob =
          new CcwRifLoadJob(
              pipelineAppState,
              options,
              dataSetQueue,
              listener,
              false,
              Optional.empty(),
              statusReporter)) {
        ccwJob.call();
      }

      // Verify what was handed off to the DataSetMonitorListener.
      assertEquals(0, listener.getNoDataAvailableEvents());
      assertEquals(1, listener.getDataEvents().size());
      assertEquals(manifest.getTimestamp(), listener.getDataEvents().get(0).getTimestamp());
      assertEquals(
          manifest.getEntries().size(), listener.getDataEvents().get(0).getFileEvents().size());

      verifyManifestFileStatus(s3FilesDao, manifestS3Key, S3ManifestFile.ManifestStatus.COMPLETED);

      // verifies that close called close on AutoCloseable dependencies
      verify(s3TaskManager).close();
      verify(s3FileCache).close();

      // TODO BEGIN remove once S3 file moves are no longer necessary.
      // Expected to be changed as part of BFD-3129.
      // Verify that the data set was renamed.
      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao, bucket, inputLocation, 0, java.time.Duration.ofSeconds(10));

      DataSetTestUtilities.waitForBucketObjectCount(
          s3Dao,
          bucket,
          expectedOutputLocation,
          1 + manifest.getEntries().size(),
          java.time.Duration.ofSeconds(10));
      // TODO END remove once S3 file moves are no longer necessary.
    } finally {
      if (StringUtils.isNotBlank(bucket)) s3Dao.deleteTestBucket(bucket);
    }
  }

  /**
   * Queries database for manifest record and verifies that its status matches the expected value.
   *
   * @param s3ManifestDbDao used to query the database
   * @param s3ManifestKey s3 key of the manifest
   * @param expectedStatus expected status value
   */
  private void verifyManifestFileStatus(
      S3ManifestDbDao s3ManifestDbDao,
      String s3ManifestKey,
      @Nullable S3ManifestFile.ManifestStatus expectedStatus) {
    S3ManifestFile manifestRecord = s3ManifestDbDao.readS3ManifestAndDataFiles(s3ManifestKey);
    if (expectedStatus == null) {
      assertNull(
          manifestRecord, "expected no record in database for manifest: key=" + s3ManifestKey);
    } else {
      assertNotNull(manifestRecord, "no record in database for manifest: key=" + s3ManifestKey);
      assertEquals(expectedStatus, manifestRecord.getStatus());
    }
  }

  /**
   * Put sample files in test specified bucket and key in s3.
   *
   * @param bucket the bucket to use for the test
   * @param location the key under which to put the file
   * @param manifest the manifest to use for the load files
   * @param resourcesToAdd the resource URLs to add to the bucket, see {@link StaticRifResource} for
   *     resource lists, should be in the order of the manifest
   * @return the uploaded manifest's S3 key
   */
  private String putSampleFilesInTestBucket(
      String bucket, String location, DataSetManifest manifest, List<URL> resourcesToAdd) {
    String manifestKey = DataSetTestUtilities.putObject(s3Dao, bucket, manifest, location);
    int index = 0;
    for (URL resource : resourcesToAdd) {
      DataSetTestUtilities.putObject(
          s3Dao, bucket, manifest, manifest.getEntries().get(index), resource, location);
      index++;
    }
    return manifestKey;
  }
}
