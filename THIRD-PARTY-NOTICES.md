# Third-party dependency evidence

Generated from the actual thin-JAR runtime libraries by `python scripts/audit-dependencies.py`. Use `--fetch-upstream` to retrieve supplementary original legal texts from official sources. The machine-readable inventory, hashes, original packaged legal files and Maven parent-POM evidence are in `licenses/`.

| Maven coordinate | Declared licenses | Packaged legal files | Supplemental files |
|---|---|---:|---:|
| `com.ethlo.time:itu:1.7.0` | Apache License, Version 2.0 | 1 | 1 |
| `com.fasterxml.jackson.core:jackson-annotations:2.15.2` | The Apache Software License, Version 2.0 | 2 | 0 |
| `com.fasterxml.jackson.core:jackson-core:2.15.2` | The Apache Software License, Version 2.0 | 5 | 1 |
| `com.fasterxml.jackson.core:jackson-databind:2.15.2` | The Apache Software License, Version 2.0 | 2 | 0 |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2` | The Apache Software License, Version 2.0 | 2 | 1 |
| `com.networknt:json-schema-validator:1.0.87` | Apache License Version 2.0 | 0 | 8 |
| `ch.qos.logback:logback-classic:1.2.13` | Eclipse Public License - v 1.0; GNU Lesser General Public License | 0 | 3 |
| `ch.qos.logback:logback-core:1.2.13` | Eclipse Public License - v 1.0; GNU Lesser General Public License | 0 | 3 |
| `org.slf4j:slf4j-api:1.7.36` | MIT License | 0 | 1 |
| `org.yaml:snakeyaml:2.0` | Apache License, Version 2.0 | 0 | 5 |

Preserve all applicable original copyrights, notices and full license texts with distributions. SpecUnfold application code uses Apache-2.0; see the root LICENSE. Unmodified Logback 1.2.13 is distributed under the selected EPL 1.0 terms. Its complete official source, build files and original legal texts accompany both archives in licenses/sources/logback_1.2.13/. The JSON Schema validator, itu, Jackson YAML and SnakeYAML runtime additions use Apache-2.0. The validator includes Apache Commons Validator routines; its original upstream NOTICE is preserved. Its bundled Unicode 15.0 data uses Unicode-DFS-2016; JSON Schema meta-schemas use the BSD-3-Clause option; RFC 5892 data carries the IETF Trust code component BSD license and attribution. SnakeYAML includes Base64Coder under its available Apache-2.0 option and Google GData escapers under Apache-2.0; their original copyright and license headers are preserved, including Base64Coder's complete list of alternative licenses. Third-party components retain their own terms. The company must confirm code ownership and public-release authorization before public release.
