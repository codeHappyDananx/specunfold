# Prepare the delivery note

Create `delivery.txt` at the workspace root. Describe one intended change and one observable acceptance result in plain text. Use project-neutral examples and include no credentials or private organizational material.

Read this specification's body with the `spec` action before acting. Submit the current specification ticket, `facts`, a non-empty `evidence` object describing the preparation and `artifactPaths` with the node result. Report `facts.prepared: true` only after the file exists. The artifact gate blocks completion when the file is absent.

The next node asks the user to approve the note. Preparation does not imply user approval.
