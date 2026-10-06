import { FormEvent, KeyboardEvent, useEffect, useMemo, useRef, useState } from "react";

import {
  ChatResponse,
  DevDeskApiError,
  Ticket,
  ToolCall,
  initializeKnowledge,
  loadTickets,
  sendChat,
} from "./api/devdesk";

type BackendStatus = "checking" | "connected" | "unavailable";
type KnowledgeStatus = "idle" | "initializing" | "ready" | "error";

interface ConversationEntry {
  id: number;
  question: string;
  userId: string;
  sessionId: string;
  response?: ChatResponse;
  error?: string;
}

interface SuggestedQuestion {
  label: string;
  category: string;
  question: string;
}

const SUGGESTED_QUESTIONS: SuggestedQuestion[] = [
  {
    label: "VPN 승인 정책",
    category: "RAG",
    question: "VPN 승인에는 얼마나 걸려?",
  },
  {
    label: "DEV_DB 권한 조회",
    category: "READ TOOL",
    question: "내 DEV_DB 권한 상태 알려줘.",
  },
  {
    label: "PROD_DB 권한 신청",
    category: "WRITE TOOL",
    question: "PROD_DB 권한을 신청해줘. 운영 지원에 필요해.",
  },
  {
    label: "VPN 장애 접수",
    category: "INCIDENT",
    question: "VPN 접속이 계속 실패해. 장애 티켓 접수해줘.",
  },
];

const MULTI_TURN_STEPS = [
  "내 PROD_DB 권한 상태 알려줘.",
  "그 권한 신청해줘. 운영 장애 대응 업무 때문이야.",
];

function formatApiError(error: unknown): string {
  if (error instanceof DevDeskApiError) {
    return error.status ? `${error.message} · HTTP ${error.status}` : error.message;
  }
  return "Backend request failed";
}

function hasPendingState(response: ChatResponse): boolean {
  return (
    response.answer.includes("PENDING") ||
    response.toolCalls.some((call) => call.toolResult?.includes("PENDING"))
  );
}

function describeEvidence(response: ChatResponse): string {
  const evidence: string[] = [];
  if (response.toolCalls.length > 0 || response.toolUsed) evidence.push("Tool trace");
  if (response.sources.length > 0) evidence.push("Retrieved sources");
  return evidence.length > 0 ? evidence.join(" + ") : "Conversational response";
}

function StatusDot({ status }: { status: BackendStatus }) {
  const label =
    status === "connected"
      ? "Connected"
      : status === "unavailable"
        ? "Unavailable"
        : "Checking";

  return (
    <span className={`backend-status backend-status--${status}`}>
      <span className="backend-status__dot" aria-hidden="true" />
      Backend {label}
    </span>
  );
}

function ToolTrace({ calls }: { calls: ToolCall[] }) {
  if (calls.length === 0) return null;

  return (
    <details className="trace-panel" open>
      <summary>
        <span>Execution Trace</span>
        <span className="summary-count">{calls.length}</span>
      </summary>
      <div className="trace-list">
        {calls.map((call, index) => (
          <article className="trace-card" key={`${call.toolName}-${index}`}>
            <div className="trace-card__heading">
              <span className={`trace-icon ${call.success ? "is-success" : "is-error"}`}>
                {call.success ? "✓" : "!"}
              </span>
              <strong>{call.toolName}</strong>
              <span className={`status-pill ${call.success ? "is-success" : "is-error"}`}>
                {call.success ? "SUCCESS" : "FAILED"}
              </span>
            </div>
            <dl className="argument-list">
              {Object.entries(call.toolArguments).map(([key, value]) => (
                <div key={key}>
                  <dt>{key}</dt>
                  <dd>{String(value)}</dd>
                </div>
              ))}
              <div>
                <dt>latency</dt>
                <dd>{call.latencyMs} ms</dd>
              </div>
            </dl>
            {call.toolResult && (
              <details className="tool-result">
                <summary>Tool result</summary>
                <p>{call.toolResult}</p>
              </details>
            )}
          </article>
        ))}
      </div>
    </details>
  );
}

function SourceList({ sources }: { sources: string[] }) {
  if (sources.length === 0) return null;

  return (
    <details className="source-panel" open>
      <summary>
        <span>RAG Sources</span>
        <span className="summary-count">{sources.length}</span>
      </summary>
      <div className="source-list">
        {sources.map((source) => (
          <span className="source-chip" key={source}>
            {source}
          </span>
        ))}
      </div>
    </details>
  );
}

