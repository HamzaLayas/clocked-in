package com.sterbenden.clockedin.core

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A small Excel workbook writer with a fixed set of styles for a printable timesheet: titles,
 * header rows, bordered cells, totals, dates and durations. Each sheet prints on one page
 * width with page numbers in the footer.
 */
object Xlsx {
    /** Cell styles. The order matches `cellXfs` in [STYLES]. */
    enum class Style {
        DEFAULT, TITLE, LABEL, VALUE, HEADER, HEADER_RIGHT,
        CELL, CELL_DATE, CELL_SHORT_DATE, CELL_HOURS,
        TOTAL, TOTAL_HOURS, NOTE, VALUE_DATE, SECTION,
    }

    sealed interface Cell {
        val style: Style

        data class Text(val value: String, override val style: Style = Style.DEFAULT) : Cell

        /** A calendar date, stored as a real spreadsheet date. */
        data class Date(val date: LocalDate, override val style: Style) : Cell

        /** A duration in whole seconds, shown as `[h]:mm:ss` so totals past 24 hours read correctly. */
        data class Hours(val seconds: Long, override val style: Style) : Cell

        /** A formula (without `=`) whose result is a duration; [seconds] is its value until Excel recalculates. */
        data class HoursFormula(val formula: String, val seconds: Long, override val style: Style) : Cell
    }

    /**
     * [widths] are column widths in characters. An empty row leaves a blank line. Rows above
     * [freezeRows] stay visible while scrolling. [footer] is printed at the bottom left of each page.
     */
    data class Sheet(
        val name: String,
        val rows: List<List<Cell>>,
        val widths: List<Int> = emptyList(),
        val freezeRows: Int = 0,
        val footer: String = "",
    )

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

    /** A reference to [cell] on another sheet, for formulas, for example `'Week 47'!B12`. */
    fun ref(sheet: String, cell: String): String = "'" + sheetName(sheet).replace("'", "''") + "'!" + cell

    /** The cell address of a zero-based column and row, for example `B12`. */
    fun address(column: Int, row: Int): String = column(column) + (row + 1)

