const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");

const baseUrl = process.env.AGENT_E2E_BASE_URL || "http://localhost:8080";
const expectedContextTokens = Number(process.env.EXPECTED_CONTEXT_TOKENS || 65_536);
const screenshotPath = path.join("test-results", "ollama-context-browser-e2e.png");

function requiredEnv(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Required environment variable ${name} is missing.`);
  }
  return value;
}

async function run() {
  fs.mkdirSync(path.dirname(screenshotPath), { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1365, height: 900 } });
  page.on("console", (message) => {
    if (message.type() === "error") console.error(`Browser console error: ${message.text()}`);
  });
  page.on("pageerror", (error) => console.error(`Browser page error: ${error.message}`));

  try {
    await page.goto(`${baseUrl}/oauth2/authorization/keycloak`, { waitUntil: "domcontentloaded" });
    await page.locator('input[name="username"]').waitFor({ state: "visible", timeout: 30_000 });
    await page.locator('input[name="username"]').fill(requiredEnv("KEYCLOAK_ADMIN_USERNAME"));
    await page.locator('input[name="password"]').fill(requiredEnv("KEYCLOAK_ADMIN_PASSWORD"));
    await page.locator('button[type="submit"], input[type="submit"]').first().click();
    await page.waitForURL((url) => url.origin === baseUrl, { timeout: 60_000 });
    await page.goto(`${baseUrl}/`, { waitUntil: "domcontentloaded" });
    await page.locator("#workspace-select:not([disabled])").waitFor({ timeout: 30_000 });

    const chatSummary = page.locator('[data-pipeline-model="chat-summary"]');
    const plannerSummary = page.locator('[data-pipeline-model="planner-summary"]');
    const contextLabel = `configured context: ${expectedContextTokens.toLocaleString("en-US")} tokens`;
    await chatSummary.waitFor({ state: "visible", timeout: 30_000 });
    await plannerSummary.waitFor({ state: "visible", timeout: 30_000 });
    await page.waitForFunction((label) => {
      const chat = document.querySelector('[data-pipeline-model="chat-summary"]');
      const planner = document.querySelector('[data-pipeline-model="planner-summary"]');
      return chat?.textContent?.includes(label) && planner?.textContent?.includes(label);
    }, contextLabel, { timeout: 30_000 });

    const pipeline = await page.evaluate(async () => {
      const response = await fetch("/api/pipeline-info");
      if (!response.ok) throw new Error(`Pipeline info returned HTTP ${response.status}.`);
      return response.json();
    });
    if (pipeline.provider !== "Local Ollama"
        || pipeline.chatContextTokens !== expectedContextTokens
        || pipeline.agentPlannerContextTokens !== expectedContextTokens) {
      throw new Error("Pipeline info did not report the configured Ollama context for chat and planning.");
    }

    console.log("Ollama context browser E2E passed.");
    console.log(`Authenticated Models in use UI shows a configured ${expectedContextTokens.toLocaleString("en-US")}-token context.`);
    console.log("No inference requests were made.");
  } finally {
    if (!page.isClosed()) {
      await page.screenshot({ path: screenshotPath, fullPage: true }).catch(() => {});
      await page.close();
    }
    await browser.close();
  }
}

run().catch((error) => {
  console.error(`Ollama context browser E2E failed: ${error.stack || error.message}`);
  process.exitCode = 1;
});
