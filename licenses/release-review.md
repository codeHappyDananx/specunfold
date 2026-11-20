# SpecUnfold 分发说明

应用代码采用 Apache-2.0，全文为根目录 `LICENSE`。第三方组件保留自己的许可和版权，实际 JAR、版本及哈希见 `dependencies.json`。

| 组件 | 当前分发方式 | 材料 |
|---|---|---|
| Logback core/classic 1.2.13 | 选择 EPL 1.0；未修改库，完整官方源码及构建文件随源码包和运行包提供 | `sources/logback_1.2.13/`：SOURCE.json、完整源码归档、原始许可、源码对应证据和构建入口 |
| Jackson annotations/core/databind 2.15.2 | Apache-2.0；内嵌解析器的 MIT、BSD 等原文分别保留 | `java/`、`upstream/com.fasterxml.jackson.core_jackson-core_2.15.2/` |
| SLF4J API 1.7.36 | MIT；保留官方完整原文和版权 | `java/`、`upstream/org.slf4j_slf4j-api_1.7.36/` |
| JSON Schema Validator 1.0.87 | 主库和 Apache Commons Validator routines 采用 Apache-2.0，保留 NOTICE | `upstream/com.networknt_json-schema-validator_1.0.87/` |
| Validator 内嵌 Unicode 15.0 数据 | Unicode-DFS-2016；保留版权和许可全文 | 同目录 `Unicode-DFS-2016-LICENSE.txt` |
| Validator 内嵌 JSON Schema 元模式 | 原作者提供 BSD-3-Clause 或 AFL-3.0，选择 BSD-3-Clause；保留原始双许可文本及声明 | 同目录 `JSON-Schema-LICENSE.txt`、`JSON-Schema-NOTICE.txt` |
| Validator 内嵌 RFC 5892 附录 B 数据 | IETF Trust Code Components BSD；保留 RFC 原始版权、BSD 文本和适用条款原件 | 同目录 `RFC5892-NOTICE.txt`、`IETF-Code-Components-BSD-LICENSE.txt`、`IETF-TLP-4.pdf` |
| itu 1.7.0 | Apache-2.0；保留完整许可和 JAR 中 THIRD-PARTY.txt | `upstream/com.ethlo.time_itu_1.7.0/`、`java/` |
| Jackson YAML 2.15.2 | Apache-2.0；保留完整许可和 JAR 中 NOTICE | `upstream/com.fasterxml.jackson.dataformat_jackson-dataformat-yaml_2.15.2/`、`java/` |
| SnakeYAML 2.0 | Apache-2.0；内嵌 Base64Coder 选择原作者提供的 Apache-2.0 选项，Google GData 转义器采用 Apache-2.0；保留各自原始版权头及 Base64Coder 完整选项声明 | `upstream/org.yaml_snakeyaml_2.0/` |

执行 `python scripts/audit-dependencies.py` 更新实际依赖清单，再执行 `python scripts/package-release.py`。打包检查根 LICENSE、运行 JAR、依赖及对应源码材料哈希，并检查 POM、JAR 法律文件存在和补充许可材料哈希。源码包和运行包均携带法律材料；分发时保持完整。

本项目只提供通用框架和 example Profile。公司代码权属、版权主体及公开发布授权仍需公司确认；本地构建和厂商关键词检查不能代替该决定。
