package com.bosideload.android

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads common metadata from an XML Info.plist.
 * Binary plist files are intentionally reported as unsupported for now.
 */
internal object IpaPlistReader {
    data class Metadata(
        val appName: String?,
        val bundleId: String?,
        val shortVersion: String?,
        val buildVersion: String?
    )

    fun read(bytes: ByteArray): Metadata? {
        if (bytes.isEmpty() || bytes.size > MAX_PLIST_BYTES) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            val root = document.documentElement ?: return null
            if (root.tagName != "plist") return null
            val dict = root.getElementsByTagName("dict").item(0) as? Element ?: return null
            val values = linkedMapOf<String, String>()
            val children = dict.childNodes
            var pendingKey: String? = null
            for (index in 0 until children.length) {
                val node = children.item(index)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                val element = node as Element
                if (element.tagName == "key") {
                    pendingKey = element.textContent
                } else {
                    val key = pendingKey
                    if (key != null && element.tagName == "string") {
                        values[key] = element.textContent.orEmpty()
                    }
                    pendingKey = null
                }
            }
            Metadata(
                appName = values["CFBundleDisplayName"]?.takeIf { it.isNotBlank() }
                    ?: values["CFBundleName"]?.takeIf { it.isNotBlank() },
                bundleId = values["CFBundleIdentifier"]?.takeIf { it.isNotBlank() },
                shortVersion = values["CFBundleShortVersionString"]?.takeIf { it.isNotBlank() },
                buildVersion = values["CFBundleVersion"]?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    private const val MAX_PLIST_BYTES = 1024 * 1024
}
