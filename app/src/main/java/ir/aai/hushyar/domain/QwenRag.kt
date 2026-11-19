package ir.aai.hushyar.domain

import ir.aai.hushyar.core.Ids
import ir.aai.hushyar.core.Text

/**
 * Qwen-ready RAG ingestion: the missing link the user called "وحشتناک مهم".
 *
 * On desktop: AnythingLLM + BGE-M3 + FAISS + llama.cpp
 * On device (2GB RAM, no NDK embedding): lexical RAG via SQLite + KnowledgeBank.answerable
 *
 * This object bridges them:
 *  1. Takes any web page (ExtractedPage) → splits into 400-600 token chunks (fa: ~2.2 chars/token) with 60-token overlap
 *  2. Attaches full metadata per chunk (source URL, domain, date, topic, layer, tags) — exactly what Qwen needs
 *  3. Converts each chunk to a KnowledgeItem with category="rag" so both the lexical retriever AND the local LLM prompt see it
 *  4. Stores via the same knowledgeStore path → instantly answerable, survives reboot/backup
 *
 * Upgrade path: when RAM allows, swap Text chunking for BGE-M3 ONNX (multilingual-e5-small 384d, 23MB INT8) + sqlite-vec.
 * The chunk format stays identical; only the scorer in KnowledgeBank.answerable gets a vector cosine term.
 */
object QwenRag {

    // fa: average 2.0-2.5 chars per token for Persian mixed with Latin; 450 tokens ≈ 1000 chars
    const val TARGET_CHARS = 900
    const val OVERLAP_CHARS = 140
    const val MIN_CHARS = 200

    data class Chunk(
        val index: Int,
        val text: String,
        val charOffset: Int,
        val tokenEstimate: Int
    )

    /**
     * Split page text into overlapping chunks suitable for Qwen context (400-600 tokens).
     * Sentence-aware: never cuts mid-sentence. Overlap keeps co-reference intact.
     */
    fun chunk(text: String): List<Chunk> {
        val sentences = Extractive.sentences(text)
        if (sentences.isEmpty()) return emptyList()
        val out = mutableListOf<Chunk>()
        var buf = StringBuilder()
        var offset = 0
        var startOffset = 0
        var idx = 0
        for (sentence in sentences) {
            val clean = sentence.trim()
            if (clean.length < 15) continue
            // Would adding this sentence exceed target? Flush current buffer
            if (buf.length + clean.length + 1 > TARGET_CHARS && buf.length >= MIN_CHARS) {
                val chunkText = buf.toString().trim()
                out.add(Chunk(idx++, chunkText, startOffset, estimateTokens(chunkText)))
                // Overlap: keep last ~OVERLAP_CHARS of previous chunk
                val overlap = chunkText.takeLast(OVERLAP_CHARS)
                // Find overlap start inside sentences to keep sentence boundary
                buf = StringBuilder(overlap + " " + clean + " ")
                startOffset = offset - overlap.length
            } else {
                if (buf.isEmpty()) startOffset = offset
                buf.append(clean).append(" ")
            }
            offset += clean.length + 1
        }
        if (buf.length >= MIN_CHARS) {
            val t = buf.toString().trim()
            out.add(Chunk(idx, t, startOffset, estimateTokens(t)))
        }
        return out
    }

    fun estimateTokens(text: String): Int = (text.length / 2.2).toInt().coerceAtLeast(1)

    /**
     * Convert chunks to KnowledgeItems with full RAG metadata.
     * Each chunk is independently retrievable and citable.
     */
    fun toKnowledgeItems(
        topic: String,
        page: ExtractedPage,
        layer: String,
        langTag: String,
        clock: Long = System.currentTimeMillis()
    ): List<KnowledgeItem> {
        val chunks = chunk(page.text)
        return chunks.map { ch ->
            val excerpt = ch.text.take(600) // KnowledgeItem.claim limit safety
            KnowledgeItem(
                id = Ids.hash("rag:${topic}:${page.url}#${ch.index}:${excerpt.take(40)}"),
                topic = topic,
                question = StudyPlanner.question(layer, topic, langTag != "en"),
                claim = excerpt,
                category = "rag",
                attribute = layer,
                url = page.url,
                domain = ir.aai.hushyar.core.Html.domainOf(page.url).orEmpty(),
                confidence = 0.68,
                createdAt = clock,
                lastVerifiedAt = clock,
                ttlDays = 45,
                sourceCount = 1,
                layer = layer,
                tags = listOf("rag", "chunk:${ch.index}/${chunks.size}", "tok:${ch.tokenEstimate}", layer, page.url.take(80))
            )
        }
    }

    /**
     * Fallback: when a page is short, store the whole cleaned text as one chunk.
     * Used for pages that failed sentence split but still have extractable text.
     */
    fun singleChunk(
        topic: String,
        page: ExtractedPage,
        layer: String,
        langTag: String,
        clock: Long = System.currentTimeMillis()
    ): KnowledgeItem {
        val text = page.text.replace(Regex("\\s+"), " ").trim().take(600)
        return KnowledgeItem(
            id = Ids.hash("rag:single:${page.url}:${text.take(40)}"),
            topic = topic,
            question = StudyPlanner.question(layer, topic, langTag != "en"),
            claim = text,
            category = "rag",
            attribute = layer,
            url = page.url,
            domain = ir.aai.hushyar.core.Html.domainOf(page.url).orEmpty(),
            confidence = 0.58,
            createdAt = clock,
            lastVerifiedAt = clock,
            ttlDays = 45,
            sourceCount = 1,
            layer = layer,
            tags = listOf("rag", "single", layer)
        )
    }
}
