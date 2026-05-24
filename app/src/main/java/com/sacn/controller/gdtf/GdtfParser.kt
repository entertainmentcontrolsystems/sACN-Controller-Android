@file:Suppress("SpellCheckingInspection")

package com.sacn.controller.gdtf

import android.content.Context
import android.net.Uri
import com.sacn.controller.model.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Parses GDTF 1.1/1.2 files (.gdtf = ZIP containing description.xml).
 *
 * Key XML structure:
 *   GDTF > FixtureType > DMXModes > DMXMode > DMXChannels > DMXChannel
 *         > LogicalChannel[@Attribute]
 *
 * Post-processing: adjacent 8-bit channels sharing the same attribute and
 * consecutive offsets are merged into a single 16-bit ChannelDef, handling
 * fixtures that list coarse + fine as separate DMXChannel elements.
 */
object GdtfParser {

    data class ParseResult(val profile: FixtureProfile?, val error: String? = null)

    fun parse(context: Context, uri: Uri, fileName: String): ParseResult = try {
        val stream = context.contentResolver.openInputStream(uri)
            ?: return ParseResult(null, "Cannot open file")
        parseStream(stream, fileName)
    } catch (e: Exception) {
        ParseResult(null, "Parse error: ${e.message}")
    }

    fun parseStream(stream: InputStream, fileName: String): ParseResult {
        ZipInputStream(stream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "description.xml") return parseXml(zis, fileName)
                entry = zis.nextEntry
            }
        }
        return ParseResult(null, "No description.xml found in fixture file")
    }

    // ── Pending-channel accumulator (replaces mutable state vars) ─────────────

    private data class PendingChannel(
        val offsets  : List<Int>,
        val geometry : String,
        val attr     : String = ""
    )

    // ── XML parser ────────────────────────────────────────────────────────────

    private fun parseXml(stream: InputStream, fileName: String): ParseResult {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, "UTF-8")

        var manufacturer = "Unknown"
        var fixtureName  = fileName.removeSuffix(".gdtf")
        val modes        = mutableListOf<DMXMode>()

        var inFixtureType  = false
        var inDmxModes     = false
        var modeName       = ""
        var modeChannels   = mutableListOf<ChannelDef>()
        var footprintMax   = 0
        var pending: PendingChannel? = null   // non-null while inside a DMXChannel element

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "FixtureType" -> {
                        inFixtureType = true
                        manufacturer  = parser.getAttributeValue(null, "Manufacturer") ?: "Unknown"
                        fixtureName   = parser.getAttributeValue(null, "LongName")
                            ?: parser.getAttributeValue(null, "Name")
                            ?: fixtureName
                    }
                    "DMXModes" -> if (inFixtureType) inDmxModes = true
                    "DMXMode"  -> if (inDmxModes) {
                        modeName     = parser.getAttributeValue(null, "Name") ?: "Mode"
                        modeChannels = mutableListOf()
                        footprintMax = 0
                    }
                    "DMXChannel" -> if (inDmxModes) {
                        val dmxBreak = parser.getAttributeValue(null, "DMXBreak") ?: "1"
                        val rawOff   = parser.getAttributeValue(null, "Offset") ?: ""
                        val geom     = parser.getAttributeValue(null, "Geometry") ?: ""
                        val offsets  = when {
                            rawOff.isBlank() || rawOff == "None" || dmxBreak == "Overwrite" -> null
                            else -> rawOff.split(",").mapNotNull { it.trim().toIntOrNull() }
                                .takeIf { it.isNotEmpty() }
                        }
                        pending = offsets?.let { PendingChannel(it, geom) }
                    }
                    "LogicalChannel" -> {
                        val cur  = pending
                        val attr = parser.getAttributeValue(null, "Attribute") ?: ""
                        if (cur != null && attr.isNotEmpty()) pending = cur.copy(attr = attr)
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "DMXChannel" -> {
                        val cur = pending
                        if (cur != null && cur.attr.isNotEmpty()) {
                            val coarse = cur.offsets[0]
                            val fine   = cur.offsets.getOrNull(1)
                            val info   = resolveAttribute(cur.attr)
                            modeChannels.add(
                                ChannelDef(
                                    name        = cur.attr,
                                    displayName = info.displayName,
                                    offset      = coarse,
                                    fineOffset  = fine,
                                    geometry    = cur.geometry,
                                    category    = info.category
                                )
                            )
                            footprintMax = maxOf(footprintMax, coarse, fine ?: 0)
                        }
                        pending = null
                    }
                    "DMXMode" -> if (inDmxModes && modeChannels.isNotEmpty()) {
                        val sorted = mergeCoarseFine(modeChannels)
                            .sortedWith(compareBy({ it.category.ordinal }, { it.offset }))
                        modes.add(DMXMode(modeName, sorted, footprintMax))
                    }
                    "DMXModes"    -> inDmxModes    = false
                    "FixtureType" -> inFixtureType = false
                }
            }
            event = parser.next()
        }

        if (modes.isEmpty()) return ParseResult(null, "No DMX modes found in fixture profile")

        return ParseResult(FixtureProfile(
            manufacturer = manufacturer.trim(),
            name         = fixtureName.trim(),
            modes        = modes,
            gdtfFileName = fileName
        ))
    }

    /**
     * Merge consecutive 8-bit channels that share the same GDTF attribute and
     * have adjacent offsets (fine = coarse + 1) into a single 16-bit ChannelDef.
     * Handles fixtures that list coarse + fine as separate DMXChannel elements.
     */
    private fun mergeCoarseFine(channels: List<ChannelDef>): List<ChannelDef> {
        val absorbed = mutableSetOf<Int>()
        val result   = mutableListOf<ChannelDef>()
        for (ch in channels) {
            if (ch.offset in absorbed) continue
            if (!ch.is16Bit) {
                val fineCandidate = channels.find { other ->
                    other !== ch &&
                    other.offset == ch.offset + 1 &&
                    other.name   == ch.name &&
                    !other.is16Bit
                }
                if (fineCandidate != null) {
                    result.add(ch.copy(fineOffset = fineCandidate.offset))
                    absorbed.add(fineCandidate.offset)
                    continue
                }
            }
            result.add(ch)
        }
        return result
    }
}
