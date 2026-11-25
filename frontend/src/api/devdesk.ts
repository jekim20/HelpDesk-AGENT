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
  ticketId?: string | null;
  ticketStatus?: string | null;
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
    readonly code?: string,
    readonly retryable = false,
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
    throw new DevDeskApiError("Backend unavailable", undefined, "BACKEND_UNAVAILABLE", true);
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
    throw new DevDeskApiError("Backend unavailable", undefined, "BACKEND_UNAVAILABLE", true);
  }

  if (!response.ok) {
    let message = statusMessage(response.status);
    let code: string | undefined;
    let retryable = false;
    try {
      const body = (await response.json()) as { answer?: unknown; code?: unknown; message?: unknown; retryable?: unknown };
      if (typeof body.answer === "string" && body.answer && [400, 409].includes(response.status)) {
        message = body.answer;
      }
      if (typeof body.code === "string" && typeof body.message === "string") {
        code = body.code;
        retryable = body.retryable === true;
        const safeMessages: Record<string, string> = {
          AI_NOT_CONFIGURED: "AI 기능이 설정되지 않았습니다. 관리자에게 문의해 주세요.",
          AI_AUTHENTICATION_FAILED: "AI 서비스 인증에 실패했습니다. 관리자 확인이 필요합니다.",
          POLICY_INDEX_UNAVAILABLE: "정책 검색 인덱스를 사용할 수 없습니다. 관리자 확인이 필요합니다.",
          AI_TEMPORARILY_UNAVAILABLE: "AI 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해 주세요.",
          AI_REQUEST_FAILED: "AI 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        };
        message = safeMessages[code] ?? "요청을 처리하지 못했습니다. 관리자에게 문의해 주세요.";
      }
    } catch {
      // Keep the status-based message when the response is not JSON.
    }
    throw new DevDeskApiError(message, response.status, code, retryable);
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
    throw new DevDeskApiError("Backend unavailable", undefined, "BACKEND_UNAVAILABLE", true);
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

export function sendChat(request: ChatRequest, idempotencyKey: string): Promise<ChatResponse> {
  return requestJson<ChatResponse>("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(request),
  });
}

export function loadTickets(): Promise<Ticket[]> {
  return requestJson<Ticket[]>("/api/admin/tickets");
}

export function initializeKnowledge(): Promise<IngestResult[]> {
  return requestJson<IngestResult[]>("/api/ingest", { method: "POST" });
}
