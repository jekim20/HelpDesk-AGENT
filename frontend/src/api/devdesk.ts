export interface ChatRequest {
  question: string;
  sessionId: string;
  userId: string;
  simulatePrimaryFailure: boolean;
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

export class DevDeskApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = "DevDeskApiError";
  }
}

async function requestJson<T>(input: string, init?: RequestInit): Promise<T> {
  let response: Response;

  try {
    response = await fetch(input, init);
  } catch {
    throw new DevDeskApiError("Backend unavailable");
  }

  if (!response.ok) {
    throw new DevDeskApiError("Backend request failed", response.status);
  }

  return (await response.json()) as T;
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
