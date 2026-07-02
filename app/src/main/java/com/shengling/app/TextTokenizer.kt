package com.shengling.app

import android.util.Log
import com.shengling.app.data.TokenizerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 文本分词器。
 *
 * 为 VITS 解码模型将中英文文本转换为 token ID 序列。
 * 优先从 [tokenizer.json] 加载词表（兼容 HuggingFace tokenizers 的 `model.vocab` 结构，
 * 以及扁平的 `vocab` 对象或 token 列表）；若词表中未收录某字符，
 * 则回退到内置的确定性映射（ASCII / CJK 区段公式），保证同一字符始终映射到同一 ID。
 *
 * 输出序列始终以 BOS 开头、EOS 结尾。
 */
class TextTokenizer(private val config: TokenizerConfig) {

    private val tag = "TextTokenizer"

    /** 已加载词表：token 字符串 -> id。 */
    private val vocab: MutableMap<String, Int> = LinkedHashMap()

    /** 内置映射的起始 id（与词表区隔开）。 */
    private val fallbackBase = 20000

    /** fallback 区已分配的最大 id，用于避免冲突。 */
    private var fallbackCeiling = fallbackBase

    init {
        loadVocab()
    }

    /**
     * 对文本分词，返回带 BOS / EOS 的 token ID 数组。
     */
    fun tokenize(text: String): IntArray {
        if (text.isEmpty()) {
            return intArrayOf(config.bosId, config.eosId)
        }
        val ids = ArrayList<Int>(text.length + 2)
        ids.add(config.bosId)
        for (ch in text) {
            ids.add(tokenToId(ch.toString()))
        }
        ids.add(config.eosId)
        return ids.toIntArray()
    }

    /**
     * 将单个 token 字符串转为 id。
     * 优先查词表，再走内置映射，最后回退到 [unkId]。
     */
    fun tokenToId(token: String): Int {
        vocab[token]?.let { return it }

        // 仅处理单字符的 fallback（多字符 token 无法用公式推导）
        if (token.length == 1) {
            val code = token.codePointAt(0)
            val mapped = fallbackIdForCodepoint(code)
            if (mapped >= 0) {
                if (mapped >= fallbackCeiling) fallbackCeiling = mapped + 1
                return mapped
            }
        }
        return config.unkId
    }

    /** 当前词表大小（含 fallback 区上限）。 */
    fun vocabSize(): Int = maxOf(vocab.values.maxOrNull() ?: 0, fallbackCeiling) + 1

    // ===================== 词表加载 =====================

    private fun loadVocab() {
        val file = File(ModelManager.modelDir, config.file)
        if (!file.exists()) {
            Log.w(tag, "词表文件不存在: ${file.absolutePath}，将仅使用内置字符映射")
            return
        }
        try {
            val root = Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(file.readText()).jsonObject
            val loaded = parseVocab(root)
            if (loaded.isNotEmpty()) {
                vocab.putAll(loaded)
                Log.i(tag, "已加载词表，共 ${vocab.size} 个 token")
            } else {
                Log.w(tag, "词表文件未包含可识别的 vocab 字段，将仅使用内置字符映射")
            }
        } catch (t: Throwable) {
            Log.e(tag, "解析词表失败，回退到内置映射", t)
        }
    }

    /**
     * 兼容多种 tokenizer.json 结构：
     * 1. HuggingFace: { "model": { "vocab": { "<token>": id, ... } } }
     * 2. 扁平: { "vocab": { "<token>": id, ... } }
     * 3. 列表: { "model": { "vocab": ["<t0>", "<t1>", ...] } } 或 { "tokens": [...] }
     */
    private fun parseVocab(root: JsonObject): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()

        // 情况 1/2：对象形式
        val vocabObj = root["model"]?.let { it.jsonObject["vocab"] } ?: root["vocab"]
        if (vocabObj is JsonObject) {
            vocabObj.forEach { (token, value) ->
                val id = value.jsonPrimitive.intOrNull ?: value.jsonPrimitive.contentOrNull?.toIntOrNull()
                if (id != null) result[token] = id
            }
            return result
        }
        // 情况 3：列表形式
        val vocabArr = (vocabObj as? JsonArray)
            ?: root["model"]?.let { it.jsonObject["vocab"] } as? JsonArray
            ?: root["tokens"] as? JsonArray
        if (vocabArr != null) {
            vocabArr.forEachIndexed { idx, element ->
                val token = element.jsonPrimitive.contentOrNull
                if (token != null) result[token] = idx
            }
        }
        return result
    }

    // ===================== 内置字符映射 =====================

    /**
     * 对 ASCII 可打印字符与中日韩统一表意文字使用确定性公式生成 id，
     * 保证可复现且无需依赖外部词表。
     *
     *  - ASCII 可打印 (U+0020..U+007E)：id = fallbackBase + (code - 0x20)
     *  - CJK 基本区 (U+4E00..U+9FFF)：id = fallbackBase + 128 + (code - 0x4E00)
     *  - 其他：返回 -1（使用 unk）
     */
    private fun fallbackIdForCodepoint(code: Int): Int {
        return when (code) {
            in 0x0020..0x007E -> fallbackBase + (code - 0x0020)
            in 0x4E00..0x9FFF -> fallbackBase + 128 + (code - 0x4E00)
            in 0x3000..0x303F -> fallbackBase + 128 + 0x9FFF + (code - 0x3000) // 中文标点
            in 0xFF00..0xFFEF -> fallbackBase + 128 + 0x9FFF + 64 + (code - 0xFF00) // 全角符号
            else -> -1
        }
    }
}
