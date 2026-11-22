package com.sterbenden.clockedin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.sterbenden.clockedin.core.Formats
import com.sterbenden.clockedin.core.PaidFilter
import java.time.LocalDate
import java.time.ZoneId
import javax.swing.JComponent

/** Start/end editor. For the running session the end field is disabled. */
class SessionTimesDialog(
    project: Project?,
    title: String,
    private val zone: ZoneId,
    start: Long,
    end: Long?,
    private val running: Boolean,
) : DialogWrapper(project) {
    private val startField = JBTextField(Formats.dateTime(start, zone), 16)
    private val endField = JBTextField(end?.let { Formats.dateTime(it, zone) } ?: "", 16)

    var start: Long = start
        private set
    var end: Long? = end
        private set

    init {
        this.title = title
        if (running) {
            endField.isEnabled = false
            endField.text = "running"
        }
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Start:", startField)
        .addLabeledComponent("End:", endField)
        .addComponent(JBLabel("Format: yyyy-MM-dd HH:mm:ss (seconds optional)").apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL })
        .panel

    override fun getPreferredFocusedComponent(): JComponent = startField

    override fun doValidate(): ValidationInfo? {
        val s = Formats.parseDateTime(startField.text, zone) ?: return ValidationInfo("Not a valid date and time", startField)
        if (!running) {
            val e = Formats.parseDateTime(endField.text, zone) ?: return ValidationInfo("Not a valid date and time", endField)
            if (e <= s) return ValidationInfo("The end must be after the start", endField)
        }
        return null
    }

    override fun doOKAction() {
        start = Formats.parseDateTime(startField.text, zone)!!
        end = if (running) null else Formats.parseDateTime(endField.text, zone)
        super.doOKAction()
    }
}

/** Picks an inclusive date range and, when [askPaid] is set, which sessions to include by paid status. */
class DateRangeDialog(
    project: Project?,
    title: String,
    from: LocalDate,
    to: LocalDate,
    private val askPaid: Boolean = false,
) : DialogWrapper(project) {
    private val fromField = JBTextField(from.toString(), 10)
    private val toField = JBTextField(to.toString(), 10)
    private val paidBox = com.intellij.openapi.ui.ComboBox(PaidFilter.entries.toTypedArray())
    internal val allDatesBox = com.intellij.ui.components.JBCheckBox("All dates").apply {
        addActionListener {
            fromField.isEnabled = !isSelected
            toField.isEnabled = !isSelected
        }
    }

    /** True when every session should be included, whatever the dates. */
    val allDates: Boolean get() = allDatesBox.isSelected

    /** Which sessions to include. Always [PaidFilter.ALL] unless [askPaid] is set. */
    val paidFilter: PaidFilter get() = if (askPaid) paidBox.selectedItem as PaidFilter else PaidFilter.ALL

    lateinit var from: LocalDate
        private set
    lateinit var to: LocalDate
        private set

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("From:", fromField)
        .addLabeledComponent("To:", toField)
        .addComponent(allDatesBox)
        .apply { if (askPaid) addLabeledComponent("Sessions:", paidBox) }
        .addComponent(JBLabel("Format: yyyy-MM-dd, both days included").apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL })
        .panel

    override fun doValidate(): ValidationInfo? {
        if (allDatesBox.isSelected) return null
        val f = Formats.parseDate(fromField.text) ?: return ValidationInfo("Not a valid date", fromField)
        val t = Formats.parseDate(toField.text) ?: return ValidationInfo("Not a valid date", toField)
        if (t.isBefore(f)) return ValidationInfo("'To' must not be before 'From'", toField)
        return null
    }

    override fun doOKAction() {
        if (!allDatesBox.isSelected) {
            from = Formats.parseDate(fromField.text)!!
            to = Formats.parseDate(toField.text)!!
        }
        super.doOKAction()
    }

    /** The chosen range; with [allDates], from the first recorded session to today. */
    fun resolve(sessions: List<com.sterbenden.clockedin.core.Session>, zone: ZoneId, today: LocalDate): Pair<LocalDate, LocalDate> {
        if (!allDates) return from to to
        val first = sessions.minOfOrNull { it.start }
            ?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today
        return minOf(first, today) to today
    }
}
