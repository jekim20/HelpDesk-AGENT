"""OpenAI-compatible deterministic test double. Never used by the deployment Compose."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import re
import threading
import time


DIMENSIONS = 8
LOCK = threading.Lock()
STATE = {"mode": "success", "chat_completions": 0, "embedding_requests": 0}


def embedding_for(value: str) -> list[float]:
    text = value.lower()
    features = [0.0] * DIMENSIONS
    if "vpn" in text or "브이피엔" in text:
        features[0] = 2.0
    if "dev_db" in text:
        features[1] = 1.5
    if "prod_db" in text:
        features[2] = 1.5
    if any(term in text for term in ("장애", "오류", "실패", "접속")):
        features[3] = 1.0
    if any(term in text for term in ("권한", "접근")):
        features[4] = 1.0
    if "티켓" in text or "ticket" in text:
        features[5] = 0.8
    if any(term in text for term in ("2영업일", "얼마나", "처리 기간", "승인")):
        features[6] = 1.5
    if any(term in text for term in ("계정", "비밀번호", "otp", "password")):
        features[7] = 1.0
    norm = math.sqrt(sum(value * value for value in features))
    if norm == 0:
        features[-1] = 1.0
        return features
    return [value / norm for value in features]


class Handler(BaseHTTPRequestHandler):
    server_version = "DeterministicOpenAIMock/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, _format, *_args):
        # Request bodies and headers are intentionally never logged.
        return

    def reply(self, status: int, body: dict):
        encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def read_json(self) -> dict:
        limit = 1_000_000
        encoding = self.headers.get("Transfer-Encoding", "").lower().strip()

        if encoding == "chunked":
            if self.headers.get("Content-Length") is not None:
                raise ValueError("conflicting body framing")

            chunks = []
            total = 0

            while True:
                line = self.rfile.readline(128)
                if not line.endswith(b"\r\n"):
                    raise ValueError("invalid chunk header")

                size = int(line.split(b";", 1)[0].strip(), 16)

                if size == 0:
                    for _ in range(20):
                        trailer = self.rfile.readline(8192)
                        if trailer == b"\r\n":
                            break
                        if not trailer or not trailer.endswith(b"\r\n"):
                            raise ValueError("invalid chunk trailer")
                    else:
                        raise ValueError("too many trailers")
                    break

                total += size
                if total > limit:
                    raise ValueError("request too large")

                chunk = self.rfile.read(size)
                if len(chunk) != size or self.rfile.read(2) != b"\r\n":
                    raise ValueError("invalid chunk body")

                chunks.append(chunk)

            raw = b"".join(chunks)

        elif encoding:
            raise ValueError("unsupported transfer encoding")

        else:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > limit:
                raise ValueError("request too large")
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError("incomplete body")

        result = json.loads(raw or b"{}")
        if not isinstance(result, dict):
            raise ValueError("JSON object required")
        return result

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"status": "ok"})
            return
        if self.path == "/__test__/state":
            with LOCK:
                state = dict(STATE)
            self.reply(200, state)
            return
        self.reply(404, {"error": {"message": "not found", "type": "not_found"}})

    def do_POST(self):
        try:
            body = self.read_json()
        except (ValueError, json.JSONDecodeError):
            self.reply(400, {"error": {"message": "invalid request", "type": "invalid_request_error"}})
            return

        if self.path == "/__test__/reset":
            with LOCK:
                STATE.update(mode="success", chat_completions=0, embedding_requests=0)
            self.reply(200, {"status": "reset"})
            return

        if self.path == "/__test__/mode":
            mode = body.get("mode")
            if mode not in ("success", "fail"):
                self.reply(400, {"error": {"message": "unsupported mode", "type": "invalid_request_error"}})
                return
            with LOCK:
                STATE["mode"] = mode
            self.reply(200, {"mode": mode})
            return

        if re.fullmatch(r"/v1/embeddings", self.path):
            inputs = body.get("input", [])
            if isinstance(inputs, str):
                inputs = [inputs]
            if not isinstance(inputs, list):
                self.reply(400, {"error": {"message": "input must be text or an array", "type": "invalid_request_error"}})
                return
            # Spring AI probes the model dimensions with an empty input list.
            # Return one deterministic vector for that probe so its dimensions()
            # call can initialize SimpleVectorStore before real documents arrive.
            if not inputs:
                inputs = [""]
            with LOCK:
                STATE["embedding_requests"] += 1
            vectors = [embedding_for(str(value)) for value in inputs]
            self.reply(200, {
                "object": "list",
                "data": [{"object": "embedding", "index": index, "embedding": vector}
                         for index, vector in enumerate(vectors)],
                "model": body.get("model", "text-embedding-3-small"),
                "usage": {"prompt_tokens": 1, "total_tokens": 1},
            })
            return

        if re.fullmatch(r"/v1/chat/completions", self.path):
            with LOCK:
                STATE["chat_completions"] += 1
                mode = STATE["mode"]
            if mode == "fail":
                self.reply(400, {"error": {"message": "deterministic E2E model failure", "type": "invalid_request_error"}})
                return

            messages = body.get("messages", [])
            prompt = " ".join(str(message.get("content", "")) for message in messages
                               if isinstance(message, dict))
            content = (
                "테스트 AI 응답: VPN 신규 권한 신청은 HelpDesk 티켓으로 접수하며, "
                "팀장 승인과 보안 검토를 거쳐 일반적으로 2영업일 이내 처리됩니다."
            )
            self.reply(200, {
                "id": "chatcmpl-e2e-deterministic",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": body.get("model", "e2e-deterministic"),
                "choices": [{"index": 0, "message": {"role": "assistant", "content": content},
                             "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30},
            })
            return

        self.reply(404, {"error": {"message": "not found", "type": "not_found"}})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8081), Handler).serve_forever()
