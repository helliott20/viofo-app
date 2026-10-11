package io.github.helliott20.dashlink.util

/**
 * Tiny, forgiving XML reader for the simple documents dashcams return.
 *
 * Camera firmware frequently emits slightly malformed XML (missing prolog, stray
 * whitespace, unescaped ampersands in file names), so we deliberately avoid a strict
 * parser. Element order is preserved, which matters for the `<Cmd>/<Status>` pairs
 * returned by the "get current state" command.
 */
class XmlNode(val name: String) {
    val children = mutableListOf<XmlNode>()
    var text: String = ""
        internal set

    fun child(name: String): XmlNode? = children.firstOrNull { it.name.equals(name, ignoreCase = true) }
    fun childText(name: String): String? = child(name)?.text
    fun all(name: String): List<XmlNode> = children.filter { it.name.equals(name, ignoreCase = true) }

    /** Depth-first search for every descendant with the given name. */
    fun findAll(name: String): List<XmlNode> {
        val out = mutableListOf<XmlNode>()
        fun walk(n: XmlNode) {
            for (c in n.children) {
                if (c.name.equals(name, ignoreCase = true)) out += c
                walk(c)
            }
        }
        walk(this)
        return out
    }

    fun find(name: String): XmlNode? = findAll(name).firstOrNull()

    override fun toString(): String = "<$name>${if (children.isEmpty()) text else children.joinToString("")}</$name>"
}

object Xml {
    /** Parses [src] and returns a synthetic root whose children are the top level elements. */
    fun parse(src: String): XmlNode {
        val root = XmlNode("#root")
        val stack = ArrayDeque<XmlNode>().apply { addLast(root) }
        val text = StringBuilder()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c != '<') {
                text.append(c); i++; continue
            }
            // Comments, prolog, CDATA, doctype
            if (src.startsWith("<!--", i)) {
                val end = src.indexOf("-->", i + 4); i = if (end < 0) n else end + 3; continue
            }
            if (src.startsWith("<![CDATA[", i)) {
                val end = src.indexOf("]]>", i + 9)
                text.append(src, i + 9, if (end < 0) n else end)
                i = if (end < 0) n else end + 3; continue
            }
            if (src.startsWith("<?", i) || src.startsWith("<!", i)) {
                val end = src.indexOf('>', i); i = if (end < 0) n else end + 1; continue
            }
            val end = src.indexOf('>', i)
            if (end < 0) { text.append(src, i, n); break }
            var tag = src.substring(i + 1, end).trim()
            i = end + 1
            if (tag.isEmpty()) continue
            if (tag.startsWith("/")) {
                val name = tag.substring(1).trim()
                val top = stack.last()
                if (top !== root) top.text = unescape(text.toString().trim())
                text.clear()
                // Pop up to and including the matching element; tolerate mismatches.
                if (stack.any { it !== root && it.name.equals(name, true) }) {
                    while (stack.size > 1) {
                        val popped = stack.removeLast()
                        if (popped.name.equals(name, true)) break
                    }
                }
                continue
            }
            val selfClosing = tag.endsWith("/")
            if (selfClosing) tag = tag.dropLast(1).trim()
            val name = tag.split(' ', '\t', '\n', '\r').first()
            text.clear()
            val node = XmlNode(name)
            stack.last().children += node
            if (!selfClosing) stack.addLast(node)
        }
        return root
    }

    private fun unescape(s: String): String =
        if ('&' !in s) s else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
}
