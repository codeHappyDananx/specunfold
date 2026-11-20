#!/usr/bin/env python3
"""Collect legal evidence for the actual thin-JAR runtime distribution."""
import argparse
import hashlib
import json
import re
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SCHEMA_COMPONENTS = {
    'com.networknt:json-schema-validator:1.0.87',
    'com.ethlo.time:itu:1.7.0',
    'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2',
    'org.yaml:snakeyaml:2.0',
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def legal(name):
    return bool(re.search(r"(?:^|[-_.])(licen[cs]es?|notices?|copying|copyright|third[-_.]party)(?:$|[-_.])", Path(name).name, re.I))


def pom_evidence(path, repo, out):
    root = ET.fromstring(re.sub(r' xmlns="[^"]+"', '', path.read_text(encoding='utf-8')))
    target = out / 'poms' / path.relative_to(repo)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(path.read_bytes())
    files = [target.relative_to(ROOT).as_posix()]
    licenses = [{child.tag: (child.text or '').strip() for child in item} for item in root.findall('licenses/license')]
    parent = root.find('parent')
    if not licenses and parent is not None:
        group, artifact, version = [parent.findtext(name) for name in ('groupId', 'artifactId', 'version')]
        parent_path = repo / group.replace('.', '/') / artifact / version / (artifact + '-' + version + '.pom')
        licenses, parents = pom_evidence(parent_path, repo, out)
        files += parents
    return licenses, files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fetch-upstream', action='store_true', help='Fetch versioned upstream full license texts')
    args = parser.parse_args()
    repo = ROOT / '.build/m2'
    libraries = ROOT / 'mcp-server/target/lib'
    application = ROOT / 'mcp-server/target/specunfold.jar'
    if not application.is_file() or not libraries.is_dir() or not repo.is_dir():
        parser.error('Build the application using the isolated Maven repository first.')
    out = ROOT / 'licenses'
    evidence = out / 'java'
    if evidence.exists():
        shutil.rmtree(evidence)
    records = []
    for jar in sorted(libraries.glob('*.jar')):
        data = jar.read_bytes()
        candidates = [p for p in repo.rglob(jar.name) if sha(p.read_bytes()) == sha(data)]
        if len(candidates) == 1:
            relative = candidates[0].relative_to(repo).parts
            group, artifact, version = '.'.join(relative[:-3]), relative[-3], relative[-2]
            pom = candidates[0].with_suffix('.pom')
        else:
            with zipfile.ZipFile(jar) as archive:
                entries = [n for n in archive.namelist() if n.endswith('/pom.properties')]
                if len(entries) != 1:
                    raise ValueError('Cannot resolve unique Maven coordinates: ' + jar.name)
                properties = dict(re.findall(r'^([^#!\s=]+)\s*=\s*(.*?)\s*$', archive.read(entries[0]).decode(), re.M))
                group, artifact, version = [properties[k] for k in ('groupId', 'artifactId', 'version')]
            pom = None
        coordinate = ':'.join((group, artifact, version))
        first_party = group == 'dev.specunfold'
        target = evidence / re.sub(r'[^A-Za-z0-9._-]', '_', coordinate)
        files = []
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if not name.endswith('/') and legal(name):
                    path = target / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(archive.read(name))
                    files.append(path.relative_to(ROOT).as_posix())
        licenses, poms = pom_evidence(pom, repo, target) if pom else ([], [])
        supplemental = []
        urls = []
        if group == 'org.slf4j':
            urls = [('LICENSE.txt', 'https://raw.githubusercontent.com/qos-ch/slf4j/v_' + version + '/LICENSE.txt')]
        elif group == 'ch.qos.logback':
            urls = [('LICENSE.txt', 'https://raw.githubusercontent.com/qos-ch/logback/v_' + version + '/LICENSE.txt'),
                    ('LGPL-2.1.txt', 'https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt'),
                    ('EPL-1.0.html', 'https://www.eclipse.org/legal/epl-v10.html')]
        elif coordinate == 'com.fasterxml.jackson.core:jackson-core:2.15.2':
            urls = [('FastDoubleParser-MIT-LICENSE', 'https://raw.githubusercontent.com/wrandelshofer/FastDoubleParser/522be16e145f43308c43b23094e31d5efcaa580e/LICENSE')]
        elif coordinate == 'com.networknt:json-schema-validator:1.0.87':
            base = 'https://api.github.com/repos/networknt/json-schema-validator/contents/'
            revision = '?ref=b634de7c83a2da68957a27f0ea55d08a5133903c'
            urls = [('LICENSE', base + 'LICENSE' + revision), ('NOTICE', base + 'NOTICE' + revision),
                    ('Unicode-DFS-2016-LICENSE.txt', 'https://api.github.com/repos/unicode-org/icu/contents/icu4c/LICENSE?ref=ff3514f257ea10afe7e710e9f946f68d256704b1', 'unicode-license'),
                    ('JSON-Schema-LICENSE.txt', 'https://api.github.com/repos/json-schema-org/json-schema-spec/contents/LICENSE?ref=51326f80900357fe3069beb4f5f575db24c1b9a7'),
                    ('JSON-Schema-NOTICE.txt', 'https://api.github.com/repos/json-schema-org/json-schema-spec/contents/README.md?ref=51326f80900357fe3069beb4f5f575db24c1b9a7', 'schema-license'),
                    ('IETF-TLP-4.pdf', 'https://trustee.ietf.org/wp-content/uploads/IETF-TLP-4.pdf'),
                    ('RFC5892-NOTICE.txt', 'https://www.rfc-editor.org/rfc/rfc5892.txt', 'rfc-notice')]
        elif coordinate == 'com.ethlo.time:itu:1.7.0':
            urls = [('LICENSE', 'https://api.github.com/repos/ethlo/itu/contents/LICENSE?ref=e8067a7e00452b2ee91fdb3538d21ad11e4167d0')]
        elif coordinate == 'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2':
            urls = [('LICENSE', 'https://api.github.com/repos/FasterXML/jackson-dataformats-text/contents/LICENSE?ref=19554b4e4daf2f400bcba8b30fc44a6d6c40101a')]
        elif coordinate == 'org.yaml:snakeyaml:2.0':
            base = 'https://api.bitbucket.org/2.0/repositories/snakeyaml/snakeyaml/src/59ddbb3304bb8e22e2004d74cddaf9ed4086632e/'
            external = base + 'src/main/java/org/yaml/snakeyaml/external/'
            urls = [('LICENSE.txt', base + 'LICENSE.txt'),
                    ('Base64Coder-NOTICE.txt', external + 'biz/base64Coder/Base64Coder.java', True)]
            urls += [(name + '-NOTICE.txt', external + 'com/google/gdata/util/common/base/' + name + '.java', True)
                     for name in ('Escaper', 'PercentEscaper', 'UnicodeEscaper')]
        for entry in urls:
            name, url = entry[:2]
            extraction = entry[2] if len(entry) == 3 else None
            path = out / 'upstream' / re.sub(r'[^A-Za-z0-9._-]', '_', coordinate) / name
            if args.fetch_upstream and not path.exists():
                path.parent.mkdir(parents=True, exist_ok=True)
                headers = {'Accept': 'application/vnd.github.raw+json', 'User-Agent': 'SpecUnfold-license-audit'} if url.startswith('https://api.github.com/') else {}
                content = urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30).read()
                if extraction is True:
                    if b'\npackage ' not in content:
                        raise ValueError('Cannot locate original source license header: ' + url)
                    content = content.split(b'\npackage ', 1)[0] + b'\n'
                elif extraction == 'unicode-license':
                    content = content.split(b'----------------------------------------------------------------------', 1)[0]
                elif extraction == 'schema-license':
                    if b'## License' not in content:
                        raise ValueError('Cannot locate original JSON Schema license declaration')
                    content = content[content.index(b'## License'):]
                elif extraction == 'rfc-notice':
                    if b'Copyright Notice' not in content or b'Table of Contents' not in content:
                        raise ValueError('Cannot locate original RFC copyright notice')
                    content = content[content.index(b'Copyright Notice'):content.index(b'Table of Contents')]
                path.write_bytes(content)
            if path.is_file():
                item = {'path': path.relative_to(ROOT).as_posix(), 'url': url, 'sha256': sha(path.read_bytes())}
                if extraction is True: item['extraction'] = 'Original leading copyright and license header, before the package declaration'
                elif extraction: item['extraction'] = extraction
                supplemental.append(item)
        record = {'coordinate': coordinate, 'jar': jar.relative_to(ROOT).as_posix(), 'sha256': sha(data),
                        'firstParty': first_party, 'licenses': licenses, 'pomEvidence': poms,
                        'packagedLegalFiles': files, 'supplementalEvidence': supplemental,
                        'missingMetadata': not first_party and not licenses,
                        'missingLegalText': not first_party and ((not files and not supplemental) or (bool(urls) and len(supplemental) != len(urls)))}
        if coordinate in SCHEMA_COMPONENTS:
            record['selectedLicense'] = 'Apache-2.0'
        if coordinate == 'com.networknt:json-schema-validator:1.0.87':
            record['embeddedComponents'] = [
                {'name': 'Apache Commons Validator routines', 'selectedLicense': 'Apache-2.0',
                 'notice': 'licenses/upstream/com.networknt_json-schema-validator_1.0.87/NOTICE'},
                {'name': 'Unicode 15.0.0 DerivedJoiningType data', 'selectedLicense': 'Unicode-DFS-2016',
                 'notice': 'licenses/upstream/com.networknt_json-schema-validator_1.0.87/Unicode-DFS-2016-LICENSE.txt'},
                {'name': 'JSON Schema draft meta-schemas', 'availableLicenseAlternatives': ['BSD-3-Clause', 'AFL-3.0'],
                 'selectedLicense': 'BSD-3-Clause', 'notice': 'licenses/upstream/com.networknt_json-schema-validator_1.0.87/JSON-Schema-LICENSE.txt'},
                {'name': 'RFC 5892 Appendix B code point data', 'selectedLicense': 'IETF-TLP-4 Code Components BSD license',
                 'notices': ['licenses/upstream/com.networknt_json-schema-validator_1.0.87/IETF-TLP-4.pdf',
                             'licenses/upstream/com.networknt_json-schema-validator_1.0.87/RFC5892-NOTICE.txt',
                             'licenses/upstream/com.networknt_json-schema-validator_1.0.87/IETF-Code-Components-BSD-LICENSE.txt']}]
            ietf_bsd = out / 'upstream/com.networknt_json-schema-validator_1.0.87/IETF-Code-Components-BSD-LICENSE.txt'
            if not ietf_bsd.is_file(): record['missingLegalText'] = True
            else:
                supplemental.append({'path': ietf_bsd.relative_to(ROOT).as_posix(), 'sha256': sha(ietf_bsd.read_bytes()),
                                     'url': 'https://trustee.ietf.org/wp-content/uploads/IETF-TLP-4.pdf',
                                     'extraction': 'Section 4.c BSD license with the RFC 5892 year and author attribution'})
        elif coordinate == 'org.yaml:snakeyaml:2.0':
            record['embeddedComponents'] = [
                {'name': 'Base64Coder', 'availableLicenseAlternatives': ['EPL-1.0-or-later', 'LGPL-2.1-or-later', 'GPL-2.0-or-later', 'Apache-2.0-or-later', 'BSD'],
                 'selectedLicense': 'Apache-2.0', 'notice': 'licenses/upstream/org.yaml_snakeyaml_2.0/Base64Coder-NOTICE.txt'},
                {'name': 'Google GData Escaper, PercentEscaper and UnicodeEscaper', 'selectedLicense': 'Apache-2.0',
                 'notices': ['licenses/upstream/org.yaml_snakeyaml_2.0/' + name + '-NOTICE.txt' for name in ('Escaper', 'PercentEscaper', 'UnicodeEscaper')]}]
        records.append(record)
    result = {'schemaVersion': 1, 'application': {'path': application.relative_to(ROOT).as_posix(),
              'sha256': sha(application.read_bytes())}, 'dependencies': records}
    (out / 'dependencies.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    lines = ['# Third-party dependency evidence', '',
             'Generated from the actual thin-JAR runtime libraries by `python scripts/audit-dependencies.py`. '
             'Use `--fetch-upstream` to retrieve supplementary original legal texts from official sources. '
             'The machine-readable inventory, hashes, original packaged legal files and Maven parent-POM evidence are in `licenses/`.', '',
             '| Maven coordinate | Declared licenses | Packaged legal files | Supplemental files |', '|---|---|---:|---:|']
    for item in records:
        if item['firstParty']:
            continue
        names = '; '.join(x.get('name', '') for x in item['licenses']) or 'MISSING'
        lines.append('| `%s` | %s | %d | %d |' % (item['coordinate'], names.replace('|', '\\|'), len(item['packagedLegalFiles']), len(item['supplementalEvidence'])))
    lines += ['', 'Preserve all applicable original copyrights, notices and full license texts with distributions. '
              'SpecUnfold application code uses Apache-2.0; see the root LICENSE. '
              'Unmodified Logback 1.2.13 is distributed under the selected EPL 1.0 terms. Its complete official source, build files and original legal texts accompany both archives in licenses/sources/logback_1.2.13/. '
              'The JSON Schema validator, itu, Jackson YAML and SnakeYAML runtime additions use Apache-2.0. '
              'The validator includes Apache Commons Validator routines; its original upstream NOTICE is preserved. '
              'Its bundled Unicode 15.0 data uses Unicode-DFS-2016; JSON Schema meta-schemas use the BSD-3-Clause option; '
              'RFC 5892 data carries the IETF Trust code component BSD license and attribution. '
              'SnakeYAML includes Base64Coder under its available Apache-2.0 option and Google GData escapers under Apache-2.0; '
              'their original copyright and license headers are preserved, including Base64Coder\'s complete list of alternative licenses. '
              'Third-party components retain their own terms. The company must confirm code ownership and public-release authorization before public release.']
    (ROOT / 'THIRD-PARTY-NOTICES.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(json.dumps({'thirdPartyJars': sum(not x['firstParty'] for x in records),
                      'missingMetadata': [x['coordinate'] for x in records if x['missingMetadata']],
                      'missingLegalText': [x['coordinate'] for x in records if x['missingLegalText']]}, indent=2))


if __name__ == '__main__':
    main()
