# Logback 1.2.13 distribution and source

The unmodified logback-core and logback-classic 1.2.13 binaries are distributed under EPL-1.0, as permitted by the upstream dual-license notice. The application license applies to its own code; it does not replace this component license.

The complete upstream source tree, including Java/Groovy sources, resources, test inputs, parent/module POMs and build scripts, is available in logback_1.2.13-source.zip in this directory. Extract it to obtain the preferred form for modification. LICENSE.txt and EPL-1.0.html provide the original notice and complete selected license. Copyright and attribution comments remain intact inside the archive. SOURCE.json identifies the exact upstream tag/commit, published source artifacts and hashes. SOURCE-MATCH.json records the comparison against the published sources.jar. No source or binary changes were made.

Build entry: extracted root pom.xml and logback-core/pom.xml, logback-classic/pom.xml. Use JDK 8 and Maven, with public Maven dependencies and plugins declared in those POMs. A module build command is:

```text
mvn -pl logback-core,logback-classic -am -Dmaven.test.skip=true -Dmaven.antrun.skip=true package
```

This command describes the upstream build entry and avoids upstream integration tests; it has not been executed as part of source-material collection. No byte-for-byte reproducible-build claim is made. Do not enable host-orion/host-hora profiles, which reference local proprietary database test drivers. Those profiles are optional upstream tests, not application build inputs. 1.2.13 uses Java 6 source/target; a recent JDK that removed that target is unsuitable without changing upstream build configuration.

All source and executable copies of these components retain EPL-1.0 and upstream notices. Redistribution must retain this source-access notice and the supplied source archive, or provide the source through another reasonable software-exchange medium under EPL-1.0. No upstream contributor provides warranties or conditions, including title, non-infringement, merchantability or fitness for purpose; no upstream contributor is liable for direct, indirect, special, incidental or consequential damages, including lost profits. Any terms differing from EPL-1.0, including any additional warranty or liability undertaking, are offered solely by the distributor and not by any upstream contributor. These terms do not limit recipients' EPL source-code rights. Commercial distributors must observe EPL section 4 concerning their own acts or omissions.
