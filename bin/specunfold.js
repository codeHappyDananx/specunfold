#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const home = path.resolve(__dirname, '..');
const instructionBegin = '<!-- SPECUNFOLD:BEGIN -->';
const instructionEnd = '<!-- SPECUNFOLD:END -->';

function instructionBlock() {
  return [instructionBegin,
    'This workspace uses SpecUnfold. Read `.agents/harness/constitution.md` before handling a task.',
    'At session start, resumption or context compaction, call `workflow_ops` with `action:"context"`, the chat workspaceRoot and current intent; include the executionId when known. Use the installed default Profile unless another Profile is explicitly selected.',
    'Read only the current references and requested specification content. Follow returned continuations and runTemplate; do not repeat disclosure already supplied for the current node.',
    'Ordinary tasks do not create workflow state. Bootstrap requires workflowConfirmedByUser:true and workflowConfirmationSource:"user_explicit_request" or "user_confirmed_proposal", backed by the actual user request or agreement. Resuming an execution reuses stored confirmation. User confirmation must come from the user.',
    'Use MCP Router for the SpecUnfold stdio service. The entry skill is `.agents/skills/specunfold-router/SKILL.md`.',
    instructionEnd].join('\n');
}

function managedInstructions(target) {
  const current = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : '';
  const begin = current.indexOf(instructionBegin);
  const end = current.indexOf(instructionEnd);
  if (begin !== -1 || end !== -1) {
    if (begin === -1 || end < begin || current.indexOf(instructionBegin, begin + instructionBegin.length) !== -1
        || current.indexOf(instructionEnd, end + instructionEnd.length) !== -1) {
      throw new Error('Installation conflict; malformed managed instruction block: ' + target);
    }
    return Buffer.from(current.slice(0, begin) + instructionBlock() + current.slice(end + instructionEnd.length));
  }
  return Buffer.from(current + (current && !current.endsWith('\n') ? '\n\n' : current ? '\n' : '') + instructionBlock() + '\n');
}

function install(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index];
    if (!['--workspace', '--profile', '--profile-dir'].includes(key) || !args[index + 1] || args[index + 1].startsWith('--') || options[key]) {
      throw new Error('Usage: install --workspace PATH --profile ID [--profile-dir PATH]');
    }
    options[key] = args[index + 1];
  }
  if (!options['--workspace'] || !options['--profile']) {
    throw new Error('Usage: install --workspace PATH --profile ID [--profile-dir PATH]');
  }
  const id = options['--profile'];
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(id)) {
    throw new Error('Profile ID must contain letters, numbers, underscores or hyphens.');
  }
  const workspace = path.resolve(options['--workspace']);
  const source = options['--profile-dir']
    ? path.resolve(options['--profile-dir'])
    : path.join(home, 'resources', 'profiles', id);
  if (!fs.statSync(source).isDirectory() || !fs.existsSync(path.join(source, 'spec-map.json'))) {
    throw new Error('Profile source must be a directory containing spec-map.json: ' + source);
  }
  const destination = path.join(workspace, '.agents', 'profiles', id);
  const files = [];
  function collect(root, directory, targetRoot) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const item = path.join(directory, entry.name);
      if (entry.isSymbolicLink()) throw new Error('Profile resources must not be symbolic links: ' + item);
      if (entry.isDirectory()) collect(root, item, targetRoot);
      else if (entry.isFile()) files.push({ target: path.join(targetRoot, path.relative(root, item)), content: fs.readFileSync(item) });
      else throw new Error('Profile resource must be a regular file: ' + item);
    }
  }
  collect(source, source, destination);
  const harness = path.join(home, 'resources', 'harness');
  collect(harness, harness, path.join(workspace, '.agents', 'harness'));
  const skillRoot = path.join(workspace, '.agents', 'harness', 'skills');
  for (const file of files) {
    if (file.target.startsWith(skillRoot + path.sep)) {
      file.target = path.join(workspace, '.agents', 'skills', path.relative(skillRoot, file.target));
    }
  }
  const manifestPath = path.join(workspace, '.agents', 'harness', 'manifest.json');
  let defaultProfile = id;
  if (fs.existsSync(manifestPath)) {
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    if (manifest.framework !== 'specunfold' || manifest.contractVersion !== 1
        || typeof manifest.defaultProfile !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(manifest.defaultProfile)) {
      throw new Error('Installation conflict; invalid Harness manifest: ' + manifestPath);
    }
    defaultProfile = manifest.defaultProfile;
  }
  files.push({ target: manifestPath, content: Buffer.from(JSON.stringify({ framework: 'specunfold', contractVersion: 1, defaultProfile }, null, 2) + '\n') });
  for (const name of ['AGENTS.md', 'CLAUDE.md']) {
    const target = path.join(workspace, name);
    files.push({ target, managed: true });
  }
  for (const file of files) {
    const target = file.target;
    let directory = path.dirname(target);
    while (directory !== path.dirname(directory)) {
      const directoryEntry = fs.lstatSync(directory, { throwIfNoEntry: false });
      if (directoryEntry && (!directoryEntry.isDirectory() || directoryEntry.isSymbolicLink())) {
        throw new Error('Installation conflict; expected regular directory: ' + directory);
      }
      directory = path.dirname(directory);
    }
    const targetEntry = fs.lstatSync(target, { throwIfNoEntry: false });
    if (targetEntry && (!targetEntry.isFile() || targetEntry.isSymbolicLink())) {
      throw new Error('Installation conflict; expected regular file: ' + target);
    }
    if (file.managed) file.content = managedInstructions(target);
    if (fs.existsSync(target) && !file.managed && !fs.readFileSync(target).equals(file.content)) {
      throw new Error('Installation conflict; existing file differs: ' + target);
    }
  }
  let copied = 0;
  for (const file of files) {
    const target = file.target;
    if (fs.existsSync(target) && fs.readFileSync(target).equals(file.content)) continue;
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, file.content, { flag: file.managed ? 'w' : 'wx' });
    copied += 1;
  }
  console.log('Profile installed: ' + destination + ' (' + copied + ' files copied)');
  console.log('Configure this stdio service in MCP Router:');
  console.log(JSON.stringify({ command: 'node', args: [__filename, 'serve'] }, null, 2));
}

try {
  const [command, ...args] = process.argv.slice(2);
  if (command === 'install') install(args);
  else if (command === 'serve' && args.length === 0) {
    const jar = path.join(home, 'mcp-server', 'target', 'specunfold.jar');
    if (!fs.existsSync(jar)) throw new Error('Build SpecUnfold first; JAR not found: ' + jar);
    const result = spawnSync('java', ['-Dspecunfold.home=' + home, '-jar', jar], { stdio: 'inherit' });
    if (result.error) throw result.error;
    process.exitCode = result.status === null ? 1 : result.status;
  } else {
    throw new Error('Usage: specunfold.js install --workspace PATH --profile ID [--profile-dir PATH]\n       specunfold.js serve');
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
