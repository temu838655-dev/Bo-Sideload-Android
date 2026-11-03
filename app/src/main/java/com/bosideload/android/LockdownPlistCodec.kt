package com.bosideload.android

import org.w3c.dom.Element
import org.xml.sax.InputSource
import com.dd.plist.PropertyListParser
import java.time.Instant
import java.util.Date
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Bounded XML and binary property-list decoder plus XML encoder for Lockdown messages.
 *
 * Supported values: dictionary, array, string, integer, real, boolean, data and date.
 * Binary input is decoded with dd-plist after the same byte-size cap, then normalized
 * with depth and node limits before it is exposed to the protocol layer.
 */
internal object LockdownPlistCodec {
    const val DEFAULT_MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_DEPTH = 64
    private const val MAX_NODES = 10_000

    fun encode(value: Map<String, Any?>, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray {
        require(maxBytes > 0) { "invalid plist size limit" }
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
            append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
            append("<plist version=\"1.0\">")
            appendValue(value, 0)
            append("</plist>")
        }
        val bytes = xml.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxBytes) { "Lockdown plist exceeds configured limit" }
        return bytes
    }

    fun decode(bytes: ByteArray, maxBytes: Int = DEFAULT_MAX_BYTES): Map<String, Any?> {
        require(maxBytes > 0) { "invalid plist size limit" }
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "invalid Lockdown plist size" }
        if (bytes.size >= BINARY_PLIST_MAGIC.size && bytes.copyOfRange(0, BINARY_PLIST_MAGIC.size).contentEquals(BINARY_PLIST_MAGIC)) {
            val parsed = PropertyListParser.parse(bytes).toJavaObject()
            val normalized = normalizeBinaryValue(parsed, 0, intArrayOf(0))
            @Suppress("UNCHECKED_CAST")
            return normalized as? Map<String, Any?> ?: throw IllegalArgumentException("Lockdown plist root must be a dictionary")
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(java.io.StringReader("")) } }
        val document = builder.parse(InputSource(ByteArrayInputStream(bytes)))
        val root = document.documentElement
        require(root.tagName == "plist") { "root element is not plist" }
        val values = root.childElements()
        require(values.size == 1) { "plist must contain exactly one root value" }
        val counter = intArrayOf(0)
        val decoded = readValue(values.single(), 0, counter)
        @Suppress("UNCHECKED_CAST")
        return decoded as? Map<String, Any?> ?: throw IllegalArgumentException("Lockdown plist root must be a dictionary")
    }

    private fun StringBuilder.appendValue(value: Any?, depth: Int) {
        require(depth <= MAX_DEPTH) { "plist nesting is too deep" }
        val out = this
        when (value) {
            is Map<*, *> -> {
                out.append("<dict>")
                value.forEach { (key, item) ->
                    val keyString = key as? String
                        ?: throw IllegalArgumentException("plist dictionary keys must be strings")
                    out.append("<key>")
                    out.appendEscaped(keyString)
                    out.append("</key>")
                    out.appendValue(item, depth + 1)
                }
                out.append("</dict>")
            }
            is List<*> -> {
                out.append("<array>")
                value.forEach { item -> out.appendValue(item, depth + 1) }
                out.append("</array>")
            }
            is String -> {
                out.append("<string>")
                out.appendEscaped(value)
                out.append("</string>")
            }
            is Boolean -> out.append(if (value) "<true/>" else "<false/>")
            is Byte, is Short, is Int, is Long ->
                out.append("<integer>").append(value.toString()).append("</integer>")
            is Float, is Double -> {
                val number = (value as Number).toDouble()
                require(number.isFinite()) { "plist real must be finite" }
                out.append("<real>").append(number.toString()).append("</real>")
            }
            is ByteArray -> out.append("<data>").append(Base64.getEncoder().encodeToString(value)).append("</data>")
            is Date -> out.append("<date>").append(Instant.ofEpochMilli(value.time).toString()).append("</date>")
            null -> throw IllegalArgumentException("null is not a supported plist value")
            else -> throw IllegalArgumentException("unsupported plist value type: ${value.javaClass.name}")
        }
    }

    private fun StringBuilder.appendEscaped(value: String) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }

    private fun readValue(element: Element, depth: Int, counter: IntArray): Any {
        require(depth <= MAX_DEPTH) { "plist nesting is too deep" }
        counter[0]++
        require(counter[0] <= MAX_NODES) { "plist contains too many values" }
        return when (element.tagName) {
            "dict" -> {
                val children = element.childElements()
                require(children.size % 2 == 0) { "plist dictionary has an unmatched key" }
                val result = linkedMapOf<String, Any?>()
                var index = 0
                while (index < children.size) {
                    val keyElement = children[index]
                    require(keyElement.tagName == "key") { "plist dictionary entry must start with key" }
                    val key = keyElement.textContent
                    require(!result.containsKey(key)) { "duplicate plist dictionary key" }
                    result[key] = readValue(children[index + 1], depth + 1, counter)
                    index += 2
                }
                result
            }
            "array" -> element.childElements().map { readValue(it, depth + 1, counter) }
            "string", "key" -> element.textContent
            "integer" -> element.textContent.trim().toLong()
            "real" -> element.textContent.trim().toDouble().also {
                require(it.isFinite()) { "plist real must be finite" }
            }
            "true" -> true.also { require(element.textContent.isBlank()) }
            "false" -> false.also { require(element.textContent.isBlank()) }
            "data" -> Base64.getMimeDecoder().decode(element.textContent)
            "date" -> Date.from(Instant.parse(element.textContent.trim()))
            else -> throw IllegalArgumentException("unsupported plist element: ${element.tagName}")
        }
    }

    private fun normalizeBinaryValue(value: Any?, depth: Int, counter: IntArray): Any? {
        require(depth <= MAX_DEPTH) { "plist nesting is too deep" }
        counter[0]++
        require(counter[0] <= MAX_NODES) { "plist contains too many values" }
        return when (value) {
            is Map<*, *> -> {
                val result = linkedMapOf<String, Any?>()
                value.forEach { (key, item) ->
                    val name = key as? String ?: throw IllegalArgumentException("plist dictionary keys must be strings")
                    require(!result.containsKey(name)) { "duplicate plist dictionary key" }
                    result[name] = normalizeBinaryValue(item, depth + 1, counter)
                }
                result
            }
            is Array<*> -> value.map { normalizeBinaryValue(it, depth + 1, counter) }
            is List<*> -> value.map { normalizeBinaryValue(it, depth + 1, counter) }
            is String, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is ByteArray, is Date -> value
            null -> throw IllegalArgumentException("null is not a supported plist value")
            else -> throw IllegalArgumentException("unsupported binary plist value type: " + value.javaClass.name)
        }
    }

    private val BINARY_PLIST_MAGIC = "bplist00".toByteArray(StandardCharsets.US_ASCII)

    private fun Element.childElements(): List<Element> {
        val result = ArrayList<Element>()
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node is Element) result += node
            else if (node.nodeType == org.w3c.dom.Node.TEXT_NODE ||
                node.nodeType == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                require(node.textContent.isBlank()) { "unexpected text between plist values" }
            }
        }
        return result
    }
}
