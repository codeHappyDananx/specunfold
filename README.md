# SpecUnfold

SpecUnfold 是独立的规范与工作流框架。它按当前节点返回规范引用、摘要和 SHA-256，按需披露正文，再检查提交的事实、产物和用户确认。工作流状态保存在工作区，服务重启后可以续接。

调用链：工作区探测 → 规范地图 → 当前节点引用 → 按需读取正文 → 提交事实与产物 → 门禁校验 → 继续或结束。

## Windows 安装包

面向使用者的 Windows x64 安装包包含精简 Java 运行环境和 Node.js，无须另外安装开发工具。
安装时可选择软件目录；安装后打开 SpecUnfold，选择代码项目目录，点击安装示例规范并复制助手连接配置。
软件安装与项目规范安装分开执行，不自动改写助手的全局配置。完整使用说明见 `scripts/windows/USER-GUIDE.md`。

维护者在 Windows x64、JDK 21 或更新版本以及 Python 3 环境中执行：

```powershell
./scripts/package-windows.ps1 -JavaHome 'C:/path/to/jdk'
```

脚本将校验后下载的 Maven、WiX 3.14.1 和 Node.js 放在 `.build/tools`，
构建并运行测试、更新实际依赖授权记录，再使用 `jlink` 和 `jpackage` 打包。
输出为 `dist/SpecUnfold-1.0.0.exe` 及校验清单，便携运行目录位于 `.build/windows/images/SpecUnfold`。
需要 MSI 格式时使用 `-InstallerType msi`。运行环境和构建工具随版本维护，不依赖用户的全局环境配置。
当前仅提供 Windows x64 安装，不包含代码签名或自动更新。

## 构建与启动

需要 JDK 8 或以上、Maven，以及支持 `node:` 标准模块的 Node.js。CLI 和示例脚本不需要 npm 安装。

```powershell
./build.ps1
node bin/specunfold.js install --workspace C:/workspaces/demo --profile example
node bin/specunfold.js serve
```

`build.ps1` 使用公共 Maven Central 配置和隔离缓存 `.build/m2`。产物为 `mcp-server/target/specunfold.jar` 和 `mcp-server/target/lib/*.jar`，必须一起保留。运行目录还应包含 `bin/`、`resources/profiles/`、本说明和第三方授权材料。也可执行 `./run.ps1` 或 `./run.sh`。

安装会将 Profile 复制到工作区的 `.agents/profiles/example`，同时安装 `.agents/harness/` 和 `.agents/skills/specunfold-router/SKILL.md`，在 `AGENTS.md`、`CLAUDE.md` 中写入 SpecUnfold 受管入口。同内容文件可以重复安装；Profile 或 Harness 文件存在不同内容、入口区块不完整时会报冲突，写入前检查全部目标文件。受管区块外的本地指令保持原样。外部 Profile 使用 `--profile-dir PATH`，`--profile ID` 指定安装标识；首装 Profile 记录为 `.agents/harness/manifest.json` 的 `defaultProfile`，后续安装其他 Profile 不改变它，调用时可显式指定 `profile`。

把 CLI 打印的 `command` 和 `args` 填入 MCP Router 的 stdio 服务配置。示例：

```json
{
  "command": "node",
  "args": ["C:/tools/specunfold/bin/specunfold.js", "serve"]
}
```

接入链路：客户端 → MCP Router → SpecUnfold stdio。安装复制 Profile 和通用 Agent 入口并打印配置；服务日志使用 stderr，stdout 用于 MCP 消息。

工作区选择顺序为调用参数 `workspaceRoot`、环境变量 `SPECUNFOLD_WORKSPACE_ROOT`、客户端 MCP roots。客户端声明 roots 能力后，服务在初始化完成时请求 `roots/list`，并响应 roots 变更通知刷新：多个根中只有一个安装了 `.agents/harness/manifest.json` 时选择它，单个根可直接使用；无法唯一选择时返回 `SELECT_WORKSPACE`、候选根和下一动作，不创建执行。未提供 roots 时使用服务工作目录。`projectPaths` 只描述关联工程，不改变聊天工作区。

Agent 读取项目指令或入口 Skill 后，在任务开始、会话续接和上下文压缩后先调用 `context`，取得当前工作区、Profile、执行状态和必要规范引用。已知执行编号时携带 `executionId`；多个未完成执行由调用方明确选择。普通任务不创建工作流，用户要求或同意持久化编排后才调用 `bootstrap`。宿主需要读取安装的项目指令或 Skill；MCP 服务启动本身不代表 Agent 的会话启动钩子。

