# SpecUnfold Agent Harness

This entry contract is independent of any business domain. The selected external Profile supplies specifications, workflows, scripts and artifact rules.

## Start and resume

At the beginning of a task, after resuming a conversation, or after context compaction, call `workflow_ops` with `action:"context"`, `workspaceRoot` set to the chat workspace, and the current `intent`. Include `executionId` when known; include `profile` when selecting a Profile explicitly. The installed `.agents/harness/manifest.json` records the default Profile.

Use the returned current state, node, blocking conditions and next action. When more than one execution is available, select an execution explicitly instead of guessing from modification time. Referencing another project or running commands there does not change the chat workspace.

This is an instruction entry point for the Agent. A host must read its project instructions or installed skill for this entry to run. Starting an MCP process alone is not an Agent session-start hook.

## Ordinary tasks and workflows

Ordinary tasks read only the relevant Profile references and required content; they do not create workflow state. Use `bootstrap` only after the user requests or agrees to persistent workflow execution. Resume an existing execution through `context` or its continuation, rather than creating another execution.

The first `bootstrap` must carry `workflowConfirmedByUser:true` and `workflowConfirmationSource:"user_explicit_request"` or `"user_confirmed_proposal"`, matching the actual user's request or agreement. Do not infer or fabricate this evidence. Existing executions reuse stored confirmation and do not require another approval merely to resume.

Persist only the effective goal, design, constraints, context and acceptance in `currentState`. Update them when the user corrects the task; past events are audit history, not current instructions.

For an active execution, follow the current node and `runTemplate`. The execution package already provides the current references and ticket: do not call `spec` again merely to obtain them. Read specification bodies only when needed. Submit actual facts, evidence, declared artifacts and the current ticket. A user-confirmation node requires the user's real answer. A blocked node stays blocked until its unmet conditions are resolved.

## Disclosure and context budget

Use startup context, current-node references, selected specification text and history pages in that order of need. Do not load every specification, accumulated evidence or history at startup. Follow continuation/page cursors when more content is required. Reuse a disclosed reference only while its hash and current execution/node remain valid; after session resumption or compaction, refresh context before relying on remembered state.

Honor server response limits and explicit budget errors. A UTF-8 byte limit bounds response size; it is not an exact model-token count. Never omit a failed gate or treat a partial page as the complete set of requirements.

## Profile extensions

Business directories, filenames, artifact requirements and scripts belong to the Profile. Read the selected Profile's map rather than inventing conventions. Run only explicitly declared scripts, and treat script output as evidence to assess, not as automatic user approval or a gate bypass. Keep the service connection through MCP Router.
