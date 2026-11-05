export interface ChatRequest {
  question: string;
  sessionId: string;
  simulatePrimaryFailure: boolean;
}

export interface CurrentUser {
  username: string;
  roles: string[];
}

export interface ToolCall {
  toolName: string;
  toolArguments: Record<string, unknown>;
  toolResult: string;
  success: boolean;
  latencyMs: number;
}

export interface ChatResponse {
  answer: string;
  sessionId: string;
  sources: string[];
  toolUsed: boolean;
  toolCalls: ToolCall[];
  fallbackUsed: boolean;
}

export interface Ticket {
  id: string;
  type: "ACCESS_REQUEST" | "INCIDENT" | "ACCOUNT_SUPPORT";
  resource: string;
  reason: string;
  requestedBy: string;
  requestedAt: string;
  status: string;
}

export interface IngestResult {
  source: string;
  chunks: number;
}

export class DevDeskApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = "DevDeskApiError";
  }
}

let csrfToken = "";
let csrfHeaderName = "X-XSRF-TOKEN";

async function refreshCsrfToken(): Promise<void> {
  let response: Response;
  try {
    response = await fetch("/api/csrf", { credentials: "same-origin" });
  } catch {
    throw new DevDeskApiError("Backend unavailable");
  }
  if (!response.ok) throw new DevDeskApiError("Could not initialize browser security token", response.status);
  const data = (await response.json()) as { token: string; headerName: string };
  csrfToken = data.token;
  csrfHeaderName = data.headerName;
}

async function ensureCsrfToken(): Promise<void> {
  if (!csrfToken) await refreshCsrfToken();
}

function statusMessage(status: number): string {
  if (status === 401) return "Your session has expired. Sign in again.";
  if (status === 403) return "Access denied or the security token expired. Refresh and try again.";
  return `Backend request failed · HTTP ${status}`;
}

async function requestJson<T>(input: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  const changesState = !["GET", "HEAD", "OPTIONS"].includes(method);
  if (changesState) await ensureCsrfToken();

  const headers = new Headers(init?.headers);
  if (changesState) headers.set(csrfHeaderName, csrfToken);

  let response: Response;

  try {
    response = await fetch(input, { ...init, headers, credentials: "same-origin" });
  } catch {
    throw new DevDeskApiError("Backend unavailable");
  }

  if (!response.ok) {
    throw new DevDeskApiError(statusMessage(response.status), response.status);
  }

  return (await response.json()) as T;
}

export async function signIn(username: string, password: string): Promise<CurrentUser> {
  await ensureCsrfToken();
  let response: Response;
  try {
    response = await fetch("/login", {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        [csrfHeaderName]: csrfToken,
      },
      body: new URLSearchParams({ username, password }),
    });
  } catch {
    throw new DevDeskApiError("Backend unavailable");
  }
  if (response.status === 401) throw new DevDeskApiError("Incorrect username or password", 401);
  if (!response.ok) throw new DevDeskApiError(statusMessage(response.status), response.status);

  // Spring Security clears the submitted token after authentication.
  csrfToken = "";
  await refreshCsrfToken();
  return loadCurrentUser();
}

export async function signOut(): Promise<void> {
  await ensureCsrfToken();
  let response: Response;
  try {
    response = await fetch("/logout", {
      method: "POST",
      credentials: "same-origin",
      headers: { [csrfHeaderName]: csrfToken },
    });
  } catch {
    throw new DevDeskApiError("Backend unavailable");
  }
  if (!response.ok) throw new DevDeskApiError(statusMessage(response.status), response.status);
  csrfToken = "";
  await refreshCsrfToken();
}

export async function loadCurrentUser(): Promise<CurrentUser> {
  await ensureCsrfToken();
  return requestJson<CurrentUser>("/api/me");
}

export function loadHistory(sessionId: string): Promise<string[]> {
  return requestJson<string[]>(`/api/history?sessionId=${encodeURIComponent(sessionId)}`);
}

export function sendChat(request: ChatRequest): Promise<ChatResponse> {
  return requestJson<ChatResponse>("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
}

export function loadTickets(): Promise<Ticket[]> {
  return requestJson<Ticket[]>("/api/admin/tickets");
}

export function initializeKnowledge(): Promise<IngestResult[]> {
  return requestJson<IngestResult[]>("/api/ingest", { method: "POST" });
}