```json
{"action":"context","workspaceRoot":"C:/workspaces/demo","intent":"delivery review"}
```

执行包已有当前节点引用、票据和 `runTemplate` 时，直接执行并提交；只在需要正文时按需读取，避免重复披露。续接刷新当前有效状态，历史按需读取，不在启动时整体加载。

协商到支持结构化结果的协议版本时，完整载荷只放在 `structuredContent`，`content` 文本仅提供短提示；协议 `2024-11-05`、`2025-03-26` 使用唯一的 `content` JSON 文本，不重复发送载荷。单条完整 JSON-RPC 响应最多 16 KiB（UTF-8 字节）；工具参数 `maxResponseBytes` 可指定 1024～16384 的整数。这是响应字节预算，不是精确模型 token 计数。

结果超出预算时，返回 `kind:"json_fragment"`、`executable:false` 的分页快照。按唯一 `next` 请求继续，依次拼接全部 `fragment`，核对 `sha256` 后解析完整 JSON；片段不能直接作为执行包或完整门禁使用。`offset`、`totalLength` 按 UTF-16 代码单元计数，使用服务返回的 `next.offset`。翻页只读取快照，不重做原动作。服务最多保留 32 份快照，每份最多 1 MiB；超出单份上限明确报错，需要缩小规范章节或状态页。快照被淘汰或服务重启后，重新调用 `context` 恢复状态，不重复 bootstrap 或重新提交已完成动作。

## 示例执行

下面 JSON 均为 MCP 工具 `workflow_ops` 的参数。`EXECUTION_ID` 替换为 bootstrap 返回值，`CURRENT_TICKET` 替换为当前节点最近一次披露返回的 `specTicket`。

```json
{"action":"workspace_probe","workspaceRoot":"C:/workspaces/demo","profile":"example"}
{"action":"spec_map","workspaceRoot":"C:/workspaces/demo","profile":"example"}
{"action":"validate","workspaceRoot":"C:/workspaces/demo","profile":"example"}
{"action":"bootstrap","workspaceRoot":"C:/workspaces/demo","profile":"example","intent":"delivery review","workflowConfirmedByUser":true,"workflowConfirmationSource":"user_explicit_request"}
```

首次 bootstrap 必须对应用户明确要求工作流或已同意持久化编排，声明 `workflowConfirmedByUser:true`，来源分别为 `user_explicit_request` 或 `user_confirmed_proposal`；不得虚构确认。已有执行续接沿用保存的确认，无须重复询问。bootstrap 返回 `prepare` 节点、当前规范引用与摘要、票据和续接参数。`review` 或 `复核` 意图会增加一条准备阶段的条件规范。默认响应不含正文。

```json
{"action":"spec","workspaceRoot":"C:/workspaces/demo","profile":"example","executionId":"EXECUTION_ID","includeContent":true}
```

读取正文后，在工作区创建 `delivery.txt`，写一项拟交付变更和一项可观察的验收结果。然后提交：

```json
{"action":"run","workspaceRoot":"C:/workspaces/demo","profile":"example","executionId":"EXECUTION_ID","nodeId":"prepare","specTicket":"CURRENT_TICKET","status":"DONE","facts":{"prepared":true},"evidence":{"note":"Read the preparation specification and wrote the delivery note."},"artifactPaths":["delivery.txt"]}
```

RUN_NODE 完成提交必须显式带 `facts` 对象、非空 `evidence` 对象和 `artifactPaths` 数组。缺文件、未声明产物路径、缺事实或事实为 false 都会返回 `BLOCKED`。修正后，读取当前节点并再次提交；门禁通过才进入 `confirm`。

无法完成当前节点时，提交 `status:"BLOCKED"` 和非空 `blockingReason` 描述阻碍。RUN_NODE 同时提交 `facts`、非空 `evidence` 和 `artifactPaths`，保存实际工作与阻碍；此时不校验 DONE 的完成门禁，也不推进节点。

在确认节点读取它的规范正文，向用户展示产物并等待明确答复。用当前票据提交用户的实际选择：

```json
{"action":"run","workspaceRoot":"C:/workspaces/demo","profile":"example","executionId":"EXECUTION_ID","nodeId":"confirm","specTicket":"CURRENT_TICKET","status":"DONE","decision":{"approved":true}}
```

