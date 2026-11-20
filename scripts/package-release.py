#!/usr/bin/env python3
"""Package reviewed source and the stdio runtime with matching legal evidence."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT.parent / "releases"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    license_path = ROOT / "LICENSE"
    license_sha256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
    if not license_path.is_file() or sha(license_path) != license_sha256:
        raise SystemExit("Provide the complete reviewed Apache-2.0 LICENSE before packaging")
    inventory = json.loads((ROOT / "licenses/dependencies.json").read_text(encoding="utf-8"))
    jar = ROOT / "mcp-server/target/specunfold.jar"
    if sha(jar) != inventory["application"]["sha256"]:
        raise SystemExit("Regenerate dependency evidence for the current application JAR")
    for dependency in inventory["dependencies"]:
        if sha(ROOT / dependency["jar"]) != dependency["sha256"]:
            raise SystemExit("Dependency evidence does not match: " + dependency["jar"])
        if dependency["missingMetadata"] or dependency["missingLegalText"]:
            raise SystemExit("Complete legal evidence before packaging: " + dependency["coordinate"])
        for material in dependency["pomEvidence"] + dependency["packagedLegalFiles"]:
            if not (ROOT / material).is_file():
                raise SystemExit("Missing dependency evidence: " + material)
        for material in dependency["supplementalEvidence"]:
            if not (ROOT / material["path"]).is_file() or sha(ROOT / material["path"]) != material["sha256"]:
                raise SystemExit("Dependency legal material changed: " + material["path"])
    source_materials = json.loads((ROOT / "licenses/sources/logback_1.2.13/SOURCE.json").read_text(encoding="utf-8"))
    for entry in source_materials["files"]:
        if sha(ROOT / entry["path"]) != entry["sha256"]:
            raise SystemExit("Corresponding source material changed: " + entry["path"])
    runtime_hashes = {entry["coordinate"]: entry["sha256"] for entry in inventory["dependencies"]}
    for entry in source_materials["binaries"]:
        if runtime_hashes[entry["coordinate"]] != entry["sha256"]:
            raise SystemExit("Corresponding source does not match runtime: " + entry["coordinate"])
    excluded = {"target", ".build", "node_modules", "__pycache__", ".git", ".svn", ".specunfold"}
    source = [ROOT / name for name in ["pom.xml", ".gitignore", ".gitattributes", "build.ps1", "run.ps1", "run.sh", "README.md", "LICENSE", "THIRD-PARTY-NOTICES.md"]]
    for directory in [".mvn", "bin", "resources", "scripts", "licenses", "workflow-core", "mcp-server"]:
        source.extend(path for path in (ROOT / directory).rglob("*")
                      if path.is_file() and not excluded.intersection(path.relative_to(ROOT).parts))
    runtime = [jar, *sorted((ROOT / "mcp-server/target/lib").glob("*.jar"))]
    runtime.extend(ROOT / name for name in ["run.ps1", "run.sh", "README.md", "LICENSE", "THIRD-PARTY-NOTICES.md"])
    for directory in ["bin", "resources", "licenses"]:
        runtime.extend(path for path in (ROOT / directory).rglob("*") if path.is_file())
    OUTPUT.mkdir(exist_ok=True)
    manifest = {"sourceFileCount": len(source), "runtimeFileCount": len(runtime),
                "applicationLicense": {"spdx": "Apache-2.0", "path": "LICENSE", "sha256": license_sha256},
                "thirdPartySourceMaterials": [source_materials], "artifacts": {}}
    for kind, files in [("source", source), ("runtime", runtime)]:
        path = OUTPUT / ("specunfold-" + kind + ".zip")
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(files):
                archive.write(file, "specunfold/" + file.relative_to(ROOT).as_posix())
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise SystemExit("Invalid archive: " + str(path))
        manifest["artifacts"][path.name] = {"sha256": sha(path), "bytes": path.stat().st_size}
    (OUTPUT / "specunfold-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
