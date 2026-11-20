'use strict';

const fs = require('node:fs');
const path = require('node:path');

let input = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => { input += chunk; });
process.stdin.on('end', () => {
  try {
    const request = JSON.parse(input);
    if (typeof request.artifactPath !== 'string' || !request.artifactPath || path.isAbsolute(request.artifactPath)) {
      throw new Error('A workspace-relative artifactPath is required.');
    }
    const workspace = fs.realpathSync(process.cwd());
    const artifact = path.resolve(workspace, request.artifactPath);
    const relative = path.relative(workspace, artifact);
    if (relative === '..' || relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) {
      throw new Error('artifactPath must remain inside the workspace.');
    }
    const exists = fs.existsSync(artifact);
    if (exists) {
      const actual = path.relative(workspace, fs.realpathSync(artifact));
      if (actual === '..' || actual.startsWith('..' + path.sep) || path.isAbsolute(actual)) {
        throw new Error('Artifact must remain inside the workspace.');
      }
    }
    const stats = exists ? fs.statSync(artifact) : null;
    const isFile = exists && stats.isFile();
    process.stdout.write(JSON.stringify({ ok: isFile, exists, path: request.artifactPath, bytes: isFile ? stats.size : null }) + '\n');
  } catch (error) {
    process.stderr.write(error.message + '\n');
    process.exitCode = 1;
  }
});