`approved:false` 保持阻断，后续可以再次取得用户批准。确认通过后进入 `verify`，读取当前规范，再运行声明的只读检查脚本：

```json
{"action":"run_profile_script","workspaceRoot":"C:/workspaces/demo","profile":"example","scriptId":"check_artifact","input":{"artifactPath":"delivery.txt"}}
```

脚本以工作区为当前目录，从 stdin 读取 JSON，在 stdout 返回一个 JSON 对象，包含 `ok`、`exists`、`path`、`bytes`。检查文件内容确实符合规范后提交：

```json
{"action":"run","workspaceRoot":"C:/workspaces/demo","profile":"example","executionId":"EXECUTION_ID","nodeId":"verify","specTicket":"CURRENT_TICKET","status":"DONE","facts":{"verified":true},"artifactPaths":["delivery.txt"],"evidence":{"note":"Checked the change description and observable acceptance result."}}
```

门禁通过后返回 `DONE`。脚本结果本身不会替代事实提交、用户确认或产物门禁。

## 规范变更与恢复

```json
{"action":"spec_check","workspaceRoot":"C:/workspaces/demo","profile":"example","executionId":"EXECUTION_ID","nodeId":"verify","specTicket":"CURRENT_TICKET"}
{"action":"state","workspaceRoot":"C:/workspaces/demo","profile":"example","executionId":"EXECUTION_ID"}
{"action":"next","workspaceRoot":"C:/workspaces/demo","profile":"example","executionId":"EXECUTION_ID"}
```

规范、工作流或门禁引用的合同及 Schema 变更使旧票据失效。先重新调用 `spec` 读取当前规范，核对变更后使用新票据提交。不要复用其他节点的票据。`state` 默认返回数量摘要；显式 `section` 读取 `facts`、`evidence`、`artifactPaths`、`history` 或 `currentState`，`offset`/`limit` 分页（默认 20 项，最多 100 项）。`next` 返回当前执行包。

`currentState` 记录 `goal`、`design`、`constraints`、`context`、`acceptance`，只有有效内容变化才增加 `revision`。`run` 返回本次 `stateDelta` 和下一节点。新会话及压缩后重新取 `context`；同一上下文中可传 `contextEpoch` 和 `knownSpecHashes:{"spec/path.md":"sha256"}`，相同版本省去正文。新上下文使用新 epoch 并清空已知哈希。

普通任务可直接 `spec`，无需 executionId 或创建状态。可指定 `workflow`、`nodeId` 或声明过的 `specPath`；`includeContent:true` 时正文默认每份 4096 个 UTF-16 代码单元，最多 8192，按 `nextRead` 续读。既有执行只允许读取当前节点所需正文。

续接时检查已完成节点声明及提交的产物。文件缺失返回 `BLOCKED` 和 `missingArtifacts`；取得用户恢复确认后，对 `context` 提交 `reopenNodeIds` 和 `recoveryConfirmed:true`，重开生产节点并清除下游状态，再重新执行。服务不会自动重建丢失文件。

已有执行仅支持节点顺序和标识保持一致的续接。工作流增删节点、重排节点或变更节点标识后，应重新 bootstrap 创建执行，不沿用原 executionId。

状态位于工作区 `.specunfold/workflow-state/<profile>/<executionId>.json`。重启服务后，使用相同工作区、Profile 和 executionId 调用 `state` 或 `next`，再按当前节点继续；不需要重新 bootstrap。

## 自定义 Profile

| 文件 | 用途 |
|---|---|
| `spec-map.json` | 默认工作流、意图路由、按节点配置的基础与条件规范 |
| `workflows/<id>.json` | 有序节点、事实断言、产物和完成门禁 |
| `spec/*.md` | 通用规范正文；规范引用使用 Profile 根相对路径 |
| `runtime/scripts.json` | 声明脚本运行时、入口、资源、参数和超时 |
| `runtime/*.js` | stdin/stdout JSON 脚本 |
| `runtime/project-context.json` | 工程角色、别名、术语映射和目录发现标记 |
| `gates/gates.json` | 可复用完成门禁，节点引用 `gates.<id>` 的 ID |
| `contracts/*.json`、`schemas/*.json` | Profile 自行声明的文档合同和 JSON Schema |

加载顺序为工作区 `.agents/profiles/<id>`、工作区 `resources/profiles/<id>`、项目 `resources/profiles/<id>`，最后才使用打包资源。选定一个来源后，各文件均从该来源加载。脚本会以声明运行时在工作区执行，安装和运行前应审查自定义 Profile 的脚本。

