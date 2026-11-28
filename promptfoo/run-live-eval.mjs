import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFile, realpath, stat } from 'node:fs/promises';
import path from 'node:path';
import { RagSession } from './rag-session.mjs';

const hsluMode = process.argv.includes('--hslu');
const agentMode = process.argv.includes('--agent');
const hsluRepoPath = process.env.HSLU_REPO_PATH;

if (hsluMode && agentMode) {
  throw new Error('The HSLU and agent evaluations must be run separately.');
}

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

async function uploadAndIndexText(session, workspaceId, source, text, onUploaded) {
  const csrf = await session.get('/api/csrf');
  const form = new FormData();
  form.set('file', new Blob([text], { type: 'text/plain' }), source);
  const uploadResponse = await session.fetch(new URL('/api/uploads', session.appUrl), {
    method: 'POST',
    headers: {
      'X-XSRF-TOKEN': csrf.token,
      'X-Workspace-ID': workspaceId,
    },
    body: form,
  });
  const uploaded = await session.json(uploadResponse);
  onUploaded(uploaded.key);

  const jobCsrf = await session.get('/api/csrf');
  const job = await session.post('/api/indexing-jobs/s3', { key: uploaded.key }, {
    'X-XSRF-TOKEN': jobCsrf.token,
    'X-Workspace-ID': workspaceId,
  });
  let approvalRequested = false;
  for (let attempt = 0; attempt < 90; attempt++) {
    const status = await session.get(`/api/indexing-jobs/${job.id}`, {
      'X-Workspace-ID': workspaceId,
    });
    if (status.status === 'AWAITING_APPROVAL' && !approvalRequested) {
      await session.approveIndexingJob(workspaceId, job.id);
      approvalRequested = true;
    }
    if (status.status === 'COMPLETED') return uploaded.key;
    if (status.status === 'FAILED') {
      throw new Error(`Synthetic S3 indexing failed for ${source}; provider error details are omitted.`);
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error(`Synthetic S3 indexing timed out for ${source}.`);
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
let session;
const uploadedKeys = [];

try {
  session = new RagSession();
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
          text: 'The Alpine observatory test marker is ORBIT-731. The project start month is April.',
        },
        {
          source: 'promptfoo-calibration.txt',
          text: 'The telescope calibration interval is exactly 17 days.',
        },
        ...(agentMode
          ? [
              {
                source: 'promptfoo-observatory-operations.txt',
                text: await readFile('promptfoo/fixtures/synthetic-planner-seed.txt', 'utf8'),
              },
              {
                source: 'leave-policy-2025.txt',
                text: 'Leave requests must be submitted at least 10 calendar days in advance. Managers approve all leave requests.',
                s3: true,
              },
              {
                source: 'leave-policy-2026.txt',
                text: 'Leave requests must be submitted at least 5 calendar days in advance. Managers approve all leave requests.',
                s3: true,
              },
            ]
          : []),
      ];
  for (const source of sources) {
    if (source.s3) {
      await uploadAndIndexText(
        session, workspaceId, source.source, source.text, (key) => uploadedKeys.push(key));
    } else {
      await session.indexText(workspaceId, source.source, source.text);
    }
  }

  const promptfoo = process.platform === 'win32' ? 'npx.cmd' : 'npx';
  const config = hsluMode
    ? 'promptfoo-hslu.yaml'
    : agentMode
      ? 'promptfoo-agent.yaml'
      : 'promptfoo.yaml';
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
    for (const key of uploadedKeys) {
      let deletionError;
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          const csrf = await session.get('/api/csrf');
          const response = await session.fetch(
            new URL(`/api/uploads?${new URLSearchParams({ key })}`, session.appUrl),
            {
              method: 'DELETE',
              headers: {
                'X-XSRF-TOKEN': csrf.token,
                'X-Workspace-ID': workspaceId,
              },
            },
          );
          if (response.status !== 204) {
            throw new Error(`Could not delete a synthetic uploaded object (HTTP ${response.status}).`);
          }
          deletionError = undefined;
          break;
        } catch (error) {
          deletionError = error;
          if (attempt < 2) {
            await new Promise((resolve) => setTimeout(resolve, 500 * (attempt + 1)));
          }
        }
      }
      if (deletionError) cleanupError = deletionError;
    }
    if (!cleanupError) {
      try {
        runPsql([
          'BEGIN;',
          `DELETE FROM indexing_jobs WHERE workspace_id = '${workspaceId}'::uuid;`,
          `DELETE FROM documents WHERE workspace_id = '${workspaceId}'::uuid;`,
          `DELETE FROM workspaces WHERE id = '${workspaceId}'::uuid AND name = '${workspaceName}';`,
          'COMMIT;',
        ].join('\n'));
        console.log('Removed temporary Promptfoo workspace records.');
      } catch (error) {
        cleanupError = error;
      }
    } else {
      console.error('Skipping temporary workspace cleanup because an uploaded object could not be deleted.');
    }
  }
}

if (cleanupError) {
  console.error(cleanupError.message);
  process.exitCode = 1;
}
