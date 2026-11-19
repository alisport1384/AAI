"""stdlib-only integration tests: real HTTP handler, real SQLite/FTS5, no internet."""
from contextlib import closing
import hashlib
import http.client
import json
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
from tempfile import TemporaryDirectory
import threading
import unittest

from selfhost.hushyar_backend import DocumentStore, RateLimiter, Service, create_server, SCHEMA

TOKEN = "test-token-keep-private-0123456789abcdef"
A = "chat-first"
B = "chat-second"


class BackendTests(unittest.TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.db = Path(self.tmp.name) / "personal.sqlite3"
        self.service = Service(self.db, TOKEN, rate_limit=200)
        self.server = create_server(self.service, port=0)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=3)
        self.tmp.cleanup()

    def call(self, method, path, body=None, token=TOKEN):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        conn.request(method, path, data, headers)
        res = conn.getresponse()
        raw = res.read()
        output = res.status, json.loads(raw), res.getheader("Cache-Control")
        conn.close()
        return output

    def add(self, thread=A, title="نبشی", text="نبشی ۴ فابریک با وزن ۹ کیلو، نرخ ۱۲۵۵۰۰ تومان در تاریخ ۱۴۰۴.", kind="text"):
        return self.call("POST", "/v1/documents", dict(thread_id=thread, title=title, text=text, kind=kind))

    def test_requires_auth_without_exposing_documents_or_token(self):
        status, body, cache = self.call("GET", "/health", token="wrong")
        self.assertEqual(401, status)
        self.assertEqual("UNAUTHORIZED", body["error"]["code"])
        self.assertNotIn(TOKEN, json.dumps(body))
        self.assertEqual("no-store", cache)
        self.assertEqual("ok", self.call("GET", "/health")[1]["status"])

    def test_index_search_verbatim_citations_and_same_thread_isolation(self):
        status, saved, _ = self.add()
        self.assertEqual(201, status)
        self.assertEqual(SCHEMA, saved["schema"])
        self.assertGreater(saved["chunks"], 0)
        self.assertEqual(200, self.add()[0])  # idempotent same file/title/thread
        query = {"thread_id": A, "question": "وزن نبشی ۴ فابریک چیست؟"}
        status, answer, _ = self.call("POST", "/v1/query", query)
        self.assertEqual(200, status)
        self.assertFalse(answer["refused"])
        self.assertEqual(len(answer["citations"]), len(answer["bullets"]))
        citation = answer["citations"][0]
        self.assertTrue(citation["ref"].startswith("D:" + saved["id"]))
        self.assertIsNone(citation["url"])
        self.assertIn("۹ کیلو", citation["quote"])
        self.assertIn(citation["quote"], answer["bullets"][0])
        with closing(self.service.store._connect()) as db:
            self.assertTrue(any(citation["quote"] in r[0] for r in db.execute(
                "SELECT body FROM chunks WHERE document_id=?", (saved["id"],))))
        self.assertEqual([], self.call("GET", "/v1/documents?thread_id=" + B)[1]["documents"])
        other = self.call("POST", "/v1/query", {"thread_id": B, "question": query["question"]})[1]
        self.assertTrue(other["refused"])
        self.assertEqual([], other["citations"])
        self.assertEqual([saved["id"]], [d["id"] for d in self.call(
            "GET", "/v1/documents?thread_id=" + A)[1]["documents"]])
        self.assertFalse(self.call("DELETE", "/v1/documents/" + saved["id"] + "?thread_id=" + B)[1]["deleted"])
        self.assertTrue(self.call("DELETE", "/v1/documents/" + saved["id"] + "?thread_id=" + A)[1]["deleted"])
        self.assertTrue(self.call("POST", "/v1/query", query)[1]["refused"])

    def test_purge_one_thread_removes_its_fts_rows_and_keeps_the_other(self):
        self.add(thread=A, title="اول")
        self.add(thread=B, title="دوم")
        result = self.call("DELETE", "/v1/threads/" + A + "/documents")
        self.assertEqual(1, result[1]["deleted_count"])
        self.assertEqual([], self.call("GET", "/v1/documents?thread_id=" + A)[1]["documents"])
        self.assertEqual(1, len(self.call("GET", "/v1/documents?thread_id=" + B)[1]["documents"]))
        self.assertTrue(self.call("POST", "/v1/query", {"thread_id": A, "question": "نبشی چیست؟"})[1]["refused"])

    def test_current_price_never_uses_a_stored_price_even_if_a_hit_exists(self):
        self.add()
        for question in ("قیمت نبشی ۴ امروز چنده؟", "قیمت روز نبشی ۴ چیست؟", "نرخ فعلی نبشی ۴؟"):
            result = self.call("POST", "/v1/query", {"thread_id": A, "question": question})[1]
            self.assertTrue(result["refused"], question)
            self.assertEqual("FRESHNESS_REQUIRED", result["reason"])
            self.assertEqual([], result["citations"])
            self.assertNotIn("۱۲۵۵۰۰", json.dumps(result, ensure_ascii=False))
        historic = self.call("POST", "/v1/query", {"thread_id": A, "question": "قیمتِ ثبت‌شده در ۱۴۰۴ چقدر بود؟"})[1]
        self.assertFalse(historic["refused"])  # clearly historical, quoted rather than claimed current

    def test_one_digit_product_size_cannot_reuse_a_different_size(self):
        self.add(text="نبشی4 فابریک ۹ کیلو با قیمت قدیمی ۱۲۵۵۰۰ تومان")
        wrong = self.call("POST", "/v1/query", {"thread_id": A, "question": "وزن نبشی۵ چقدر است؟"})[1]
        self.assertTrue(wrong["refused"])
        self.assertEqual([], wrong["citations"])
        correct = self.call("POST", "/v1/query", {"thread_id": A, "question": "وزن نبشی4 چقدر است؟"})[1]
        self.assertFalse(correct["refused"])

    def test_html_scripts_are_not_indexed_and_query_cannot_issue_sql(self):
        self.add(text="<html><script>ADMINISTRATOR SECRET</script><p>آزمایش معتبر و اطلاعات فنی سند</p></html>", kind="html")
        self.assertTrue(self.call("POST", "/v1/query", {"thread_id": A, "question": "ADMINISTRATOR SECRET"})[1]["refused"])
        self.assertFalse(self.call("POST", "/v1/query", {"thread_id": A, "question": "آزمایش معتبر"})[1]["refused"])
        self.call("POST", "/v1/query", {"thread_id": A, "question": 'x" OR 1=1; DROP TABLE documents; --'})
        self.assertEqual(1, len(self.call("GET", "/v1/documents?thread_id=" + A)[1]["documents"]))

    def test_invalid_input_and_body_limits_fail_closed(self):
        self.assertEqual("INVALID_THREAD", self.add(thread="../../evil")[1]["error"]["code"])
        self.assertEqual("UNSUPPORTED_FORMAT", self.add(kind="pdf")[1]["error"]["code"])
        self.assertEqual("DOCUMENT_TOO_LARGE", self.add(text="س" * 60_000)[1]["error"]["code"])
        self.assertEqual("INVALID_QUESTION", self.call("POST", "/v1/query", {"thread_id": A, "question": "?" * 500})[1]["error"]["code"])
        self.assertEqual(404, self.call("GET", "/unexpected")[0])

    def test_full_size_text_with_many_paragraphs_has_a_valid_receipt(self):
        # The server's 100 KB cap can still produce >200 overlapping chunks.
        text = ("a" * 349 + "\n") * 270
        self.assertLessEqual(len(text.encode("utf-8")), 100_000)
        status, result, _ = self.add(text=text)
        self.assertEqual(201, status)
        self.assertGreater(result["chunks"], 200)
        self.assertLessEqual(result["chunks"], 500)
        listing = self.call("GET", "/v1/documents?thread_id=" + A)[1]["documents"]
        self.assertEqual(result["chunks"], listing[0]["chunks"])

    def test_backup_is_consistent_and_metrics_contain_no_document_text(self):
        self.add(title="سند شخصی ویژه")
        out = Path(self.tmp.name) / "backup.sqlite3"
        self.service.store.backup(out)
        self.assertEqual(0, self.db.stat().st_mode & 0o077)
        self.assertEqual(0, out.stat().st_mode & 0o077)
        self.assertEqual(1, len(DocumentStore(out).list(A)))
        self.add(thread=B)
        self.service.store.backup(out)  # atomic replacement, not an obsolete snapshot
        self.assertEqual(2, len(DocumentStore(out).list(A) + DocumentStore(out).list(B)))
        self.assertEqual(0, out.stat().st_mode & 0o077)
        metrics = self.call("GET", "/metrics")[1]
        self.assertGreater(metrics["requests"], 0)
        self.assertNotIn("سند شخصی", json.dumps(metrics, ensure_ascii=False))

    def test_backup_command_refuses_a_missing_source_instead_of_creating_an_empty_backup(self):
        absent = Path(self.tmp.name) / "not-the-real-database.sqlite3"
        output = Path(self.tmp.name) / "should-not-exist.sqlite3"
        process = subprocess.run([sys.executable, "-m", "selfhost.hushyar_backend", "backup",
                                  "--db", str(absent), "--out", str(output)], capture_output=True, text=True)
        self.assertNotEqual(0, process.returncode)
        self.assertFalse(absent.exists())
        self.assertFalse(output.exists())

    @unittest.skipUnless(shutil.which("openssl"), "openssl is needed for this optional TLS test")
    def test_self_signed_https_serves_authenticated_health_and_has_a_stable_pin(self):
        cert = Path(self.tmp.name) / "server.pem"
        key = Path(self.tmp.name) / "server-key.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256",
                        "-days", "1", "-subj", "/CN=127.0.0.1",
                        "-addext", "subjectAltName=IP:127.0.0.1",
                        "-keyout", str(key), "-out", str(cert)],
                       check=True, capture_output=True)
        with create_server(self.service, port=0, cert=str(cert), key=str(key)) as secure:
            worker = threading.Thread(target=secure.serve_forever, daemon=True)
            worker.start()
            try:
                conn = http.client.HTTPSConnection("127.0.0.1", secure.server_port, timeout=3,
                    context=ssl._create_unverified_context())  # test client only; Android PIN+SAN is mandatory
                conn.request("GET", "/health", headers={"Authorization": "Bearer " + TOKEN})
                der = conn.sock.getpeercert(binary_form=True)
                response = conn.getresponse()
                self.assertEqual(200, response.status)
                self.assertEqual("ok", json.loads(response.read())["status"])
                self.assertEqual(64, len(hashlib.sha256(der).hexdigest()))
                conn.close()
            finally:
                secure.shutdown()
                worker.join(timeout=3)

    def test_non_loopback_requires_tls_and_rate_limiter_is_bounded(self):
        with self.assertRaises(ValueError):
            create_server(self.service, host="0.0.0.0", port=0)
        rate = RateLimiter(limit=2, window=60)
        self.assertTrue(rate.allow("user"))
        self.assertTrue(rate.allow("user"))
        self.assertFalse(rate.allow("user"))
        self.assertTrue(rate.allow("another"))


if __name__ == "__main__":
    unittest.main()
