const workspaceSelect = document.getElementById("workspace-select");
const refreshButton = document.getElementById("refresh-button");
const pageStatus = document.getElementById("page-status");
const fileRows = document.getElementById("file-rows");
const lastUpdated = document.getElementById("last-updated");
const actionPanel = document.getElementById("action-panel");
const actionHeading = document.getElementById("action-heading");
const actionDescription = document.getElementById("action-description");
const signInLink = document.getElementById("sign-in-link");
const retryButton = document.getElementById("retry-button");
const activeStatuses = new Set(["QUEUED", "PROCESSING"]);
const maxIndexFileBytes = 20 * 1024 * 1024;
const stageLabels = {
  QUEUED: "Waiting to start",
  READING: "Scanning and extracting text",
  TRANSCRIBING: "Transcribing audio or video",
  SCREENING: "Screening extracted content",
  REVIEW_PENDING: "Waiting for approval",
  CHUNKING: "Preparing text chunks",
  EMBEDDING: "Creating search embeddings",
  SAVING: "Saving to the search index",
  REINDEXING: "Refreshing embeddings",
  COMPLETED: "Indexing complete",
  FAILED: "Indexing failed",
  REJECTED: "Not indexed"
};
let activeWorkspaceId = sessionStorage.getItem("rag-workspace-id");
let refreshTimer;
let refreshInProgress = false;
let refreshPending = false;

async function apiJson(path, workspaceId) {
  const headers = { Accept: "application/json" };
  if (workspaceId) headers["X-Workspace-ID"] = workspaceId;
  const response = await fetch(path, { headers, credentials: "same-origin" });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const body = await response.json();
      message = body.detail || body.message || message;
    } catch {
      // Keep the HTTP status as the visible error when the response is not JSON.
    }
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return response.json();
}

function showAuthenticationRequired() {
  if (refreshTimer) {
    window.clearInterval(refreshTimer);
    refreshTimer = undefined;
  }
  workspaceSelect.disabled = true;
  refreshButton.disabled = true;
  pageStatus.textContent = "Sign in to view files and indexing status for your workspace.";
  pageStatus.classList.remove("error");
  actionHeading.textContent = "Authentication required";
  actionDescription.textContent =
    "Your workspace files are private. Sign in with your account to load the selected workspace.";
  signInLink.hidden = false;
  retryButton.hidden = true;
  actionPanel.hidden = false;
  for (const id of ["total-count", "active-count", "approval-count", "indexed-count"]) {
    document.getElementById(id).textContent = "—";
  }
  lastUpdated.textContent = "";
  fileRows.replaceChildren();
  const row = document.createElement("tr");
  const cell = document.createElement("td");
  cell.className = "empty-row";
  cell.colSpan = 4;
  cell.textContent = "Sign in to view workspace files.";
  row.append(cell);
  fileRows.append(row);
}

function showLoadError(error) {
  pageStatus.textContent = `Could not load workspace files: ${error.message}`;
  pageStatus.classList.add("error");
  actionHeading.textContent = "Files could not be loaded";
  actionDescription.textContent = "Check your connection, then retry. Your files have not been changed.";
  signInLink.hidden = true;
  retryButton.hidden = false;
  actionPanel.hidden = false;
}

function formatBytes(bytes) {
  if (bytes < 1_000) return `${bytes} B`;
  if (bytes < 1_000_000) return `${(bytes / 1_000).toFixed(1)} kB`;
  if (bytes < 1_000_000_000) return `${(bytes / 1_000_000).toFixed(1)} MB`;
  return `${(bytes / 1_000_000_000).toFixed(2)} GB`;
}

function displayStatus(file) {
  const status = file.status || (file.indexed ? "COMPLETED" : "UPLOADED");
  let label = status.replaceAll("_", " ").toLowerCase();
  label = label.charAt(0).toUpperCase() + label.slice(1);
  if (status === "COMPLETED" && file.indexed) label = "Indexed";
  if (status === "PROCESSING") label = "Processing";
  if (status === "AWAITING_APPROVAL") label = "Awaiting approval";
  return { status, label };
}

function detailFor(file, status) {
  switch (status) {
    case "UPLOADED":
      if (file.sizeBytes > maxIndexFileBytes) return "Uploaded · exceeds the 20 MiB per-file indexing limit";
      return file.indexable ? "Uploaded · ready to submit for screening" : "Uploaded · indexing is unavailable for this file";
    case "QUEUED":
      return "Queued · waiting for a worker";
    case "PROCESSING":
      return `Processing · ${stageLabels[file.stage] || file.stage || "Working"}`;
    case "AWAITING_APPROVAL":
      return "Screening complete · workspace-owner approval is required before indexing";
    case "COMPLETED":
      return file.indexed
        ? `Indexed · ${file.chunks} chunk${file.chunks === 1 ? "" : "s"}`
        : "Processing complete";
    case "FAILED":
      return file.indexed ? "Reprocessing failed · the existing indexed version remains available" : "Processing failed";
    case "REJECTED":
      return "Rejected · this content was not indexed";
    default:
      return "Status unavailable";
  }
}

function addCell(row, label, content, className) {
  const cell = document.createElement("td");
  cell.dataset.label = label;
  if (className) cell.className = className;
  if (content instanceof Node) cell.append(content);
  else cell.textContent = content;
  row.append(cell);
  return cell;
}

