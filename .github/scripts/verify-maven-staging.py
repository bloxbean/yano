#!/usr/bin/env python3
"""Checks a staged snapshot Maven repository before r2-snapshot.yml uploads it.

Usage: verify-maven-staging.py <repository-dir> <seed-dir> <group-path> <version> <artifactId>...

The repository must hold exactly the given artifactIds under <group-path>, each with <version> as its only
version, artifact- and version-level maven-metadata.xml that name that version, every file the version-level
metadata lists, a checksum for every file, and binary, sources and javadoc jars plus Gradle module metadata for
every jar-packaged module. A file of any other kind or outside <group-path> - a distribution zip, a native
binary, another project's metadata - fails the check, because whatever is staged is uploaded to the shared
bucket.

<seed-dir> holds <artifactId>.xml, the artifact-level metadata the repository was seeded with. Every version it
lists must still be listed after staging: uploading a list Gradle failed to merge would erase published versions.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CHECKSUMS = ('.md5', '.sha1', '.sha256', '.sha512')
MAVEN_FILES = ('.jar', '.pom', '.module', 'maven-metadata.xml') + CHECKSUMS
JAR_MODULE_FILES = {(None, 'jar'), ('sources', 'jar'), ('javadoc', 'jar'), (None, 'module'), (None, 'pom')}


def text(element, path):
    found = element.find(path)
    return found.text.strip() if found is not None and found.text else None


def versions(metadata):
    return [v.text for v in metadata.findall('versioning/versions/version')]


def packaging(pom):
    # POMs are namespaced; match the element by local name. Maven's default packaging is jar.
    for child in ET.parse(pom).getroot():
        if child.tag.rsplit('}', 1)[-1] == 'packaging':
            return child.text.strip()
    return 'jar'


def check_artifact(artifact_dir, seed, group_id, artifact, version):
    errors = []
    staged_versions = sorted(p.name for p in artifact_dir.iterdir() if p.is_dir())
    if staged_versions != [version]:
        return [f'{artifact}: staged versions {staged_versions}, expected [{version}]']
    version_dir = artifact_dir / version
    for metadata_file in (artifact_dir / 'maven-metadata.xml', version_dir / 'maven-metadata.xml'):
        if not metadata_file.is_file():
            return [f'{artifact}: no {metadata_file.relative_to(artifact_dir.parent)}']

    metadata = ET.parse(artifact_dir / 'maven-metadata.xml').getroot()
    listed = versions(metadata)
    if (text(metadata, 'groupId'), text(metadata, 'artifactId')) != (group_id, artifact):
        errors.append(f'{artifact}: artifact metadata names another artifact')
    if version not in listed or text(metadata, 'versioning/latest') != version:
        errors.append(f'{artifact}: artifact metadata does not list {version} as latest')
    if seed.is_file():
        dropped = [v for v in versions(ET.parse(seed).getroot()) if v not in listed]
        if dropped:
            errors.append(f'{artifact}: staging dropped published versions {dropped}')

    snapshot = ET.parse(version_dir / 'maven-metadata.xml').getroot()
    if (text(snapshot, 'groupId'), text(snapshot, 'artifactId'), text(snapshot, 'version')) != (
            group_id, artifact, version):
        errors.append(f'{artifact}: version metadata names another artifact or version')
    named = {}
    for entry in snapshot.findall('versioning/snapshotVersions/snapshotVersion'):
        classifier, extension = text(entry, 'classifier'), text(entry, 'extension')
        suffix = f'-{classifier}' if classifier else ''
        named[(classifier, extension)] = f'{artifact}-{text(entry, "value")}{suffix}.{extension}'

    for name in named.values():
        if not (version_dir / name).is_file():
            errors.append(f'{artifact}: metadata names {name}, which is not staged')
    if (None, 'pom') not in named:
        return errors + [f'{artifact}: no POM']
    required = {(None, 'pom')}
    if packaging(version_dir / named[(None, 'pom')]) != 'pom':
        required = JAR_MODULE_FILES
    for classifier, extension in sorted(required - named.keys(), key=str):
        errors.append(f'{artifact}: missing {classifier or "main"} {extension}')

    # Anything in the version directory that the metadata does not name would be uploaded but never resolved.
    expected = set(named.values()) | {'maven-metadata.xml'}
    for f in version_dir.iterdir():
        base = f.name
        for checksum in CHECKSUMS:
            base = base.removesuffix(checksum)
        if base not in expected:
            errors.append(f'{artifact}: unexpected file {f.name}')
    return errors


def main(root, seed_dir, group_path, version, artifacts):
    root = Path(root)
    group_dir = root / group_path
    errors = []
    for f in sorted(p for p in root.rglob('*') if p.is_file()):
        rel = f.relative_to(root)
        if not f.is_relative_to(group_dir):
            errors.append(f'outside {group_path}: {rel}')
        elif not f.name.endswith(MAVEN_FILES):
            errors.append(f'not a Maven repository file: {rel}')
        elif not f.name.endswith(CHECKSUMS) and not f.with_name(f.name + '.sha1').is_file():
            errors.append(f'no checksum: {rel}')

    staged = sorted(p.name for p in group_dir.iterdir() if p.is_dir()) if group_dir.is_dir() else []
    if staged != sorted(artifacts):
        errors.append(f'staged artifacts {staged} do not match the build publications {sorted(artifacts)}')
    group_id = group_path.replace('/', '.')
    for artifact in staged:
        errors += check_artifact(group_dir / artifact, Path(seed_dir) / f'{artifact}.xml', group_id, artifact,
                                 version)

    for error in errors:
        print(f'::error::{error}', file=sys.stderr)
    if errors:
        return 1
    files = sum(1 for p in root.rglob('*') if p.is_file())
    print(f'{len(staged)} artifacts, {files} files, all at {version}')
    return 0


if __name__ == '__main__':
    if len(sys.argv) < 6:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5:]))
