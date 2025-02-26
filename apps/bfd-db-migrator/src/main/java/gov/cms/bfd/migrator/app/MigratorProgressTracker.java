package gov.cms.bfd.migrator.app;

import gov.cms.bfd.sharedutils.database.DatabaseMigrationProgress;
import java.util.function.Consumer;
import lombok.Data;

/**
 * Provides methods to report the current stage of processing. Wraps these into {@link
 * MigratorProgress} objects and passes them on to a {@link Consumer} to report them.
 */
@Data
public class MigratorProgressTracker {
  /** Sends the progress to some destination such as logs or SQS. */
  private final Consumer<MigratorProgress> progressReporter;

  /** Report app has started. */
  public void appStarted() {
    var progress = new MigratorProgress(MigratorProgress.Stage.Started, null);
    progressReporter.accept(progress);
  }

  /** Report app has connected to the database. */
  public void appConnected() {
    var progress = new MigratorProgress(MigratorProgress.Stage.Connected, null);
    progressReporter.accept(progress);
  }

  /** Report app has finished without errors. */
  public void appFinished() {
    var progress = new MigratorProgress(MigratorProgress.Stage.Finished, null);
    progressReporter.accept(progress);
  }

  /** Report app has finished due to an error. */
  public void appFailed() {
    var progress = new MigratorProgress(MigratorProgress.Stage.Failed, null);
    progressReporter.accept(progress);
  }

  /**
   * Reports app has completed a stage of the migration.
   *
   * @param migrationProgress migration stage just completed
   */
  public void migrating(DatabaseMigrationProgress migrationProgress) {
    var progress = new MigratorProgress(MigratorProgress.Stage.Migrating, migrationProgress);
    progressReporter.accept(progress);
  }
}
