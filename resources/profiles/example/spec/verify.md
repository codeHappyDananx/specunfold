# Verify the approved delivery

Read `delivery.txt` and check that it states a change and an observable acceptance result. Invoke the declared `check_artifact` script with `artifactPath: "delivery.txt"`. The runner uses the workspace as its working directory. The script reads filesystem metadata and returns JSON; it does not create or modify the artifact.

When the artifact and its content pass the check, submit `facts.verified: true` and a non-empty `evidence.note` stating what was verified. A missing artifact, missing fact, false verification fact or missing evidence blocks completion. Correct the issue and submit the same node again.

Workflow state is saved after each operation. After restarting the server, resume using the same workspace and execution ID; completed nodes remain completed.
