# Step 8 runtime/source finding: verify local archive before publication

During the PlexonPanel 3.4.1 end-to-end certification audit, the cold full-backup source was found to move the staged `.partial` ZIP into `restore-points/<backupId>.zip` before SHA-256 and `LocalBackupVerifier` completed.

That ordering could leave an unverified final ZIP in the restore-point namespace when hashing or local verification failed. The maintenance job would still fail closed, but the filesystem contract was weaker than the release requirement that a final restore-point archive must not be published before local verification succeeds.

The remediation:

- keeps archive creation in `staging/<backupId>.partial`;
- fsyncs the staged archive;
- hashes and locally verifies the staged archive while it is still `.partial`;
- checks expanded/source bytes before publication;
- atomically moves the staged archive to `restore-points/<backupId>.zip` only after verification succeeds;
- retains the existing failure cleanup of `.partial` files;
- adds regression coverage proving a corrupt staged archive cannot create a final ZIP and a verified staged archive is atomically published.

This is a mandatory production-certification fix. A new full backup must not be attempted in production until the change passes x64 and ARM64 CI, is merged, and the resulting Host artifact is deployed with exact provenance.

Runtime certification remains partial; this source remediation does not itself prove the production backup, off-site upload, retry-upload, recovery-resolution, or restore gates.
