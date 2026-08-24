package com.kyant.amdl.api

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

fun ttmlToLrc(xml: String): String {
    val p = Xml.newPullParser()
    p.setInput(StringReader(xml))

    val out = StringBuilder()
    val text = StringBuilder()

    var time: String? = null
    var skipDepth = 0

    while (p.eventType != XmlPullParser.END_DOCUMENT) {
        when (p.eventType) {
            XmlPullParser.START_TAG -> {
                when (p.name) {
                    "p" -> {
                        time = p.getAttributeValue(null, "begin")
                        text.clear()
                    }

                    "span" -> {
                        if (skipDepth > 0 || isBgSpan(p)) {
                            skipDepth++
                        }
                    }
                }
            }

            XmlPullParser.TEXT -> {
                if (skipDepth == 0 && time != null) {
                    text.append(p.text)
                }
            }

            XmlPullParser.END_TAG -> {
                when (p.name) {
                    "span" -> {
                        if (skipDepth > 0) {
                            skipDepth--
                        }
                    }

                    "p" -> {
                        time?.let {
                            out.append("[${it.toLrcTime()}]")
                                .append(text.toString().trim())
                                .append('\n')
                        }
                        time = null
                    }
                }
            }
        }
        p.next()
    }

    return out.toString()
}

private fun isBgSpan(p: XmlPullParser): Boolean {
    for (i in 0 until p.attributeCount) {
        if (p.getAttributeName(i) == "role" && p.getAttributeValue(i) == "x-bg") {
            return true
        }
    }
    return false
}

private fun String.toLrcTime(): String {
    val value = trim()

    val seconds = if (':' in value) {
        val parts = value.split(':')
        parts.fold(0.0) { acc, part ->
            acc * 60 + part.toDouble()
        }
    } else {
        value.toDouble()
    }

    val min = (seconds / 60).toInt()
    val sec = seconds % 60

    return "%02d:%05.2f".format(min, sec)
}
