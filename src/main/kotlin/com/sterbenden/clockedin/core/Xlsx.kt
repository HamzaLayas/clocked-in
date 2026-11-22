package com.sterbenden.clockedin.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A minimal Excel workbook writer: text and duration cells, bold rows, one or more sheets. */
object Xlsx {
    sealed interface Cell {
        val bold: Boolean

        data class Text(val value: String, override val bold: Boolean = false) : Cell

        /** A duration in whole seconds, shown as `[h]:mm:ss` so totals past 24 hours read correctly. */
        data class Duration(val seconds: Long, override val bold: Boolean = false) : Cell
    }

    /** [widths] are column widths in characters. An empty row leaves a blank line. */
    data class Sheet(val name: String, val rows: List<List<Cell>>, val widths: List<Int> = emptyList())

    fun write(sheets: List<Sheet>): ByteArray {
        require(sheets.isNotEmpty()) { "A workbook needs at least one sheet." }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes(sheets.size))
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", workbook(sheets))
            put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, sheet -> put("xl/worksheets/sheet${i + 1}.xml", worksheet(sheet)) }
        }
        return out.toByteArray()
    }

    /** Excel's limits for sheet names: at most 31 characters, none of `[]:*?/\`. */
    fun sheetName(name: String): String = name.replace(Regex("""[\[\]:*?/\\]"""), "-").take(31).ifBlank { "Sheet" }

    private const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private const val ROOT_RELS = XML +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="$REL_NS/officeDocument" Target="xl/workbook.xml"/>""" +
        """</Relationships>"""

    // Style indexes: 0 text, 1 bold text, 2 duration, 3 bold duration.
    private const val STYLES = XML +
        """<styleSheet xmlns="$MAIN_NS">""" +
        """<numFmts count="1"><numFmt numFmtId="164" formatCode="[h]:mm:ss"/></numFmts>""" +
        """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
        """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
        """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="4">""" +
        """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
        """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
        """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
        """<xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>""" +
        """</cellXfs>""" +
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
        """</styleSheet>"""

    private fun contentTypes(sheetCount: Int) = buildString {
        append(XML)
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..sheetCount) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun workbook(sheets: List<Sheet>) = buildString {
        append(XML)
        append("""<workbook xmlns="$MAIN_NS" xmlns:r="$REL_NS"><sheets>""")
        sheets.forEachIndexed { i, sheet ->
            append("""<sheet name="${escape(sheetName(sheet.name))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int) = buildString {
        append(XML)
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..sheetCount) {
            append("""<Relationship Id="rId$i" Type="$REL_NS/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${sheetCount + 1}" Type="$REL_NS/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun worksheet(sheet: Sheet) = buildString {
        append(XML)
        append("""<worksheet xmlns="$MAIN_NS">""")
        if (sheet.widths.isNotEmpty()) {
            append("<cols>")
            sheet.widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
            append("</cols>")
        }
        append("<sheetData>")
        sheet.rows.forEachIndexed { r, row ->
            if (row.isEmpty()) return@forEachIndexed
            append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, cell ->
                val ref = column(c) + (r + 1)
                when (cell) {
                    is Cell.Text -> append("""<c r="$ref" t="inlineStr"${style(cell, 1)}><is><t xml:space="preserve">${escape(cell.value)}</t></is></c>""")
                    // Spreadsheet durations are fractions of a day.
                    is Cell.Duration -> append("""<c r="$ref" s="${if (cell.bold) 3 else 2}"><v>${dayFraction(cell.seconds)}</v></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun dayFraction(seconds: Long): String =
        java.math.BigDecimal.valueOf(seconds)
            .divide(java.math.BigDecimal.valueOf(86_400), 15, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()

    private fun style(cell: Cell, boldStyle: Int) = if (cell.bold) """ s="$boldStyle"""" else ""

    private fun column(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun escape(text: String): String = buildString {
        for (ch in text) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch < ' ' && ch != '\t' && ch != '\n' -> Unit // not allowed in XML
                else -> append(ch)
            }
        }
    }
}
