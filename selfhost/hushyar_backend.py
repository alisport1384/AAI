"""Opt-in, extractive personal-document backend for Hushyar.

Python 3.10+ stdlib + SQLite FTS5 only. No external API, remote model, generated facts,
code execution, document downloads, or telemetry. The Android app works without this process.
Run `python3 -m selfhost.hushyar_backend --help` from the repository root.
"""

from __future__ import annotations

import argparse
from collections import deque
from contextlib import closing
import hashlib
import hmac
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import socket
import sqlite3
import ssl
import tempfile
import threading
import time
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

SCHEMA = "hushyar.docs/1"
MAX_BODY = 160_000
MAX_DOCUMENT = 100_000  # UTF-8 bytes; large documents must be split by the owner
MAX_QUERY = 400
THREAD_ID = re.compile(r"^[A-Za-z0-9_-]{3,70}$")
DOC_ID = re.compile(r"^[a-f0-9]{32}$")
TERMS = re.compile(r"[\w\u0600-\u06ff]+", re.UNICODE)
NUMBERS = re.compile(r"(?<!\w)\d+(?:[/.,]\d+)?(?!\w)")
STOP = {"the", "and", "for", "what", "from", "with", "how", "are", "this", "that", "about",
        "این", "آن", "برای", "چیست", "چیه", "چقدر", "چند", "کجا", "کدام", "از", "به", "در", "را", "است", "هست", "های", "چه", "من", "اسناد", "اسنادم"}


class RequestError(Exception):
    def __init__(self, code: str, message: str, status: int = 400):
        super().__init__(message)
        self.code, self.status = code, status


def normalized(value: str) -> str:
    import unicodedata
    text = unicodedata.normalize("NFKC", value).lower()
    text = text.replace("ي", "ی").replace("ك", "ک").replace("ى", "ی").replace("ة", "ه")
    text = text.replace("\u200c", " ").replace("\u200d", " ")
    text = "".join(str(unicodedata.decimal(c)) if c.isdecimal() else c
                   for c in text if not unicodedata.combining(c))
    # A one-digit product size in «نبشی4» must not be merged into the product word.
    return re.sub(r"(?<=[a-z\u0600-\u06ff])(?=\d)|(?<=\d)(?=[a-z\u0600-\u06ff])", " ", text)


def query_terms(value: str) -> list[str]:
    return list(dict.fromkeys(term for term in TERMS.findall(normalized(value))
                              if term not in STOP and (len(term) >= 2 or term.isdecimal())))[:16]


def needs_live_evidence(question: str) -> bool:
    q = normalized(question)
    fresh = any(word in q for word in ("امروز", "الان", "لحظه", "به روز", "بروز", "تازه",
                                       "today", "now", "latest", "current", "live", "up to date"))
    # «قیمت روز» is a common request for a *current* price. Match whole words so an
    # unrelated word such as «روزنامه» does not become a false freshness signal.
    fresh = fresh or bool(re.search(r"(?<!\w)(?:روز|فعلی|کنونی|آخرین|جدید|حالا)(?!\w)", q))
    price = any(word in q for word in ("قیمت", "نرخ", "price", "cost", "usd", "دلار"))
    return fresh and price


class _HtmlText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.skip = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in {"script", "style", "noscript"}:
            self.skip += 1
        if tag in {"p", "div", "li", "br", "tr", "h1", "h2", "h3", "td"}:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "noscript"} and self.skip:
            self.skip -= 1
        if tag in {"p", "div", "li", "tr", "h1", "h2", "h3"}:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self.skip:
            self.parts.append(data)


def clean_document(text: str, kind: str) -> str:
    if kind not in {"text", "markdown", "csv", "html"}:
        raise RequestError("UNSUPPORTED_FORMAT", "فقط متن، Markdown، CSV و HTML پشتیبانی می‌شوند.")
    if "\x00" in text:
        raise RequestError("INVALID_DOCUMENT", "فایل متنی معتبر نیست.")
    if kind == "html":
        parser = _HtmlText()
        parser.feed(text)
        text = "".join(parser.parts)
    text = re.sub(r"[ \t]+", " ", text).strip()
    if len(text.encode("utf-8")) > MAX_DOCUMENT:
        raise RequestError("DOCUMENT_TOO_LARGE", "فایل بیش از ۱۰۰ کیلوبایت متن دارد؛ آن را تقسیم کن.", 413)
    if not text:
        raise RequestError("EMPTY_DOCUMENT", "فایل متنی خالی است.")
    return text


