import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFile, realpath, stat } from 'node:fs/promises';
import path from 'node:path';
import { RagSession } from './rag-session.mjs';

const hsluMode = process.argv.includes('--hslu');
const hsluRepoPath = process.env.HSLU_REPO_PATH;

function loadDotEnv(contents) {
  const values = {};
  for (const line of contents.split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!match || match[1].startsWith('#')) continue;
    values[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2');
  }
  return values;
}

function runPsql(sql) {
  const result = spawnSync('docker', [
    'compose', 'exec', '-T', 'postgres', 'sh', '-c',
    'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
  ], { input: sql, encoding: 'utf8' });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`Could not clean up the temporary evaluation data: ${result.stderr.trim()}`);
  }
}

async function loadHsluEvaluationSource() {
  if (!hsluRepoPath) {
    throw new Error('Set HSLU_REPO_PATH to the local HSLU repository checkout.');
  }
  const root = await realpath(hsluRepoPath);
  const sourcePath = path.join(root, 'wiki', 'rag-course-architecture.md');
  const resolvedSource = await realpath(sourcePath);
  if (!resolvedSource.startsWith(`${root}${path.sep}`)) {
    throw new Error('The selected HSLU evaluation source must be inside the repository.');
  }
  const metadata = await stat(resolvedSource);
  if (!metadata.isFile() || metadata.size > 64 * 1024) {
    throw new Error('The selected HSLU evaluation source must be a regular file no larger than 64 KiB.');
  }
  const text = await readFile(resolvedSource, 'utf8');
  if (!text.trim()) throw new Error('The selected HSLU evaluation source is empty.');
  return {
    source: 'hslu/wiki/rag-course-architecture.md',
    text,
  };
}

const env = loadDotEnv(await readFile('.env', 'utf8'));
const required = [
  'KEYCLOAK_ADMIN_PASSWORD',
  'INFOMANIAK_AI_API_KEY',
  'INFOMANIAK_AI_PRODUCT_ID',
  'INFOMANIAK_AI_CHAT_MODEL',
  'INFOMANIAK_AI_EMBEDDING_MODEL',
  'INFOMANIAK_AI_RERANK_MODEL',
];
const missing = required.filter((name) => !env[name] || /^(replace-with|change-me)/i.test(env[name]));
if (missing.length) {
  throw new Error(`Configure these values in .env before a live eval: ${missing.join(', ')}`);
}
if (env.RAG_PROVIDER !== 'infomaniak') {
  throw new Error('Set RAG_PROVIDER=infomaniak in .env before a live eval.');
}

Object.assign(process.env, env, {
  PROMPTFOO_DISABLE_TELEMETRY: '1',
  PROMPTFOO_DISABLE_UPDATE: '1',
});

const workspaceName = `Promptfoo live eval ${randomUUID()}`;
let workspaceId;
let cleanupError;

try {
  const session = new RagSession();
  await session.login();
  const workspace = await session.createWorkspace(workspaceName);
  if (!/^[0-9a-f-]{36}$/i.test(workspace.id || '')) {
    throw new Error('The local RAG API returned an invalid workspace ID.');
  }
  workspaceId = workspace.id;
  process.env.PROMPTFOO_WORKSPACE_ID = workspaceId;

  const sources = hsluMode
    ? [await loadHsluEvaluationSource()]
    : [
        {
          source: 'promptfoo-observatory.txt',
          text: 'The Alpine observatory test marker is ORBIT-731.',
        },
        {
          source: 'promptfoo-calibration.txt',
          text: 'The telescope calibration interval is exactly 17 days.',
        },
      ];
  for (const source of sources) {
    await session.indexText(workspaceId, source.source, source.text);
  }

  const promptfoo = process.platform === 'win32' ? 'npx.cmd' : 'npx';
  const config = hsluMode ? 'promptfoo-hslu.yaml' : 'promptfoo.yaml';
  const result = spawnSync(promptfoo, [
    'promptfoo', 'eval', '--config', config, '--no-cache', '--no-share', '--no-write',
  ], {
    stdio: 'inherit',
    env: process.env,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exitCode = result.status || 1;
} finally {
  if (workspaceId) {
    try {
      runPsql([
        'BEGIN;',
        `DELETE FROM indexing_jobs WHERE workspace_id = '${workspaceId}'::uuid;`,
        `DELETE FROM documents WHERE workspace_id = '${workspaceId}'::uuid;`,
        `DELETE FROM workspaces WHERE id = '${workspaceId}'::uuid AND name = '${workspaceName}';`,
        'COMMIT;',
      ].join('\n'));
      console.log('Removed the temporary Promptfoo workspace and its test data.');
    } catch (error) {
      cleanupError = error;
    }
  }
}

if (cleanupError) {
  console.error(cleanupError.message);
  process.exitCode = 1;
}
