package gov.cms.bfd.pipeline.rda.grpc.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.spy;

import gov.cms.bfd.pipeline.rda.grpc.RdaChange;
import gov.cms.mpsm.rda.v1.RecordSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class AbstractClaimTransformerTest {

  private static final short PHASE_ONE = 1;
  private static final short PHASE_TWO = 2;
  private static final short PHASE_THREE = 3;
  private static final short PHASE_SEQ_ZERO = 0;
  private static final short PHASE_SEQ_ONE = 1;
  private static final short PHASE_SEQ_TWO = 2;

  public static Stream<Arguments> shouldTransformSource() {
    final String DATE = "1970-01-01";
    final String TIMESTAMP = "1970-01-01T00:00:00.000001Z";
    final LocalDate LOCAL_DATE = LocalDate.parse(DATE);
    final Instant INSTANT = OffsetDateTime.parse(TIMESTAMP).toInstant();

    return Stream.of(
        Arguments.arguments(
            "No errors - No arguments",
            RecordSource.newBuilder().build(),
            new RdaChange.Source(),
            List.of()),
        Arguments.arguments(
            "No errors - Just phase argument",
            RecordSource.newBuilder().setPhase("p1").build(),
            new RdaChange.Source(PHASE_ONE, null, null, null),
            List.of()),
        Arguments.arguments(
            "No errors - Phase and phase sequence arguments",
            RecordSource.newBuilder().setPhase("p2").setPhaseSeqNum(PHASE_SEQ_ZERO).build(),
            new RdaChange.Source(PHASE_TWO, PHASE_SEQ_ZERO, null, null),
            List.of()),
        Arguments.arguments(
            "No errors - Phase, phase sequence, and extract date",
            RecordSource.newBuilder()
                .setPhase("p3")
                .setPhaseSeqNum(PHASE_SEQ_ONE)
                .setExtractDate(DATE)
                .build(),
            new RdaChange.Source(PHASE_THREE, PHASE_SEQ_ONE, LOCAL_DATE, null),
            List.of()),
        Arguments.arguments(
            "No errors - All Arguments",
            RecordSource.newBuilder()
                .setPhase("p3")
                .setPhaseSeqNum(PHASE_SEQ_TWO)
                .setExtractDate(DATE)
                .setTransmissionTimestamp(TIMESTAMP)
                .build(),
            new RdaChange.Source(PHASE_THREE, PHASE_SEQ_TWO, LOCAL_DATE, INSTANT),
            List.of()),
        Arguments.arguments(
            "Invalid arguments",
            RecordSource.newBuilder()
                .setPhase("p4")
                .setPhaseSeqNum(-2)
                .setExtractDate("apple")
                .setTransmissionTimestamp("banana")
                .build(),
            new RdaChange.Source(),
            List.of(
                new DataTransformer.ErrorMessage(RdaChange.Source.Fields.phase, "is unknown phase"),
                new DataTransformer.ErrorMessage(RdaChange.Source.Fields.phaseSeqNum, "is signed"),
                new DataTransformer.ErrorMessage(
                    RdaChange.Source.Fields.extractDate, "invalid date"),
                new DataTransformer.ErrorMessage(
                    RdaChange.Source.Fields.transmissionTimestamp, "invalid timestamp"))));
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource
  void shouldTransformSource(
      // unused - This is just to give the test a name
      @SuppressWarnings("unused") final String testName,
      final RecordSource from,
      final RdaChange.Source expectedSource,
      final List<DataTransformer.ErrorMessage> expectedErrors) {
    AbstractClaimTransformer claimTransformer = spy(AbstractClaimTransformer.class);

    DataTransformer transformer = new DataTransformer();
    RdaChange.Source actual = claimTransformer.transformSource(from, transformer);

    assertEquals(expectedSource, actual);
    assertEquals(expectedErrors, transformer.getErrors());
  }
}