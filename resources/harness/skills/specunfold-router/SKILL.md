---
name: specunfold-router
description: Restore the current SpecUnfold workspace, Profile and execution context at task start, conversation resumption or context compaction; disclose only specifications required for the current task or node.
---

1. When `.agents/harness/manifest.json` exists, read `.agents/harness/constitution.md`. Otherwise handle the task normally without installing or creating state.
2. Call `workflow_ops` with `action:"context"`, the chat `workspaceRoot` and current `intent`. Include the known `executionId`, or an explicit `profile` selection. The installed default Profile is used otherwise.
3. Consume the returned current state, required specification references, blocking conditions and next action. Select explicitly when multiple executions are returned.
4. Read only needed specification bodies/pages. Refresh context after resumption or compaction. A changed reference hash requires reading the changed requirements before submitting results.
5. Ordinary tasks stay stateless. First `bootstrap` requires the user's request/agreement, `workflowConfirmedByUser:true` and `workflowConfirmationSource:"user_explicit_request"` or `"user_confirmed_proposal"`. Never fabricate confirmation. Existing executions reuse stored confirmation; follow returned `runTemplate` and continuations without requesting approval again merely to resume.
6. Submit real facts, evidence and artifacts. Ask for the user's actual decision at confirmation nodes. A failure or missing requirement stays blocked; never replace it with an invented approval.
