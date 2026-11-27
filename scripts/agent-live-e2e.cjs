const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");

const baseUrl = "http://localhost:8080";
const filename = "orbit-synthetic-model-call-test.txt";
const objectName = `/${filename}`;
const screenshotPath = path.join("test-results", "agent-live-e2e.png");
const syntheticText =
  "Synthetic test document only. Fictitious project ORBIT has test code ORBIT-731. " +
  "Its start month is April. The project goal is to build an example dashboard.";

function requiredEnv(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Required environment variable ${name} is missing.`);
  }
  return value;
}

async function authenticatedPage(browser) {
  const page = await browser.newPage({ viewport: { width: 1365, height: 900 } });
  await page.goto(`${baseUrl}/oauth2/authorization/keycloak`, { waitUntil: "domcontentloaded" });
  await page.locator('input[name="username"]').waitFor({ state: "visible", timeout: 30_000 });
  await page.locator('input[name="username"]').fill(requiredEnv("KEYCLOAK_ADMIN_USERNAME"));
  await page.locator('input[name="password"]').fill(requiredEnv("KEYCLOAK_ADMIN_PASSWORD"));
  await page.locator('button[type="submit"], input[type="submit"]').first().click();
  await page.waitForURL((url) => url.origin === baseUrl, { timeout: 60_000 });
  await page.goto(`${baseUrl}/`, { waitUntil: "domcontentloaded" });
  await page.locator("#workspace-select:not([disabled])").waitFor({ timeout: 30_000 });
  return page;
}

async function requestJson(page, endpoint, workspaceId) {
  const result = await page.evaluate(
    async ({ endpoint, workspaceId }) => {
      const response = await fetch(endpoint, {
        headers: workspaceId ? { "X-Workspace-ID": workspaceId } : {},
      });
      if (!response.ok) {
        return { status: response.status };
      }
      return { status: response.status, data: await response.json() };
    },
    { endpoint, workspaceId },
  );
  if (result.status !== 200) {
    throw new Error(`Authenticated request to ${endpoint} failed with HTTP ${result.status}.`);
  }
  return result.data;
}

async function listWorkspaceInventory(page) {
  const workspaces = await requestJson(page, "/api/workspaces");
  const inventory = [];
  for (const workspace of workspaces) {
    const [documents, storage] = await Promise.all([
      requestJson(page, "/api/documents", workspace.id),
      requestJson(page, "/api/storage", workspace.id),
    ]);
    inventory.push({ workspaceId: workspace.id, documents, files: storage.files });
  }
  return inventory;
}

async function waitForIndexing(page, workspaceId) {
  const deadline = Date.now() + 300_000;
  while (Date.now() < deadline) {
    const jobs = await requestJson(page, "/api/indexing-jobs", workspaceId);
    const job = jobs.find((item) => item.source?.endsWith(objectName));
    if (job && ["COMPLETED", "FAILED"].includes(job.status)) return job;
    await page.waitForTimeout(2_000);
  }
  throw new Error("Synthetic document indexing did not finish within five minutes.");
}

async function cleanupSyntheticFile(page, workspaceId) {
  if (!page || !workspaceId || page.isClosed()) return;

  const deadline = Date.now() + 300_000;
  while (Date.now() < deadline) {
    const jobs = await requestJson(page, "/api/indexing-jobs", workspaceId);
    const active = jobs.some(
      (job) =>
        job.source?.endsWith(objectName) &&
        ["QUEUED", "PROCESSING"].includes(job.status),
    );
    if (!active) break;
    await page.waitForTimeout(2_000);
  }

  const activeJobs = await requestJson(page, "/api/indexing-jobs", workspaceId);
  if (
    activeJobs.some(
      (job) =>
        job.source?.endsWith(objectName) &&
        ["QUEUED", "PROCESSING"].includes(job.status),
    )
  ) {
    throw new Error("Cannot safely delete the test file while its indexing job is active.");
  }

  await page.reload({ waitUntil: "domcontentloaded" });
  await page.locator("#workspace-select:not([disabled])").waitFor({ timeout: 30_000 });
  await page.locator("#workspace-select").selectOption(workspaceId);
  const fileRow = page.locator("#indexed-file-list .file-row").filter({ hasText: filename });
  if (await fileRow.count()) {
    page.once("dialog", (dialog) => dialog.accept());
    await fileRow.getByRole("button", { name: "Delete" }).click();
    await page.waitForFunction(
      async (targetFilename) => {
        const response = await fetch("/api/storage");
        if (!response.ok) return false;
        const storage = await response.json();
        return storage.files.every((file) => file.filename !== targetFilename);
      },
      filename,
      { timeout: 30_000 },
    );
  }

  const remainingArtifacts = async () => {
    const [documents, storage] = await Promise.all([
      requestJson(page, "/api/documents", workspaceId),
      requestJson(page, "/api/storage", workspaceId),
    ]);
    return {
      documents: documents.filter((document) => document.source.endsWith(objectName)),
      files: storage.files.filter((file) => file.filename === filename),
    };
  };
  let remaining = await remainingArtifacts();
  if (remaining.documents.length || remaining.files.length) {
    const keys = new Set([
      ...remaining.documents.map((document) => document.source),
      ...remaining.files.map((file) => file.key),
    ]);
    const deletion = await page.evaluate(async ({ workspaceId, keys }) => {
      const csrfResponse = await fetch("/api/csrf");
      if (!csrfResponse.ok) return { status: csrfResponse.status };
      const csrf = await csrfResponse.json();
      const statuses = [];
      for (const key of keys) {
        const response = await fetch(`/api/uploads?${new URLSearchParams({ key })}`, {
          method: "DELETE",
          headers: {
            "X-Workspace-ID": workspaceId,
            "X-XSRF-TOKEN": csrf.token,
          },
        });
        statuses.push(response.status);
        if (!response.ok) break;
      }
      return { statuses };
    }, { workspaceId, keys: [...keys] });
    if (deletion.status || deletion.statuses.some((status) => status !== 204)) {
      throw new Error("The authenticated API could not remove the remaining synthetic test data.");
    }

    const deadline = Date.now() + 30_000;
    do {
      remaining = await remainingArtifacts();
      if (!remaining.documents.length && !remaining.files.length) return;
      await page.waitForTimeout(1_000);
    } while (Date.now() < deadline);
  }
  if (remaining.documents.length || remaining.files.length) {
    throw new Error("Synthetic test file or its index remains after cleanup.");
  }
}

async function run() {
  fs.mkdirSync(path.dirname(screenshotPath), { recursive: true });
  const browser = await chromium.launch({ headless: true });
  let page;
  let workspaceId;
  let testFailure;

  try {
    page = await authenticatedPage(browser);
    const pipeline = await requestJson(page, "/api/pipeline-info");
    if (!pipeline.provider.toLowerCase().includes("infomaniak")) {
      throw new Error("The live browser test requires RAG_PROVIDER=infomaniak.");
    }

    const workspaces = await listWorkspaceInventory(page);
    const emptyWorkspace = workspaces.find(
      (workspace) => workspace.documents.length === 0 && workspace.files.length === 0,
    );
    if (!emptyWorkspace) {
      throw new Error("No empty workspace is available; refusing to send existing document metadata.");
    }
    workspaceId = emptyWorkspace.workspaceId;
    await page.locator("#workspace-select").selectOption(workspaceId);

    await page.locator("#file-input").setInputFiles({
      name: filename,
      mimeType: "text/plain",
      buffer: Buffer.from(syntheticText, "utf8"),
    });
    await page.locator("#upload-button").click();
    const fileRow = page.locator("#indexed-file-list .file-row").filter({ hasText: filename });
    await fileRow.waitFor({ state: "visible", timeout: 30_000 });
    await fileRow.getByRole("button", { name: "Index for chat" }).click();

    const indexingJob = await waitForIndexing(page, workspaceId);
    if (indexingJob?.status !== "COMPLETED" || indexingJob.chunks < 1) {
      throw new Error(`Synthetic document indexing failed with status ${indexingJob?.status ?? "unknown"}.`);
    }

    const question = "Welcher Testcode gehört zum fiktiven Projekt ORBIT?";
    await page.locator("#question").fill(question);
    const responsePromise = page.waitForResponse(
      (response) =>
        response.url().endsWith("/api/agent/query") &&
        response.request().method() === "POST",
      { timeout: 240_000 },
    );
    await page.locator("#ask-form button[type='submit']").click();
    const response = await responsePromise;
    if (!response.ok()) {
      throw new Error(`The authenticated agent query failed with HTTP ${response.status()}.`);
    }
    const result = await response.json();
    const completedTools = new Set(
      (result.steps || [])
        .filter((step) => step.status === "completed")
        .map((step) => step.tool),
    );
    if (!completedTools.has("planner") || !completedTools.has("evidence-and-answer")) {
      throw new Error("The agent response did not complete planning and evidence-backed answering.");
    }
    if (!/ORBIT[- ]?731/i.test(result.answer || "")) {
      throw new Error("The answer model did not return the expected synthetic project code.");
    }
    if (!(result.sources || []).some((source) => source.source === filename)) {
      throw new Error("The agent response did not cite the synthetic source document.");
    }

    await page.waitForFunction(
      (expectedAnswer) =>
        [...document.querySelectorAll(".message.assistant .message-body")]
          .some((message) => message.textContent.includes(expectedAnswer)),
      "ORBIT-731",
      { timeout: 30_000 },
    );

    console.log("Live browser E2E passed.");
    console.log(`Planner: ${pipeline.agentPlanner}`);
    console.log(`Answer model: ${pipeline.chatModel}`);
    console.log("Completed: authenticated UI, indexing/embeddings, agent planning, retrieval/reranking, cited answer.");
    console.log("Synthetic answer and citation verified.");
  } catch (error) {
    testFailure = error;
  } finally {
    if (page && !page.isClosed()) {
      await page.screenshot({ path: screenshotPath, fullPage: true }).catch(() => {});
    }

    try {
      if (page && workspaceId) {
        await cleanupSyntheticFile(page, workspaceId);
        console.log("Synthetic uploaded file and index cleanup verified.");
      }
    } catch (cleanupError) {
      testFailure = testFailure
        ? new AggregateError([testFailure, cleanupError], "Live browser test and cleanup failed.")
        : cleanupError;
    }
    if (page && !page.isClosed()) {
      await page.close();
    }
    await browser.close();
  }

  if (testFailure) throw testFailure;
}

run().catch((error) => {
  console.error(`Live browser E2E failed: ${error.message}`);
  process.exitCode = 1;
});