def make_chunks(text: str, max_chars: int = 720, overlap: int = 70) -> list[str]:
    """Keep bounded, overlapping *verbatim* spans so every quoted fact has a source."""
    result = []
    start = 0
    while start < len(text):
        end = min(len(text), start + max_chars)
        if end < len(text):
            cut = max(text.rfind("\n", start + 300, end), text.rfind(" ", start + 300, end))
            if cut > start:
                end = cut + 1
        part = text[start:end].strip()
        if part:
            result.append(part)
        if end == len(text):
            break
        start = max(start + 1, end - overlap)
    return result


def checked_thread(thread_id: object) -> str:
    if not isinstance(thread_id, str) or not THREAD_ID.fullmatch(thread_id):
        raise RequestError("INVALID_THREAD", "شناسهٔ گفت‌وگو معتبر نیست.")
    return thread_id


class DocumentStore:
    def __init__(self, path: str | Path):
        self.path = str(path)
        Path(self.path).parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with closing(self._connect()) as db, db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS documents (
                    id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, title TEXT NOT NULL,
                    digest TEXT NOT NULL, created_at INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS documents_scope ON documents(thread_id);
                CREATE TABLE IF NOT EXISTS chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL, body TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS chunks_doc ON chunks(document_id);
                CREATE VIRTUAL TABLE IF NOT EXISTS chunk_fts USING fts5(body, tokenize='unicode61');
            """)
        # The SQLite file holds plaintext personal documents. Repair permissive file modes
        # from earlier launches; the CLI also sets umask before *creating* DB/journal files.
        os.chmod(self.path, 0o600)

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA busy_timeout=5000")
        return db

    def add(self, thread_id: str, title: str, text: str, kind: str) -> dict:
        checked_thread(thread_id)
        if not isinstance(title, str) or not title.strip() or len(title) > 120:
            raise RequestError("INVALID_TITLE", "عنوان باید بین ۱ تا ۱۲۰ نویسه باشد.")
        if not isinstance(text, str):
            raise RequestError("INVALID_DOCUMENT", "متن سند معتبر نیست.")
        cleaned = clean_document(text, kind)
        digest = hashlib.sha256(cleaned.encode("utf-8")).hexdigest()
        title = title.strip()
        with closing(self._connect()) as db, db:
            existing = db.execute("SELECT id FROM documents WHERE thread_id=? AND digest=? AND title=?",
                                  (thread_id, digest, title)).fetchone()
            if existing:
                return {"id": existing["id"], "chunks": db.execute(
                    "SELECT COUNT(*) FROM chunks WHERE document_id=?", (existing["id"],)).fetchone()[0], "duplicate": True}
            if db.execute("SELECT COUNT(*) FROM documents WHERE thread_id=?", (thread_id,)).fetchone()[0] >= 100:
                raise RequestError("DOCUMENT_LIMIT", "سقف ۱۰۰ سند برای این گفتگو پر شده است.", 409)
            doc_id = uuid4().hex
            db.execute("INSERT INTO documents VALUES (?,?,?,?,?)",
                       (doc_id, thread_id, title, digest, int(time.time())))
            parts = make_chunks(cleaned)
            for ordinal, part in enumerate(parts):
                row = db.execute("INSERT INTO chunks(document_id, ordinal, body) VALUES (?,?,?)",
                                 (doc_id, ordinal, part))
                db.execute("INSERT INTO chunk_fts(rowid, body) VALUES (?,?)", (row.lastrowid, normalized(part)))
        return {"id": doc_id, "chunks": len(parts), "duplicate": False}

    def list(self, thread_id: str) -> list[dict]:
        checked_thread(thread_id)
        with closing(self._connect()) as db:
            rows = db.execute("SELECT d.id,d.title,d.created_at,COUNT(c.id) AS chunks "
                              "FROM documents d LEFT JOIN chunks c ON d.id=c.document_id "
                              "WHERE d.thread_id=? GROUP BY d.id ORDER BY d.created_at DESC,d.id DESC",
                              (thread_id,)).fetchall()
            return [dict(row) for row in rows]

    def delete(self, thread_id: str, doc_id: str) -> bool:
        checked_thread(thread_id)
        if not isinstance(doc_id, str) or not DOC_ID.fullmatch(doc_id):
            raise RequestError("INVALID_ID", "شناسهٔ سند معتبر نیست.")
        with closing(self._connect()) as db, db:
            ids = [r[0] for r in db.execute(
                "SELECT c.id FROM chunks c JOIN documents d ON d.id=c.document_id "
                "WHERE d.thread_id=? AND d.id=?", (thread_id, doc_id))]
            if not ids:
                return False
            db.executemany("DELETE FROM chunk_fts WHERE rowid=?", ((i,) for i in ids))
            db.execute("DELETE FROM documents WHERE id=? AND thread_id=?", (doc_id, thread_id))
            return True

    def delete_thread(self, thread_id: str) -> int:
        checked_thread(thread_id)
        with closing(self._connect()) as db, db:
            ids = [r[0] for r in db.execute(
                "SELECT c.id FROM chunks c JOIN documents d ON d.id=c.document_id WHERE d.thread_id=?",
                (thread_id,))]
            count = db.execute("SELECT COUNT(*) FROM documents WHERE thread_id=?", (thread_id,)).fetchone()[0]
            db.executemany("DELETE FROM chunk_fts WHERE rowid=?", ((i,) for i in ids))
            db.execute("DELETE FROM documents WHERE thread_id=?", (thread_id,))
            return count

    def ask(self, thread_id: str, question: str) -> dict:
        checked_thread(thread_id)
        if not isinstance(question, str) or not question.strip() or len(question) > MAX_QUERY:
            raise RequestError("INVALID_QUESTION", "پرسش باید بین ۱ تا ۴۰۰ نویسه باشد.")
        base = {"schema": SCHEMA, "refused": True, "citations": [], "bullets": [], "missing": []}
        if needs_live_evidence(question):
            return dict(base, text="سندهای ذخیره‌شده شاهد قیمت امروز نیستند. برای جستجوی وب، بدون پیشوند «از اسنادم:» بپرس.",
                        missing=["شاهد زندهٔ قیمت"], reason="FRESHNESS_REQUIRED")
        terms = query_terms(question)
        if not terms:
            return dict(base, text="موضوع پرسش برای جستجو در سندها مشخص نیست.",
                        missing=["موضوع مشخص"], reason="EMPTY_QUERY")
        # FTS operators are never copied from the user: quote each normalized lexical term.
        match = " OR ".join('"' + term.replace('"', '') + '"' for term in terms)
        with closing(self._connect()) as db:
            rows = db.execute(
                "SELECT d.id,d.title,c.ordinal,c.body,bm25(chunk_fts) AS score "
                "FROM chunk_fts JOIN chunks c ON c.id=chunk_fts.rowid "
                "JOIN documents d ON d.id=c.document_id "
                "WHERE chunk_fts MATCH ? AND d.thread_id=? "
                "ORDER BY score ASC,c.id ASC LIMIT 40", (match, thread_id)).fetchall()
        # FTS OR may return a wrong size/model just because the product name matched.
        # Numeric constraints must appear as complete tokens in the *same* retrieved chunk.
        required_numbers = set(NUMBERS.findall(normalized(question)))
        rows = [row for row in rows if required_numbers.issubset(set(NUMBERS.findall(normalized(row["body"]))))]
        if not rows:
            return dict(base, text="در اسناد همین گفتگو شاهدی با مشخصات دقیقِ پرسش پیدا نشد؛ چیزی حدس نمی‌زنم.",
                        missing=["بخش مرتبط و مشخصات دقیق در اسناد این گفتگو"], reason="NO_MATCH")
        citations = []
        bullets = []
        for row in rows[:5]:
            body = row["body"]
            # The displayed quote is a *verbatim* substring, not a model's paraphrase.
            term_pos = [normalized(body).find(term) for term in terms]
            first = min((pos for pos in term_pos if pos >= 0), default=0)
            begin = max(0, first - 65)
            quote = body[begin:begin + 340].strip()
            if not quote:
                continue
            ref = "D:" + row["id"] + ":" + str(row["ordinal"])
            citations.append({"ref": ref, "label": row["title"][:100], "url": None, "quote": quote})
            bullets.append("«" + quote + "»")
        if not citations:
            return dict(base, text="بخش مرتبطِ خوانا پیدا نشد.", missing=["متن سند"], reason="NO_MATCH")
        return {"schema": SCHEMA, "refused": False,
                "text": "این عبارت‌ها عیناً از سندهای همین گفتگو بازیابی شدند؛ تحلیل مدل یا تأییدِ تازهٔ وب نیستند.",
                "bullets": bullets, "citations": citations, "missing": [], "reason": "EXTRACTIVE_FTS5"}

    def backup(self, output: str | Path) -> None:
        out = Path(output)
        out.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if out.resolve() == Path(self.path).resolve():
            raise ValueError("Backup must have a different path")
        # Write a consistent snapshot into a private file, then atomically replace the
        # destination. An interrupted backup must not overwrite yesterday's good copy.
        with tempfile.NamedTemporaryFile(dir=out.parent, prefix=f".{out.name}.",
                                         suffix=".tmp", delete=False) as slot:
            temporary = Path(slot.name)  # NamedTemporaryFile defaults to 0600
        try:
            with closing(self._connect()) as source, closing(sqlite3.connect(temporary)) as target:
                source.backup(target)
            with temporary.open("rb") as snapshot:
                os.fsync(snapshot.fileno())
            os.replace(temporary, out)
        finally:
            temporary.unlink(missing_ok=True)


class RateLimiter:
    def __init__(self, limit: int = 60, window: float = 60.0):
        self.limit, self.window = limit, window
        self.hits: dict[str, deque] = {}
        self.lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self.lock:
            hits = self.hits.setdefault(key, deque())
            while hits and hits[0] <= now - self.window:
                hits.popleft()
            if len(hits) >= self.limit:
                return False
            hits.append(now)
            # Reclaim old IPs to avoid unbounded memory on a publicly reachable process.
            if len(self.hits) > 1024:
                active = {ip: q for ip, q in self.hits.items() if q and q[-1] > now - self.window}
                self.hits = dict(sorted(active.items(), key=lambda item: item[1][-1], reverse=True)[:1024])
            return True


class Service:
    def __init__(self, database: str | Path, token: str, rate_limit: int = 60):
        if len(token) < 32:
            raise ValueError("HUSHYAR_BACKEND_TOKEN must be at least 32 characters")
        self.store = DocumentStore(database)
        self.token = token
        self.limiter = RateLimiter(rate_limit)
        self.metrics: dict[str, int] = {"requests": 0, "refusals": 0, "errors": 0}
        self.times: deque[float] = deque(maxlen=1000)
        self.lock = threading.Lock()

    def record(self, duration: float, status: int, refused: bool = False) -> None:
        with self.lock:
            self.metrics["requests"] += 1
            if refused:
                self.metrics["refusals"] += 1
            if status >= 400:
                self.metrics["errors"] += 1
            self.times.append(duration)

    def report(self) -> dict:
        with self.lock:
            times = sorted(self.times)
            p95 = times[max(0, int(len(times) * .95) - 1)] if times else 0.0
            return {"schema": SCHEMA, **self.metrics, "latency_p95_ms": round(p95 * 1000, 2)}


def make_handler(service: Service) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "HushyarPersonal/1"
        sys_version = ""

        def log_message(self, fmt: str, *args: object) -> None:
            # Deliberately no prompts, document titles, bodies, tokens or paths in logs.
            pass

        def _reply(self, status: int, payload: dict) -> None:
            data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _handle(self, method: str) -> None:
            start = time.monotonic()
            status = 200
            refused = False
            try:
                if not service.limiter.allow(self.client_address[0]):
                    raise RequestError("RATE_LIMIT", "درخواست‌ها بیش از حد مجازند؛ بعداً دوباره تلاش کن.", 429)
                auth = self.headers.get("Authorization", "")
                if not auth.startswith("Bearer ") or not hmac.compare_digest(auth[7:], service.token):
                    raise RequestError("UNAUTHORIZED", "کلید دسترسی معتبر نیست.", 401)
                path = urlsplit(self.path)
                if method == "GET" and path.path == "/health":
                    payload = {"schema": SCHEMA, "status": "ok", "retrieval": "sqlite-fts5", "model": "none"}
                elif method == "GET" and path.path == "/metrics":
                    payload = service.report()
                elif method == "GET" and path.path == "/v1/documents":
                    thread = parse_qs(path.query).get("thread_id", [None])[0]
                    payload = {"schema": SCHEMA, "documents": service.store.list(checked_thread(thread))}
                elif method == "DELETE" and path.path.startswith("/v1/documents/"):
                    thread = parse_qs(path.query).get("thread_id", [None])[0]
                    doc_id = path.path.removeprefix("/v1/documents/")
                    deleted = service.store.delete(checked_thread(thread), doc_id)
                    payload = {"schema": SCHEMA, "deleted": deleted}
                elif method == "DELETE" and path.path.startswith("/v1/threads/") and path.path.endswith("/documents"):
                    thread = path.path[len("/v1/threads/"):-len("/documents")]
                    count = service.store.delete_thread(checked_thread(thread))
                    payload = {"schema": SCHEMA, "deleted_count": count}
                elif method == "POST" and path.path in {"/v1/documents", "/v1/query"}:
                    size = self.headers.get("Content-Length", "")
                    if not size.isascii() or not size.isdigit() or int(size) > MAX_BODY:
                        raise RequestError("BODY_TOO_LARGE", "اندازهٔ درخواست معتبر نیست یا بیش از حد است.", 413)
                    if self.headers.get("Content-Type", "").split(";")[0].strip().lower() != "application/json":
                        raise RequestError("CONTENT_TYPE", "فقط JSON پذیرفته می‌شود.", 415)
                    try:
                        body = json.loads(self.rfile.read(int(size)).decode("utf-8"))
                    except (UnicodeError, ValueError):
                        raise RequestError("INVALID_JSON", "درخواست JSON خوانا نیست.") from None
                    if not isinstance(body, dict):
                        raise RequestError("INVALID_JSON", "درخواست باید شیء JSON باشد.")
                    thread = checked_thread(body.get("thread_id"))
                    if path.path == "/v1/documents":
                        result = service.store.add(thread, body.get("title"), body.get("text"), body.get("kind"))
                        payload = {"schema": SCHEMA, **result}
                        status = 201 if not result["duplicate"] else 200
                    else:
                        payload = service.store.ask(thread, body.get("question"))
                        refused = payload["refused"]
                else:
                    raise RequestError("NOT_FOUND", "مسیر شناخته‌شده نیست.", 404)
                self._reply(status, payload)
            except RequestError as exc:
                status = exc.status
                self._reply(status, {"error": {"code": exc.code, "message": str(exc)}})
            except (sqlite3.Error, OSError):
                status = 503
                self._reply(status, {"error": {"code": "STORAGE_ERROR", "message": "پایگاه دادهٔ شخصی در دسترس نیست."}})
            except Exception:
                status = 500
                self._reply(status, {"error": {"code": "SERVER_ERROR", "message": "خطای داخلی؛ جزئیات در پاسخ فاش نشد."}})
            finally:
                service.record(time.monotonic() - start, status, refused)

        def do_GET(self) -> None:
            self._handle("GET")

        def do_POST(self) -> None:
            self._handle("POST")

        def do_DELETE(self) -> None:
            self._handle("DELETE")

    return Handler


def create_server(service: Service, host: str = "127.0.0.1", port: int = 8765,
                  cert: str | None = None, key: str | None = None) -> ThreadingHTTPServer:
    if (cert is None) != (key is None):
        raise ValueError("Certificate and private key must be supplied together")
    if not cert and host not in {"127.0.0.1", "::1", "localhost"}:
        raise ValueError("TLS is mandatory when binding beyond localhost")
    server = ThreadingHTTPServer((host, port), make_handler(service))
    server.daemon_threads = True
    if cert:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(cert, key)
        server.socket = ctx.wrap_socket(server.socket, server_side=True)
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description="Hushyar personal document backend; no paid API or GPU")
    parser.add_argument("command", choices=("serve", "backup"))
    parser.add_argument("--db", default="./hushyar-personal.sqlite3", help="SQLite database path")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--cert", help="TLS certificate PEM with SAN for the phone's host/IP")
    parser.add_argument("--key", help="TLS private key PEM")
    parser.add_argument("--out", help="Output SQLite snapshot path (backup command)")
    args = parser.parse_args()
    os.umask(0o077)  # private database, SQLite journals and backup, even outside the README shell
    if args.command == "backup":
        if not args.out:
            parser.error("backup requires --out")
        if not Path(args.db).is_file():
            parser.error("backup source database does not exist; check --db (nothing was created)")
        DocumentStore(args.db).backup(args.out)
        print("Database snapshot written:", args.out)
        return
    token = os.environ.get("HUSHYAR_BACKEND_TOKEN", "")
    if len(token) < 32:
        parser.error("set HUSHYAR_BACKEND_TOKEN to a random value of at least 32 characters")
    service = Service(args.db, token)
    with create_server(service, args.host, args.port, args.cert, args.key) as server:
        print(f"Hushyar personal backend listening on {args.host}:{args.port} (TLS={'on' if args.cert else 'off, localhost only'})")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