门禁既可在节点内声明，也可使用 `gates/gates.json` 的 ID。目录、产物名、字段和正则全部由 Profile 声明。

| 类型 | 声明字段 |
|---|---|
| `fact` / `evidence` | `path`、`operator`，支持 `exists`、`notEmpty`、`equals`、`in`、`empty`；比较值用 `value`，前提用相同契约的 `when` |
| `artifact` | 当前节点提交且存在的 `path` |
| `document_contract` | `contract` 指向 Profile JSON，声明 `artifact`、`requiredPatterns`、`requiredAnyPatternGroups`、`forbiddenPatterns` |
| `json_schema` | `artifact`、`schema`；Draft7，支持 Profile 内相对 `$ref`，不读取网络或外部文件 |
| `json_item_directory_bundle` | `sourceArtifact`、`groupsPath`、`itemsField`、`idField`、`root`、`directorySeparator`、`requiredFiles` |
| `json_document_traceability` | `sourceArtifact`、`sourceArrayPath`、`sourceIdField`、`targetArtifact`、`targetSectionStartPattern`、可选 `targetSectionEndPattern`、`targetIdPattern` |
| `provenance` | `artifact`、`recordArtifact`、`recordsPath`、`allowedProducers`、`allowedActions`；核对记录的路径、SHA-256、mtime及生成入口 |

`provenance` 核对提交证据与文件一致性，不认证不可信生成者的身份。ASK_USER 默认等待真实用户选择；仅调用方显式 `skipConfirm:true`、`decision:{"approved":true,"autoApproved":true}` 且节点 `autoDecisionPolicy.conditions` 全通过时使用声明式策略。条件类型为 `path_in` / `path_not_in`（`artifact`、`path`、`values`）或 `no_blocking_items`（`artifact`、`paths`、`blockingFields`、`blockingSeverities`，有严重度规则时声明 `severityField`）。没有隐式业务字段或默认自动批准。

意图命中多个不同工作流时必须显式选择 `workflow`。`workflowResolution.argumentRoutes` 根据 `parameters` 中声明的参数名路由；节点 `operationModes` 筛选操作模式，跳过项保存为 `SKIPPED`。工作流与节点的 `executionInstruction` 字符串数组合并去重披露。节点 `requiredSpec` 必须被当前规范地图覆盖。示例采用三个节点：`prepare RUN_NODE → confirm ASK_USER → verify RUN_NODE`。

`bootstrap.parameters` 在创建执行时冻结。工作流和门禁字符串可引用 `${parameters.key}`、`${executionId}`；参数必须为非空字符串，缺失时直接报错。重启继续使用冻结参数。当前节点 `allowedTools` 声明的工具可取得规范地图中对应的 `actionParameterTemplates`，无需再次加载全图。

工程路径用工具参数 `projectPaths` 或工作区 `.agents/context.json.projectPaths` 声明，不改变聊天所属 `workspaceRoot`。Profile 的 `projects` 可声明 `key`、`aliases`、`intentKeywords`、`discoverAlways` 及 `discovery` 标记，歧义或缺失目录明确返回待确认项。`ignore_workspace` 使用显式 `reason` 保存忽略选择。需要固定发行资源时，启动 JVM 加 `-Dspecunfold.profileRoot=<Profiles总目录>`；指定后缺少资源直接报错。

## 第三方授权

SpecUnfold 应用代码采用 Apache-2.0，全文见根目录 `LICENSE`。第三方组件保留自身授权；Logback 1.2.13 采用 EPL 1.0 路径，未修改的官方完整源码和构建文件位于 `licenses/sources/logback_1.2.13/`，随源码包和运行包提供。

构建后执行 `python scripts/audit-dependencies.py --fetch-upstream`，收集实际 `target/lib` 依赖的原始授权文件、POM 父链和官方补充原文。离线重复生成使用 `python scripts/audit-dependencies.py`。发行时附带 `LICENSE`、`THIRD-PARTY-NOTICES.md` 和 `licenses/`。版权主体及源码公开授权仍须公司确认。

本地验收：`python scripts/smoke-stdio.py` 检查按需披露、规范变更、事实和产物门禁、用户确认、脚本调用及重启续接；`node scripts/verify-cli.mjs` 检查安装 CLI。发行使用 `python scripts/package-release.py`，源码和运行包写入相邻的 `releases/`。