function renderFiles(storage) {
  const files = storage.files || [];
  const counts = {
    active: files.filter(file => activeStatuses.has(file.status)).length,
    approval: files.filter(file => file.status === "AWAITING_APPROVAL").length,
    indexed: files.filter(file => file.indexed).length
  };
  document.getElementById("total-count").textContent = String(files.length);
  document.getElementById("active-count").textContent = String(counts.active);
  document.getElementById("approval-count").textContent = String(counts.approval);
  document.getElementById("indexed-count").textContent = String(counts.indexed);
  fileRows.replaceChildren();

  if (!files.length) {
    const row = document.createElement("tr");
    const empty = document.createElement("td");
    empty.className = "empty-row";
    empty.colSpan = 4;
    empty.textContent = "No files have been uploaded to this workspace yet.";
    row.append(empty);
    fileRows.append(row);
  }

  for (const file of files) {
    const row = document.createElement("tr");
    const nameCell = document.createElement("td");
    const name = document.createElement("div");
    name.className = "file-name";
    name.textContent = file.filename;
    const key = document.createElement("small");
    key.className = "file-key";
    key.textContent = file.key;
    nameCell.append(name, key);
    row.append(nameCell);

    addCell(row, "Size", formatBytes(file.sizeBytes));
    const { status, label } = displayStatus(file);
    const badge = document.createElement("span");
    badge.className = `status-badge status-${status.toLowerCase().replaceAll("_", "-")}`;
    badge.textContent = label;
    addCell(row, "Status", badge);

    const detail = document.createElement("span");
    detail.className = "file-detail";
    detail.textContent = detailFor(file, status);
    const detailCell = addCell(row, "Details", detail);
    if (status === "FAILED" && file.errorMessage) {
      const error = document.createElement("small");
      error.className = "file-error";
      error.textContent = file.errorMessage;
      detailCell.append(error);
    }
    fileRows.append(row);
  }

  lastUpdated.textContent = `Updated ${new Date().toLocaleTimeString()}`;
  pageStatus.textContent = files.length
    ? `${files.length} file${files.length === 1 ? "" : "s"} in this workspace.`
    : "This workspace has no uploaded files yet.";
  pageStatus.classList.remove("error");
  actionPanel.hidden = true;

  if (counts.active > 0) {
    if (!refreshTimer) refreshTimer = window.setInterval(refreshFiles, 3_000);
  } else if (refreshTimer) {
    window.clearInterval(refreshTimer);
    refreshTimer = undefined;
  }
}

async function refreshFiles() {
  if (!activeWorkspaceId) return;
  if (refreshInProgress) {
    refreshPending = true;
    return;
  }
  refreshInProgress = true;
  const workspaceId = activeWorkspaceId;
  refreshButton.disabled = true;
  try {
    const storage = await apiJson("/api/storage", workspaceId);
    if (workspaceId === activeWorkspaceId) renderFiles(storage);
  } catch (error) {
    if (workspaceId === activeWorkspaceId) {
      if (error.status === 401) showAuthenticationRequired();
      else showLoadError(error);
    }
    if (workspaceId === activeWorkspaceId && !fileRows.children.length) {
      const row = document.createElement("tr");
      const cell = document.createElement("td");
      cell.className = "empty-row";
      cell.colSpan = 4;
      cell.textContent = "File status could not be loaded.";
      row.append(cell);
      fileRows.append(row);
    }
  } finally {
    refreshInProgress = false;
    refreshButton.disabled = !activeWorkspaceId;
    if (refreshPending) {
      refreshPending = false;
      refreshFiles();
    }
  }
}

async function loadWorkspaces() {
  pageStatus.textContent = "Loading your workspaces…";
  try {
    const workspaces = await apiJson("/api/workspaces");
    if (!workspaces.length) throw new Error("No workspace is assigned to this account.");
    workspaceSelect.replaceChildren();
    for (const workspace of workspaces) {
      const option = document.createElement("option");
      option.value = workspace.id;
      option.textContent = workspace.name;
      workspaceSelect.append(option);
    }
    if (!workspaces.some(workspace => workspace.id === activeWorkspaceId)) {
      activeWorkspaceId = workspaces[0].id;
    }
    workspaceSelect.value = activeWorkspaceId;
    sessionStorage.setItem("rag-workspace-id", activeWorkspaceId);
    workspaceSelect.disabled = false;
    refreshButton.disabled = false;
    await refreshFiles();
  } catch (error) {
    if (error.status === 401) {
      showAuthenticationRequired();
    } else {
      showLoadError(error);
      workspaceSelect.disabled = true;
      refreshButton.disabled = true;
      fileRows.replaceChildren();
      const row = document.createElement("tr");
      const cell = document.createElement("td");
      cell.className = "empty-row";
      cell.colSpan = 4;
      cell.textContent = "Workspace files could not be loaded.";
      row.append(cell);
      fileRows.append(row);
    }
  }
}

workspaceSelect.addEventListener("change", () => {
  activeWorkspaceId = workspaceSelect.value;
  sessionStorage.setItem("rag-workspace-id", activeWorkspaceId);
  if (refreshTimer) {
    window.clearInterval(refreshTimer);
    refreshTimer = undefined;
  }
  pageStatus.textContent = "Loading files for this workspace…";
  pageStatus.classList.remove("error");
  for (const id of ["total-count", "active-count", "approval-count", "indexed-count"]) {
    document.getElementById(id).textContent = "—";
  }
  lastUpdated.textContent = "";
  const row = document.createElement("tr");
  const cell = document.createElement("td");
  cell.className = "empty-row";
  cell.colSpan = 4;
  cell.textContent = "Loading files…";
  row.append(cell);
  fileRows.replaceChildren(row);
  refreshFiles();
});
refreshButton.addEventListener("click", refreshFiles);
retryButton.addEventListener("click", loadWorkspaces);
loadWorkspaces();