function App() {
  const [userId, setUserId] = useState("user1");
  const [sessionId, setSessionId] = useState("demo");
  const [question, setQuestion] = useState("");
  const [simulatePrimaryFailure, setSimulatePrimaryFailure] = useState(false);
  const [entries, setEntries] = useState<ConversationEntry[]>([]);
  const [isSending, setIsSending] = useState(false);
  const [backendStatus, setBackendStatus] = useState<BackendStatus>("checking");
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [isLoadingTickets, setIsLoadingTickets] = useState(false);
  const [knowledgeStatus, setKnowledgeStatus] = useState<KnowledgeStatus>("idle");
  const [knowledgeError, setKnowledgeError] = useState("");
  const nextId = useRef(1);
  const conversationEndRef = useRef<HTMLDivElement>(null);

  const latestResponse = useMemo(
    () => [...entries].reverse().find((entry) => entry.response)?.response,
    [entries],
  );

  async function refreshTickets() {
    setIsLoadingTickets(true);
    try {
      const result = await loadTickets();
      setTickets(result);
      setBackendStatus("connected");
    } catch {
      setBackendStatus("unavailable");
    } finally {
      setIsLoadingTickets(false);
    }
  }

  async function handleKnowledgeInitialization() {
    if (knowledgeStatus === "initializing") return;

    setKnowledgeStatus("initializing");
    setKnowledgeError("");
    try {
      await initializeKnowledge();
      setKnowledgeStatus("ready");
      setBackendStatus("connected");
    } catch (error) {
      setKnowledgeStatus("error");
      setKnowledgeError(
        error instanceof DevDeskApiError && error.status
          ? `Knowledge initialization failed · HTTP ${error.status}`
          : "Knowledge initialization failed",
      );
      setBackendStatus(
        error instanceof DevDeskApiError && error.status ? "connected" : "unavailable",
      );
    }
  }

  useEffect(() => {
    void refreshTickets();
  }, []);

  useEffect(() => {
    conversationEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [entries, isSending]);

  async function handleSubmit(event?: FormEvent) {
    event?.preventDefault();
    const trimmedQuestion = question.trim();
    const trimmedUserId = userId.trim();
    const trimmedSessionId = sessionId.trim();

    if (!trimmedQuestion || !trimmedUserId || !trimmedSessionId || isSending) return;

    const id = nextId.current++;
    const entry: ConversationEntry = {
      id,
      question: trimmedQuestion,
      userId: trimmedUserId,
      sessionId: trimmedSessionId,
    };

    setEntries((current) => [...current, entry]);
    setQuestion("");
    setIsSending(true);

    try {
      const response = await sendChat({
        question: trimmedQuestion,
        userId: trimmedUserId,
        sessionId: trimmedSessionId,
        simulatePrimaryFailure,
      });
      setEntries((current) =>
        current.map((item) => (item.id === id ? { ...item, response } : item)),
      );
      setBackendStatus("connected");
      if (hasPendingState(response)) void refreshTickets();
    } catch (error) {
      setEntries((current) =>
        current.map((item) =>
          item.id === id ? { ...item, error: formatApiError(error) } : item,
        ),
      );
      setBackendStatus("unavailable");
    } finally {
      setIsSending(false);
    }
  }

  function handleQuestionKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void handleSubmit();
    }
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand-block">
          <div className="brand-mark" aria-hidden="true">D</div>
          <div>
            <div className="brand-line">
              <h1>DevDesk AI</h1>
              <span className="environment-chip">LOCAL DEMO</span>
            </div>
            <p>HelpDesk Agent Demo Console</p>
          </div>
        </div>
        <div className="topbar__meta">
          <span className="tech-line">Spring AI · RAG · Tool Calling</span>
          <StatusDot status={backendStatus} />
        </div>
      </header>

      <main className="workspace">
        <section className="conversation-card" aria-label="Conversation">
          <div className="section-heading conversation-heading">
            <div>
              <span className="eyebrow">LIVE REQUEST FLOW</span>
              <h2>Conversation</h2>
            </div>
            {entries.length > 0 && (
              <button
                aria-label="Clear messages shown in this browser only"
                className="text-button"
                onClick={() => setEntries([])}
                title="Clears only messages shown in this browser. Server-side conversation memory is preserved."
                type="button"
              >
                Clear display
              </button>
            )}
          </div>

          <div className="conversation-stream" aria-live="polite">
            {entries.length === 0 && !isSending && (
              <div className="empty-state">
                <div className="empty-state__mark" aria-hidden="true">D</div>
                <h3>See the agent decision, not just the answer.</h3>
                <p>
                  Choose a verified demo question to inspect RAG sources, Tool execution,
                  and the PENDING write boundary.
                </p>
                <div className="empty-state__flow" aria-label="Demo flow">
                  <span>Question</span><b>→</b><span>Execution</span><b>→</b><span>Evidence</span>
                </div>
              </div>
            )}

            {entries.map((entry) => (
              <div className="exchange" key={entry.id}>
                <div className="message message--user">
                  <div className="message__meta">
                    <strong>You</strong>
                    <span>{entry.userId} · {entry.sessionId}</span>
                  </div>
                  <p>{entry.question}</p>
                </div>

                {entry.response && (
                  <div className="message message--agent">
                    <div className="message__meta">
                      <strong>DevDesk</strong>
                      <div className="message-badges">
                        {entry.response.fallbackUsed && (
                          <span className="badge badge--warning">Fallback used</span>
                        )}
                        {hasPendingState(entry.response) && (
                          <span className="badge badge--pending">PENDING</span>
                        )}
                      </div>
                    </div>
                    <p>{entry.response.answer}</p>
                    <ToolTrace calls={entry.response.toolCalls} />
                    <SourceList sources={entry.response.sources} />
                  </div>
                )}

                {entry.error && (
                  <div className="message message--error" role="alert">
                    <div className="message__meta"><strong>Request error</strong></div>
                    <p>{entry.error}</p>
                    <small>Check that the Spring Boot backend is running on port 8080.</small>
                  </div>
                )}
              </div>
            ))}

            {isSending && (
              <div className="message message--agent message--loading">
                <div className="loading-dots" aria-label="DevDesk is responding">
                  <span /><span /><span />
                </div>
                <span>DevDesk is processing the request…</span>
              </div>
            )}
            <div ref={conversationEndRef} />
          </div>

          <form className="composer" onSubmit={handleSubmit}>
            <textarea
              aria-label="Question"
              disabled={isSending}
              maxLength={4000}
              onChange={(event) => setQuestion(event.target.value)}
              onKeyDown={handleQuestionKeyDown}
              placeholder="Ask about a policy, check access, or create a support ticket…"
              rows={3}
              value={question}
            />
            <div className="composer__footer">
              <span>Enter to send · Shift+Enter for a new line</span>
              <button
                className="send-button"
                disabled={isSending || !question.trim() || !userId.trim() || !sessionId.trim()}
                type="submit"
              >
                {isSending ? "Sending…" : "Send"}
                <span aria-hidden="true">↗</span>
              </button>
            </div>
          </form>
        </section>

        <aside className="side-panel" aria-label="Demo controls and details">
          <section className="panel-block context-block">
            <div className="panel-title">
              <span className="panel-icon" aria-hidden="true">◎</span>
              <div>
                <span className="eyebrow">CONTEXT KEY</span>
                <h2>User &amp; Session</h2>
              </div>
            </div>
            <div className="field-grid">
              <label>
                <span>User ID</span>
                <input value={userId} onChange={(event) => setUserId(event.target.value)} />
              </label>
              <label>
                <span>Session ID</span>
                <input value={sessionId} onChange={(event) => setSessionId(event.target.value)} />
              </label>
            </div>
            <p className="helper-text">
              Memory is isolated by <code>userId:sessionId</code>. Changes apply to the next request.
            </p>
            <label className="toggle-row">
              <input
                checked={simulatePrimaryFailure}
                onChange={(event) => setSimulatePrimaryFailure(event.target.checked)}
                type="checkbox"
              />
              <span>
                <strong>Fallback demo</strong>
                Send model-path requests with primary-failure simulation while enabled
              </span>
            </label>
          </section>

          <section className="panel-block knowledge-block">
            <div className="panel-title panel-title--compact panel-title--split">
              <div>
                <span className="panel-icon" aria-hidden="true">◇</span>
                <h2>Knowledge Base</h2>
              </div>
              <span
                aria-live="polite"
                className={`knowledge-status knowledge-status--${knowledgeStatus}`}
              >
                {knowledgeStatus === "idle" && "Not run in this UI session"}
                {knowledgeStatus === "initializing" && "Initializing…"}
                {knowledgeStatus === "ready" && "Ready for RAG demo"}
                {knowledgeStatus === "error" && "Initialization failed"}
              </span>
            </div>
            <button
              className="initialize-button"
              disabled={knowledgeStatus === "initializing"}
              onClick={() => void handleKnowledgeInitialization()}
              type="button"
            >
              {knowledgeStatus === "initializing"
                ? "Initializing policy knowledge…"
                : "Initialize policy knowledge"}
            </button>
            {knowledgeError && <p className="knowledge-error" role="alert">{knowledgeError}</p>}
          </section>

          <section className="panel-block">
            <div className="panel-title panel-title--compact">
              <span className="panel-icon" aria-hidden="true">⌁</span>
              <h2>Suggested demos</h2>
            </div>
            <p className="suggestion-helper">RAG demo requires policy knowledge initialization.</p>
            <div className="suggestion-list">
              {SUGGESTED_QUESTIONS.map((suggestion) => (
                <button
                  key={suggestion.label}
                  onClick={() => setQuestion(suggestion.question)}
                  type="button"
                >
                  <span>{suggestion.category}</span>
                  <strong>{suggestion.label}</strong>
                </button>
              ))}
            </div>
            <div className="multi-turn-demo">
              <div>
                <span className="eyebrow">MULTI-TURN · SAME SESSION</span>
                <strong>Contextual resource handoff</strong>
              </div>
              {MULTI_TURN_STEPS.map((step, index) => (
                <button key={step} onClick={() => setQuestion(step)} type="button">
                  <span>STEP {index + 1}</span>{step}
                </button>
              ))}
            </div>
          </section>

          <section className="panel-block execution-summary">
            <div className="panel-title panel-title--compact">
              <span className="panel-icon" aria-hidden="true">↳</span>
              <h2>Latest execution</h2>
            </div>
            {latestResponse ? (
              <dl className="summary-grid">
                <div><dt>Observed evidence</dt><dd>{describeEvidence(latestResponse)}</dd></div>
                <div><dt>Tool calls</dt><dd>{latestResponse.toolCalls.length}</dd></div>
                <div><dt>Sources</dt><dd>{latestResponse.sources.length}</dd></div>
                <div><dt>Fallback</dt><dd>{latestResponse.fallbackUsed ? "Used" : "Not used"}</dd></div>
              </dl>
            ) : (
              <p className="panel-empty">Send a request to inspect its execution evidence.</p>
            )}
          </section>

          <section className="panel-block tickets-block">
            <div className="panel-title panel-title--compact panel-title--split">
              <div>
                <span className="panel-icon" aria-hidden="true">▤</span>
                <h2>Tickets</h2>
              </div>
              <button className="text-button" disabled={isLoadingTickets} onClick={() => void refreshTickets()} type="button">
                {isLoadingTickets ? "Loading…" : "Refresh"}
              </button>
            </div>
            {tickets.length === 0 ? (
              <p className="panel-empty">No in-memory tickets in this backend session.</p>
            ) : (
              <div className="ticket-list">
                {tickets.slice(-4).reverse().map((ticket) => (
                  <article key={ticket.id}>
                    <div><strong>{ticket.id}</strong><span className="badge badge--pending">{ticket.status}</span></div>
                    <p>{ticket.type} · {ticket.resource}</p>
                  </article>
                ))}
              </div>
            )}
          </section>

          <section className="panel-block snapshot-block">
            <span className="eyebrow">OFFLINE EVALUATION SNAPSHOT</span>
            <div className="snapshot-row"><strong>15 / 15</strong><span>Agent scenarios passed*</span></div>
            <div className="snapshot-row"><strong>16</strong><span>Deterministic RAG queries</span></div>
            <div className="decision-note">
              <span>Decision</span>
              <p><code>0.30</code> candidate not adopted after unseen evaluation.</p>
            </div>
            <small>* Saved bounded evaluation run, not general Agent accuracy.</small>
          </section>
        </aside>
      </main>

      <footer>
        <span>DevDesk AI · Portfolio Demo Console</span>
        <span>Existing backend APIs only · No browser-side AI credentials</span>
      </footer>
    </div>
  );
}

export default App;
