package dev.local.physicalmemory.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A calendar-only editing page. Selection stays local until the user confirms. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpiryDatePickerPage(value: String,itemLabel: String,onDismiss: ()->Unit,onConfirm: (String)->Unit) {
    val existing=remember(value) { runCatching { LocalDate.parse(value) }.getOrNull() }
    // Material's calendar represents date-only values at UTC midnight, not the device timezone.
    val selectedMillis=existing?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
    val initialMonth=existing ?: LocalDate.now()
    val picker=rememberDatePickerState(initialSelectedDateMillis=selectedMillis,
        initialDisplayedMonthMillis=initialMonth.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        yearRange=minOf(1900,initialMonth.year)..maxOf(2100,initialMonth.year))
    val selected=picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString() }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(modifier=Modifier.fillMaxSize().systemBarsPadding().testTag("expiry-date-page"),
                contentWindowInsets=WindowInsets(0,0,0,0),topBar={
                    TopAppBar(title={Text("选择日期")},navigationIcon={
                        TextButton(onDismiss,Modifier.testTag("cancel-expiry-date")) { Text("‹ 返回") }
                    })
                },bottomBar={
                    Row(Modifier.fillMaxWidth().padding(20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        OutlinedButton({onConfirm("")},Modifier.weight(1f).testTag("clear-expiry-date")) { Text("清空日期") }
                        Button({selected?.let(onConfirm)},Modifier.weight(1f).testTag("confirm-expiry-date"),enabled=selected!=null) { Text("确认日期") }
                    }
                }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=12.dp),
                    horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(itemLabel,Modifier.padding(8.dp),style=MaterialTheme.typography.titleMedium)
                    Text(selected ?: "请选择日期",Modifier.testTag("selected-expiry-date"),style=MaterialTheme.typography.headlineSmall)
                    // Material's seven fixed-width day cells clip at the OPPO's large display/font settings.
                    if(LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.3f) {
                        LargeTextDateList(picker)
                    } else {
                        DatePicker(picker,Modifier.widthIn(max=520.dp).testTag("expiry-calendar"),title=null,headline=null,showModeToggle=false)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LargeTextDateList(picker: DatePickerState) {
    val locale=LocalConfiguration.current.locales[0]
    val displayed=Instant.ofEpochMilli(picker.displayedMonthMillis).atOffset(ZoneOffset.UTC).toLocalDate()
    val month=YearMonth.from(displayed)
    var yearsOpen by remember {mutableStateOf(false)}
    var monthsOpen by remember {mutableStateOf(false)}
    fun showMonth(value: YearMonth) {picker.displayedMonthMillis=value.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()}
    Column(Modifier.fillMaxWidth().testTag("expiry-calendar"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton({yearsOpen=true},Modifier.fillMaxWidth().testTag("expiry-year")) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {Text("年份",style=MaterialTheme.typography.labelMedium);Text("${month.year} ▾")}
                }
                DropdownMenu(yearsOpen,{yearsOpen=false},Modifier.heightIn(max=320.dp)) {
                    // Keep the displayed year near the start; all supported years remain available.
                    val years=(month.year..picker.yearRange.last).toList()+(picker.yearRange.first until month.year).toList()
                    years.forEach {year->DropdownMenuItem(text={Text("$year 年")},onClick={showMonth(YearMonth.of(year,month.month));yearsOpen=false})}
                }
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton({monthsOpen=true},Modifier.fillMaxWidth().testTag("expiry-month")) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {Text("月份",style=MaterialTheme.typography.labelMedium);Text("${month.monthValue} 月 ▾")}
                }
                DropdownMenu(monthsOpen,{monthsOpen=false},Modifier.heightIn(max=320.dp)) {
                    (1..12).forEach {value->DropdownMenuItem(text={Text("$value 月")},onClick={showMonth(YearMonth.of(month.year,value));monthsOpen=false})}
                }
            }
        }
        (1..month.lengthOfMonth()).forEach {day ->
            val date=month.atDay(day)
            val millis=date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            OutlinedCard(onClick={picker.selectedDateMillis=millis},modifier=Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=picker.selectedDateMillis==millis,onClick=null)
                    Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)),
                        modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