    private const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private const val ROOT_RELS = XML +
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="$REL_NS/officeDocument" Target="xl/workbook.xml"/>""" +
        """</Relationships>"""

    private const val ACCENT = "FF1F4E79"
    private const val ACCENT_LIGHT = "FFDDEBF7"
    private const val GRID = "FFBFBFBF"
    private const val MUTED = "FF595959"

    private fun xf(numFmt: Int = 0, font: Int = 0, fill: Int = 0, border: Int = 0, align: String? = null): String {
        val applies = buildString {
            if (numFmt != 0) append(""" applyNumberFormat="1"""")
            if (font != 0) append(""" applyFont="1"""")
            if (fill != 0) append(""" applyFill="1"""")
            if (border != 0) append(""" applyBorder="1"""")
            if (align != null) append(""" applyAlignment="1"""")
        }
        val alignment = align?.let { """<alignment horizontal="$it" vertical="center"/>""" }
        return """<xf numFmtId="$numFmt" fontId="$font" fillId="$fill" borderId="$border" xfId="0"$applies""" +
            (if (alignment != null) ">$alignment</xf>" else "/>")
    }

    // Fonts: 0 normal, 1 bold, 2 title, 3 header (bold white), 4 muted, 5 muted italic, 6 section.
    // Fills: 0 none, 1 gray125 (required), 2 accent, 3 light accent.
    // Borders: 0 none, 1 thin grid, 2 thin grid with an accent line on top (totals), 3 accent underline (sections).
    // Number formats: 164 durations, 165 long dates, 166 short dates.
    private val STYLES = XML +
        """<styleSheet xmlns="$MAIN_NS">""" +
        """<numFmts count="3">""" +
        """<numFmt numFmtId="164" formatCode="[h]:mm:ss"/>""" +
        """<numFmt numFmtId="165" formatCode="dddd, d mmmm yyyy"/>""" +
        """<numFmt numFmtId="166" formatCode="d mmm yyyy"/>""" +
        """</numFmts>""" +
        """<fonts count="7">""" +
        """<font><sz val="11"/><name val="Calibri"/><family val="2"/></font>""" +
        """<font><b/><sz val="11"/><name val="Calibri"/><family val="2"/></font>""" +
        """<font><b/><sz val="18"/><color rgb="$ACCENT"/><name val="Calibri"/><family val="2"/></font>""" +
        """<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/><family val="2"/></font>""" +
        """<font><sz val="10"/><color rgb="$MUTED"/><name val="Calibri"/><family val="2"/></font>""" +
        """<font><i/><sz val="9"/><color rgb="$MUTED"/><name val="Calibri"/><family val="2"/></font>""" +
        """<font><b/><sz val="12"/><color rgb="$ACCENT"/><name val="Calibri"/><family val="2"/></font>""" +
        """</fonts>""" +
        """<fills count="4">""" +
        """<fill><patternFill patternType="none"/></fill>""" +
        """<fill><patternFill patternType="gray125"/></fill>""" +
        """<fill><patternFill patternType="solid"><fgColor rgb="$ACCENT"/><bgColor indexed="64"/></patternFill></fill>""" +
        """<fill><patternFill patternType="solid"><fgColor rgb="$ACCENT_LIGHT"/><bgColor indexed="64"/></patternFill></fill>""" +
        """</fills>""" +
        """<borders count="4">""" +
        """<border><left/><right/><top/><bottom/><diagonal/></border>""" +
        """<border><left style="thin"><color rgb="$GRID"/></left><right style="thin"><color rgb="$GRID"/></right>""" +
        """<top style="thin"><color rgb="$GRID"/></top><bottom style="thin"><color rgb="$GRID"/></bottom><diagonal/></border>""" +
        """<border><left style="thin"><color rgb="$GRID"/></left><right style="thin"><color rgb="$GRID"/></right>""" +
        """<top style="medium"><color rgb="$ACCENT"/></top><bottom style="thin"><color rgb="$GRID"/></bottom><diagonal/></border>""" +
        """<border><left/><right/><top/><bottom style="medium"><color rgb="$ACCENT"/></bottom><diagonal/></border>""" +
        """</borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="${Style.entries.size}">""" +
        xf() + // DEFAULT
        xf(font = 2) + // TITLE
        xf(font = 4) + // LABEL
        xf(font = 1) + // VALUE
        xf(font = 3, fill = 2, border = 1, align = "left") + // HEADER
        xf(font = 3, fill = 2, border = 1, align = "right") + // HEADER_RIGHT
        xf(border = 1) + // CELL
        xf(numFmt = 165, border = 1, align = "left") + // CELL_DATE
        xf(numFmt = 166, border = 1, align = "left") + // CELL_SHORT_DATE
        xf(numFmt = 164, border = 1, align = "right") + // CELL_HOURS
        xf(font = 1, fill = 3, border = 2) + // TOTAL
        xf(numFmt = 164, font = 1, fill = 3, border = 2, align = "right") + // TOTAL_HOURS
        xf(font = 5) + // NOTE
        xf(numFmt = 166, font = 1, align = "left") + // VALUE_DATE
        xf(font = 6, border = 3) + // SECTION
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
        // Recalculate the total formulas when the file is opened.
        append("""</sheets><calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>""")
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
        append("""<worksheet xmlns="$MAIN_NS" xmlns:r="$REL_NS">""")
        append("""<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""")
        append("""<sheetViews><sheetView workbookViewId="0" showGridLines="0">""")
        if (sheet.freezeRows > 0) {
            val topLeft = address(0, sheet.freezeRows)
            append("""<pane ySplit="${sheet.freezeRows}" topLeftCell="$topLeft" activePane="bottomLeft" state="frozen"/>""")
            append("""<selection pane="bottomLeft" activeCell="$topLeft" sqref="$topLeft"/>""")
        }
        append("</sheetView></sheetViews>")
        append("""<sheetFormatPr defaultRowHeight="15"/>""")
        if (sheet.widths.isNotEmpty()) {
            append("<cols>")
            sheet.widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
            append("</cols>")
        }
        append("<sheetData>")
        sheet.rows.forEachIndexed { r, row ->
            if (row.isEmpty()) return@forEachIndexed
            val tall = row.any { it.style == Style.TITLE }
            append("""<row r="${r + 1}"${if (tall) """ ht="28" customHeight="1"""" else ""}>""")
            row.forEachIndexed { c, cell ->
                val ref = address(c, r)
                val s = cell.style.ordinal
                when (cell) {
                    is Cell.Text ->
                        if (cell.value.isEmpty()) {
                            append("""<c r="$ref" s="$s"/>""")
                        } else {
                            append("""<c r="$ref" s="$s" t="inlineStr"><is><t xml:space="preserve">${escape(cell.value)}</t></is></c>""")
                        }
                    is Cell.Date -> append("""<c r="$ref" s="$s"><v>${serial(cell.date)}</v></c>""")
                    is Cell.Hours -> append("""<c r="$ref" s="$s"><v>${dayFraction(cell.seconds)}</v></c>""")
                    is Cell.HoursFormula ->
                        append("""<c r="$ref" s="$s"><f>${escape(cell.formula)}</f><v>${dayFraction(cell.seconds)}</v></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        append("""<printOptions horizontalCentered="1"/>""")
        append("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")
        append("""<pageSetup orientation="portrait" fitToWidth="1" fitToHeight="0"/>""")
        val footer = buildString {
            if (sheet.footer.isNotBlank()) append("&L").append(sheet.footer.replace("&", "&&"))
            append("&RPage &P of &N")
        }
        append("""<headerFooter><oddFooter>${escape(footer)}</oddFooter></headerFooter>""")
        append("</worksheet>")
    }

    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    /** Spreadsheet dates count days from 30 December 1899. */
    private fun serial(date: LocalDate): Long = ChronoUnit.DAYS.between(EPOCH, date)

    /** Spreadsheet durations are fractions of a day. */
    private fun dayFraction(seconds: Long): String =
        BigDecimal.valueOf(seconds)
            .divide(BigDecimal.valueOf(86_400), 15, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()

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
