#!/usr/bin/env python3
"""
verify-knowledge.py — راستی‌آزمایی فرمت یگانهٔ حقیقت (DESIGN 2.1 §25.3)
CI-friendly: بدون اندروید، exit≠0 اگر هر قیدی بشکند.
"""
import pathlib, re, sys

root = pathlib.Path(__file__).resolve().parent.parent
errors = []

def assert_true(cond, msg):
    tag = "OK" if cond else "FAIL"
    print(f"{tag} {msg}")
    if not cond:
        errors.append(msg)

def read(rel): return (root/rel).read_text(encoding="utf-8", errors="ignore")

# 1. KnowledgeItem 15 فیلد
p = read("app/src/main/java/ir/aai/hushyar/domain/Knowledge.kt")
for f in ["val id:", "val topic:", "val question:", "val claim:", "val category:", "val attribute:", "val url:", "val domain:", "val confidence:", "val createdAt:", "val lastVerifiedAt:", "val ttlDays:", "val sourceCount:", "val layer:", "val tags:"]:
    assert_true(f in p, f"KnowledgeItem/{f.strip()}")

# 2. QwenRag
q = read("app/src/main/java/ir/aai/hushyar/domain/QwenRag.kt")
assert_true("TARGET_CHARS = 900" in q, "QwenRag TARGET 900 (~450 tok)")
assert_true("OVERLAP_CHARS = 140" in q, "QwenRag OVERLAP 140")
assert_true("MIN_CHARS = 200" in q, "QwenRag MIN 200")
assert_true("estimateTokens" in q and "2.2" in q, "QwenRag estimateTokens 2.2")
assert_true('category = "rag"' in q, 'QwenRag category rag')
assert_true("confidence = 0.68" in q, "QwenRag confidence 0.68 chunk")
assert_true("confidence = 0.58" in q, "QwenRag confidence 0.58 singleChunk")
assert_true("chunk:" in q and "tok:" in q, "QwenRag tags chunk/tok")
assert_true("fun chunk(" in q, "QwenRag.chunk()")
assert_true("fun toKnowledgeItems(" in q, "QwenRag.toKnowledgeItems()")
assert_true("fun singleChunk(" in q, "QwenRag.singleChunk()")

# 3. Data.kt DDL
d = read("app/src/main/java/ir/aai/hushyar/data/Data.kt")
for col in ["id TEXT PRIMARY KEY","topic TEXT","question TEXT","claim TEXT","category TEXT","attribute TEXT","url TEXT","domain TEXT","confidence REAL","created_at INTEGER","last_verified_at INTEGER","ttl_days INTEGER","source_count INTEGER","superseded_by TEXT","tags TEXT","layer TEXT"]:
    key = col.split()[0]
    assert_true(key.lower() in d.lower(), f"Data.kt col {key}")
assert_true("fun knowledgeUpsert(" in d, "Repos.knowledgeUpsert")
assert_true("fun knowledgeAnswerable(" in d, "Repos.knowledgeAnswerable")
assert_true("KnowledgeBank.merge" in d, "Repos uses KnowledgeBank.merge")
assert_true("KnowledgeBank.answerable" in d, "Repos uses KnowledgeBank.answerable")

# 4. KnowledgeBank methods
for fn in ["fun answerable","fun coverage","fun gaps","fun isStale","fun stamp","fun merge","fun decide","fun mentionsSubject","ASK_WORDS","sameWord"]:
    assert_true(fn in p, f"KnowledgeBank:{fn}")

# 5. IronKnowledge 18 families
ik = read("app/src/main/java/ir/aai/hushyar/domain/IronKnowledge.kt")
cat_block = ik[ik.find("val categories:"):ik.find("fun byName")]
assert_true(cat_block.count("IronCategory(") == 18, f"IronKnowledge families 18 (got {cat_block.count('IronCategory(')})")
assert_true('category = "iron"' in ik, "IronKnowledge category iron")
assert_true("ttlDays = 180" in ik, "IronKnowledge ttl 180")
assert_true("confidence = 0.92" in ik, "IronKnowledge confidence 0.92")
assert_true("fun toKnowledgeItems(" in ik, "IronKnowledge.toKnowledgeItems()")
assert_true("asset://iron_knowledge/" in ik, "IronKnowledge url explicit")

# 6. IronSeed
seed = read("app/src/main/java/ir/aai/hushyar/domain/IronSeed.kt")
assert_true('PREF_SEEDED' in seed, "IronSeed PREF_SEEDED")
assert_true('fun seed(' in seed, "IronSeed.seed")
assert_true('fun reseed(' in seed, "IronSeed.reseed")
assert_true('needsSeed' in seed, "IronSeed.needsSeed")
assert_true('knowledgeUpsert' in seed, "IronSeed -> knowledgeUpsert")

# 7. Search 30 engines inc gerdoo/zarebin
s = read("app/src/main/java/ir/aai/hushyar/domain/Search.kt")
ids = re.findall(r'EngineSpec\(\s*"([^"]+)"', s)
uniq = []
seen=set()
for i in ids:
    if i not in seen: seen.add(i); uniq.append(i)
assert_true(len(uniq) == 30, f"Engines.all 30 (got {len(uniq)}: {uniq})")
assert_true("gerdoo" in uniq, "Engines has gerdoo")
assert_true("zarebin" in uniq, "Engines has zarebin")
assert_true("gerdoo.me/search/?query={q}" in s, "gerdoo URL")
assert_true("zarebin.ir/search?q={q}" in s, "zarebin URL")
assert_true("zarebin.ir/gse/api/search" in s, "zarebin JSON fallback")
assert_true("JsonSerp.zarebin" in s, "JsonSerp.zarebin parser")
assert_true("SerpFormat.JSON" in s, "zarebin JSON format")

# 8. DeepStudy dual-write
ds = read("app/src/main/java/ir/aai/hushyar/domain/DeepStudy.kt")
assert_true("FactHarvester.harvest" in ds, "DeepStudy FactHarvester.harvest")
assert_true("QwenRag.toKnowledgeItems" in ds, "DeepStudy QwenRag.toKnowledgeItems")
assert_true("QwenRag.singleChunk" in ds, "DeepStudy QwenRag.singleChunk")
assert_true("class DeepStudy" in ds, "DeepStudy class")
assert_true("fun study(" in ds, "DeepStudy.study()")
assert_true("diverse(" in ds, "DeepStudy.diverse()")
assert_true("StudyPlanner" in ds, "StudyPlanner")
assert_true("StudyOutcome" in ds, "StudyOutcome with coverage/gaps/engines/failures")
assert_true("StudyBudget" in ds, "StudyBudget quick/balanced/deep")
assert_true("store(item)" in ds or "store(rag" in ds or "store(single" in ds, "DeepStudy store() dual-write")

if errors:
    print(f"\nFAILED — {len(errors)} check(s) red.", file=sys.stderr)
    sys.exit(1)
else:
    print(f"\nGREEN — all checks pass ({len(uniq)} engines, 18 families, 15 fields, chunk 900/140).")
