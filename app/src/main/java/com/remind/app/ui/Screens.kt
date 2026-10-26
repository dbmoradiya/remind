package com.remind.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.remind.app.data.*
import com.remind.app.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
private fun ScreenTitle(title: String, sub: String? = null) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 18.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        sub?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp))
}

private fun fmtHour(minutes: Int): String {
    val h = minutes / 60
    return if (h < 12) "${if (h == 0) 12 else h} AM" else "${if (h == 12) 12 else h - 12} PM"
}

@Composable
private fun PlainRow(
    title: String, trailing: String? = null, done: Boolean = false, accent: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f),
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (done) TextDecoration.LineThrough else null)
        trailing?.let {
            Text(it, fontSize = 13.sp, fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 24.dp))
}

@Composable
fun RemindNavHost(nav: NavHostController, vm: RemindUi) {
    NavHost(nav, startDestination = "today") {
        composable("today") { TodayScreen(nav, vm) }
        composable("reminders") { RemindersScreen(nav, vm) }
        composable("add?editId={editId}",
            arguments = listOf(navArgument("editId") { type = NavType.LongType; defaultValue = -1L })) {
            AddReminderScreen(nav, vm, it.arguments!!.getLong("editId"))
        }
        composable("plan") { ManagePlanScreen(nav, vm) }
        composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            DetailScreen(nav, vm, it.arguments!!.getLong("id"))
        }
        composable("memories") { MemoriesScreen(nav, vm) }
        composable("addMemory") { AddMemoryScreen(nav, vm) }
        composable("settings") { SettingsScreen(nav, vm) }
    }
}

@Composable
private fun BottomNav(nav: NavHostController, active: String) {
    val items = listOf("today" to "Today", "reminders" to "Reminders", "memories" to "Memories", "settings" to "Settings")
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        items.forEach { (route, label) ->
            NavigationBarItem(
                selected = active == route,
                onClick = { if (active != route) nav.navigate(route) { popUpTo("today"); launchSingleTop = true } },
                icon = {}, label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    }
}

@Composable
fun TodayScreen(nav: NavHostController, vm: RemindUi) {
    val planItems by vm.planItems.collectAsState()
    val checks by vm.planChecks.collectAsState()
    val water by vm.waterCount.collectAsState()
    val settings by vm.settings.collectAsState()
    val reminders by vm.reminders.collectAsState()
    val renewals by vm.renewals.collectAsState()
    val today = LocalDate.now()
    val waterTotal = WaterPlan.dailySlots(settings.waterStart, settings.waterEnd, settings.waterInterval)

    val upcoming = reminders.mapNotNull { r ->
        when (r.type) {
            ReminderType.EXPIRY -> vm.renewalCycleOf(r)?.let { c -> r to RenewalEngine.daysLeft(c, today) }
            else -> RepeatEngine.nextAfter(r, today.minusDays(1))?.let { d -> r to (d.toEpochDay() - today.toEpochDay()) }
        }
    }.filter { it.second >= -30 }.sortedBy { it.second }.take(3)

    Scaffold(bottomBar = { BottomNav(nav, "today") }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item { ScreenTitle("Today", today.format(DateTimeFormatter.ofPattern("EEE, d MMM"))) }
            item { SectionLabel("Plan") }
            items(planItems.filter { !it.isWater }, key = { "plan-" + it.id }) { item ->
                val done = checks[item.id] == true
                PlainRow(
                    title = "${if (done) "☑" else "☐"}  ${item.name}",
                    trailing = item.timeMinutes?.let { "%d:%02d %s".format(if (it / 60 % 12 == 0) 12 else it / 60 % 12, it % 60, if (it / 60 < 12) "AM" else "PM") },
                    done = done,
                    onClick = { vm.togglePlan(item, !done) }
                )
            }
            item { PlainRow(title = "Manage plan", trailing = "›", onClick = { nav.navigate("plan") }) }
            if (planItems.any { it.isWater }) {
                item {
                    PlainRow(title = "☐  Water", trailing = "$water / $waterTotal",
                        onClick = { vm.addWater() })
                    LinearProgressIndicator(
                        progress = { (water.toFloat() / waterTotal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(3.dp),
                    )
                }
            }
            if (upcoming.isNotEmpty()) {
                item { SectionLabel("Coming up") }
                items(upcoming, key = { "up-" + it.first.id }) { (r, days) ->
                    PlainRow(title = r.name,
                        trailing = if (days >= 0) "$days days" else "expired",
                        accent = true,
                        onClick = { nav.navigate("detail/${r.id}") })
                }
            }
        }
    }
}

@Composable
fun RemindersScreen(nav: NavHostController, vm: RemindUi) {
    val reminders by vm.reminders.collectAsState()
    val renewals by vm.renewals.collectAsState()
    val today = LocalDate.now()
    val grouped = reminders.groupBy { it.category }.toSortedMap()
    Scaffold(
        bottomBar = { BottomNav(nav, "reminders") },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("add") },
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface) { Text("Add") }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item { ScreenTitle("Reminders") }
            if (reminders.isEmpty()) {
                item { Text("No reminders yet. Tap Add.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
            }
            grouped.forEach { (category, list) ->
                item { SectionLabel(category) }
                items(list, key = { it.id }) { r ->
                    val trailing = when (r.type) {
                        ReminderType.EXPIRY -> vm.renewalCycleOf(r)?.let { "${RenewalEngine.daysLeft(it, today)} days" }
                        ReminderType.DATE -> r.dateEpochDay?.let { LocalDate.ofEpochDay(it).format(dateFmt) }
                        ReminderType.REPEAT -> RepeatEngine.nextAfter(r, today.minusDays(1))?.format(dateFmt)
                    }
                    PlainRow(title = r.name + if (r.alarm) " ♪" else "", trailing = trailing,
                        accent = r.type == ReminderType.EXPIRY,
                        onClick = { nav.navigate("detail/${r.id}") })
                }
            }
        }
    }
}

@Composable
fun AddReminderScreen(nav: NavHostController, vm: RemindUi, editId: Long = -1L) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    var type by remember { mutableStateOf(ReminderType.EXPIRY) }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Vehicle") }
    var dateText by remember { mutableStateOf("") }
    var validityChoice by remember { mutableStateOf("1 year") }
    var customDays by remember { mutableStateOf("") }
    var leadDays by remember { mutableStateOf("15") }
    var repeatUnit by remember { mutableStateOf(RepeatUnit.DAY) }
    var timeText by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf<String?>(null) }
    var contactUri by remember { mutableStateOf<String?>(null) }
    var alarm by remember { mutableStateOf(false) }
    var createdAt by remember { mutableStateOf(0L) }
    var snoozedUntilMs by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickContact = if (androidx.activity.compose.LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickContact()) { uri ->
            uri?.let {
                runCatching {
                    context.contentResolver.query(it, arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) contactName = c.getString(0)
                    }
                }
                contactUri = it.toString()
            }
        }
    } else null

    LaunchedEffect(editId) {
        if (editId > 0) {
            vm.reminder(editId).first()?.let { r ->
                type = r.type; name = r.name; category = r.category
                dateText = (if (r.type == ReminderType.EXPIRY) r.anchorEpochDay else r.dateEpochDay)
                    ?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("d/M/yyyy")) } ?: ""
                validityChoice = when {
                    r.validityDays != null -> "Custom"
                    r.validityMonths == 6 -> "6 months"
                    else -> "1 year"
                }
                customDays = r.validityDays?.toString() ?: ""
                leadDays = r.leadDays.toString()
                repeatUnit = r.repeatUnit ?: RepeatUnit.DAY
                timeText = r.timeMinutes?.let { "%d:%02d".format(it / 60, it % 60) } ?: ""
                amountText = r.amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: ""
                contactName = r.contactName; contactUri = r.contactUri
                alarm = r.alarm; createdAt = r.createdAt; snoozedUntilMs = r.snoozedUntilMs
            }
        } else {
            alarm = settings.alarmDefault
        }
        loaded = true
    }

    fun parseDate(): Long? = runCatching {
        LocalDate.parse(dateText.trim(), DateTimeFormatter.ofPattern("d/M/yyyy")).toEpochDay()
    }.getOrNull()

    fun parseTime(): Int? {
        if (timeText.isBlank()) return null
        return runCatching {
            val (h, m) = timeText.trim().split(":").map { it.toInt() }
            require(h in 0..23 && m in 0..59); h * 60 + m
        }.getOrNull()
    }

    Scaffold(bottomBar = { BottomNav(nav, "reminders") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ScreenTitle(if (editId > 0) "Edit reminder" else "New reminder")
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ReminderType.REPEAT to "Repeat", ReminderType.EXPIRY to "Expiry", ReminderType.DATE to "Date").forEach { (t, label) ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), singleLine = true)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Vehicle", "Bills", "Family", "Other").forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }
            Spacer(Modifier.height(8.dp))
            when (type) {
                ReminderType.EXPIRY -> {
                    OutlinedTextField(value = dateText, onValueChange = { dateText = it },
                        label = { Text("Purchased on (d/m/yyyy)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("6 months", "1 year", "Custom").forEach { v ->
                            FilterChip(selected = validityChoice == v, onClick = { validityChoice = v }, label = { Text(v) })
                        }
                    }
                    if (validityChoice == "Custom") {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = customDays, onValueChange = { customDays = it.filter(Char::isDigit) },
                            label = { Text("Valid for (days)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = leadDays, onValueChange = { leadDays = it.filter(Char::isDigit) },
                        label = { Text("Remind before (days)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                }
                ReminderType.REPEAT -> {
                    Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepeatUnit.entries.forEach { u ->
                            FilterChip(selected = repeatUnit == u, onClick = { repeatUnit = u },
                                label = { Text(u.name.lowercase().replaceFirstChar(Char::uppercase)) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = dateText, onValueChange = { dateText = it },
                        label = { Text("First date (d/m/yyyy)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                }
                ReminderType.DATE -> {
                    OutlinedTextField(value = dateText, onValueChange = { dateText = it },
                        label = { Text("Date (d/m/yyyy)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = timeText, onValueChange = { timeText = it },
                label = { Text("Reminder time (HH:mm)") }, singleLine = true,
                    supportingText = { Text("Optional - default 9 AM") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = amountText, onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("Amount (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            if (type == ReminderType.DATE) {
                PlainRow(title = if (contactName == null) "Link contact" else "Contact: $contactName",
                    trailing = "Optional", onClick = { pickContact?.launch(null) })
            }
            PlainRow(title = "Alarm ring", trailing = if (alarm) "On" else "Off", accent = alarm, onClick = { alarm = !alarm })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp), fontSize = 13.sp) }
            Button(onClick = {
                val date = parseDate()
                val timeInvalid = timeText.isNotBlank() && parseTime() == null
                val customInvalid = type == ReminderType.EXPIRY && validityChoice == "Custom" && (customDays.toIntOrNull() ?: 0) <= 0
                when {
                    !loaded -> error = "Loading..."
                    name.isBlank() -> error = "Name is required"
                    date == null -> error = "Enter the date as d/m/yyyy"
                    timeInvalid -> error = "Time must be HH:mm, e.g. 18:30"
                    customInvalid -> error = "Enter the number of days"
                    else -> {
                        val r = ReminderEntity(
                            id = if (editId > 0) editId else 0,
                            name = name.trim(), category = category, type = type,
                            repeatUnit = if (type == ReminderType.REPEAT) repeatUnit else null,
                            dateEpochDay = if (type != ReminderType.EXPIRY) date else null,
                            anchorEpochDay = if (type == ReminderType.EXPIRY) date else null,
                            validityMonths = if (type == ReminderType.EXPIRY && validityChoice != "Custom") (if (validityChoice == "1 year") 12 else 6) else null,
                            validityDays = if (type == ReminderType.EXPIRY && validityChoice == "Custom") customDays.toIntOrNull() else null,
                            leadDays = leadDays.toIntOrNull() ?: 7,
                            timeMinutes = parseTime(),
                            amount = amountText.toDoubleOrNull(),
                            contactName = contactName, contactUri = contactUri,
                            alarm = alarm, enabled = true,
                            createdAt = if (editId > 0) createdAt else LocalDate.now().toEpochDay(),
                            snoozedUntilMs = if (editId > 0) snoozedUntilMs else null
                        )
                        vm.saveReminder(r)
                        nav.popBackStack()
                    }
                }
            }, modifier = Modifier.fillMaxWidth().padding(24.dp), shape = RoundedCornerShape(12.dp)) { Text("Save") }
        }
    }
}

@Composable
fun DetailScreen(nav: NavHostController, vm: RemindUi, id: Long) {
    val reminder by vm.reminder(id).collectAsState(initial = null)
    val renewals by vm.renewalsFor(id).collectAsState(initial = emptyList())
    val today = LocalDate.now()
    val r = reminder ?: return
    var showHistory by remember { mutableStateOf(false) }
    var periodChoice by remember { mutableStateOf("1 year") }
    var leadText by remember { mutableStateOf(r.leadDays.toString()) }

    Scaffold(bottomBar = { BottomNav(nav, "reminders") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            ScreenTitle(r.name, r.category)
            if (r.type == ReminderType.EXPIRY) {
                val cycle = runCatching {
                    RenewalEngine.currentCycle(r, renewals)
                }.getOrNull()
                cycle?.let { c ->
                    val left = RenewalEngine.daysLeft(c, today)
                    Text(if (left >= 0) "$left" else "Expired", fontSize = 56.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp))
                    Text(if (left >= 0) "days left · expires ${LocalDate.ofEpochDay(c.expiryEpochDay).format(dateFmt)}"
                         else "${-left} days ago · was due ${LocalDate.ofEpochDay(c.expiryEpochDay).format(dateFmt)}",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("6 months", "1 year", "Custom").forEach { v ->
                            FilterChip(selected = periodChoice == v, onClick = { periodChoice = v }, label = { Text(v) })
                        }
                    }
                    var customRenewDays by remember { mutableStateOf("") }
                    if (periodChoice == "Custom") {
                        OutlinedTextField(value = customRenewDays, onValueChange = { customRenewDays = it.filter(Char::isDigit) },
                            label = { Text("Renew for (days)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
                    }
                    OutlinedTextField(value = leadText, onValueChange = { leadText = it.filter(Char::isDigit) },
                        label = { Text("Next reminder lead (days)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
                    var renewError by remember { mutableStateOf<String?>(null) }
                    Button(onClick = {
                        if (periodChoice == "Custom") {
                            val d = customRenewDays.toIntOrNull()
                            if (d == null || d <= 0) { renewError = "Enter the number of days"; return@Button }
                            vm.renewReminder(r, null, d, leadText.toIntOrNull())
                        } else {
                            vm.renewReminder(r, if (periodChoice == "1 year") 12 else 6, null, leadText.toIntOrNull())
                        }
                        renewError = null
                    }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), shape = RoundedCornerShape(12.dp)) { Text("Renew") }
                    renewError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp)) }
                    Spacer(Modifier.height(8.dp))
                    PlainRow(title = "Renewal history", trailing = if (showHistory) "−" else "›", onClick = { showHistory = !showHistory })
                    if (showHistory) renewals.forEach { rec ->
                        PlainRow(title = "Renewed ${LocalDate.ofEpochDay(rec.renewedAtEpochDay).format(dateFmt)}",
                            trailing = rec.periodMonths?.let { "$it mo" } ?: rec.periodDays?.let { "$it d" } ?: "")
                    }
                }
            } else {
                val upcoming = RepeatEngine.upcoming(r, today.minusDays(1), 3)
                SectionLabel("Upcoming")
                upcoming.forEach { d -> PlainRow(title = d.format(dateFmt)) }
            }
            r.amount?.let { PlainRow(title = "Amount", trailing = "₹" + (if (it % 1.0 == 0.0) it.toLong().toString() else it.toString())) }
            r.contactName?.let { PlainRow(title = "Contact", trailing = it) }
            PlainRow(title = "Edit", trailing = "›", onClick = { nav.navigate("add?editId=${r.id}") })
            PlainRow(title = "Alarm ring ♪", trailing = if (r.alarm) "On" else "Off",
                onClick = { vm.saveReminder(r.copy(alarm = !r.alarm)) })
            PlainRow(title = if (r.enabled) "Enabled" else "Paused", trailing = if (r.enabled) "On" else "Off",
                onClick = { vm.saveReminder(r.copy(enabled = !r.enabled)) })
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { vm.deleteReminder(r); nav.popBackStack() },
                modifier = Modifier.padding(horizontal = 24.dp)) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun MemoriesScreen(nav: NavHostController, vm: RemindUi) {
    val memories by vm.memories.collectAsState()
    val photoFailed by vm.photoCopyFailed.collectAsState()
    Scaffold(
        bottomBar = { BottomNav(nav, "memories") },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("addMemory") },
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface) { Text("Add") }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item { ScreenTitle("Memories") }
            if (photoFailed) {
                item { Text("The photo could not be copied, so the memory was saved without it. Pick the photo again.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp), fontSize = 13.sp) }
            }
            if (memories.isEmpty()) {
                item { Text("Save a memory - a purchase, a trip. Once a year, a simple notification brings it back.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp), fontSize = 14.sp) }
            }
            items(memories, key = { it.id }) { m ->
                val date = LocalDate.ofEpochDay(m.dateEpochDay)
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (m.photoUri != null) {
                        AsyncImage(model = m.photoUri, contentDescription = m.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(m.title, modifier = Modifier.weight(1f))
                    Text("${date.format(dateFmt)} · ${MemoryEngine.yearsAgo(date, LocalDate.now())}y",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            item {
                Text("Yearly reminder on · Photos saved on this phone",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

@Composable
fun AddMemoryScreen(nav: NavHostController, vm: RemindUi) {
    var title by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val registryOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current // non-null on device, null-safe in previews
    val pickPhoto = if (androidx.activity.compose.LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
        ) { uri -> if (uri != null) photoUri = uri.toString() }
    } else null
    Scaffold(bottomBar = { BottomNav(nav, "memories") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ScreenTitle("New memory")
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("What happened") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = dateText, onValueChange = { dateText = it }, label = { Text("Date (d/m/yyyy)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            PlainRow(title = if (photoUri == null) "Add photo" else "Photo added",
                trailing = if (photoUri == null) "Optional" else "✓",
                onClick = { pickPhoto?.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            if (photoUri != null) {
                AsyncImage(model = photoUri, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(horizontal = 24.dp).size(96.dp).clip(RoundedCornerShape(12.dp)))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp), fontSize = 13.sp) }
            Button(onClick = {
                val date = runCatching { LocalDate.parse(dateText.trim(), DateTimeFormatter.ofPattern("d/M/yyyy")) }.getOrNull()
                when {
                    title.isBlank() -> error = "Title is required"
                    date == null -> error = "Enter the date as d/m/yyyy"
                    else -> {
                        vm.saveMemory(MemoryEntity(title = title.trim(), dateEpochDay = date.toEpochDay(), note = note.ifBlank { null }, photoUri = photoUri))
                        nav.popBackStack()
                    }
                }
            }, modifier = Modifier.fillMaxWidth().padding(24.dp), shape = RoundedCornerShape(12.dp)) { Text("Save") }
        }
    }
}

@Composable
fun ManagePlanScreen(nav: NavHostController, vm: RemindUi) {
    val planItems by vm.planItems.collectAsState()
    var editing by remember { mutableStateOf<PlanItemEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(bottomBar = { BottomNav(nav, "today") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            ScreenTitle("Manage plan", "Daily habits and their reminder times")
            planItems.filter { !it.isWater }.forEach { item ->
                PlainRow(title = item.name,
                    trailing = item.timeMinutes?.let { "%d:%02d %s".format(if (it / 60 % 12 == 0) 12 else it / 60 % 12, it % 60, if (it / 60 < 12) "AM" else "PM") } ?: "No reminder",
                    onClick = { editing = item })
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth().padding(24.dp),
                shape = RoundedCornerShape(12.dp)) { Text("Add habit") }
            Text("Water reminders are managed in Settings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp))
        }
    }

    val target = editing ?: (if (showAdd) PlanItemEntity(name = "", sort = (planItems.maxOfOrNull { it.sort } ?: 0) + 1) else null)
    if (target != null) {
        var nameText by remember(target.id) { mutableStateOf(target.name) }
        var timeText by remember(target.id) { mutableStateOf(target.timeMinutes?.let { "%d:%02d".format(it / 60, it % 60) } ?: "") }
        var err by remember(target.id) { mutableStateOf<String?>(null) }
        fun parseHm(t: String): Int? = runCatching {
            val (h, m) = t.trim().split(":").map { it.toInt() }
            require(h in 0..23 && m in 0..59); h * 60 + m
        }.getOrNull()
        AlertDialog(
            onDismissRequest = { editing = null; showAdd = false },
            title = { Text(if (target.id == 0L) "Add habit" else "Edit habit") },
            text = {
                Column {
                    OutlinedTextField(value = nameText, onValueChange = { nameText = it },
                        label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = timeText, onValueChange = { timeText = it },
                        label = { Text("Reminder time (HH:mm, optional)") }, singleLine = true)
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = if (timeText.isBlank()) null else parseHm(timeText)
                    when {
                        nameText.isBlank() -> err = "Name is required"
                        timeText.isNotBlank() && t == null -> err = "Use HH:mm, e.g. 6:30"
                        else -> {
                            vm.savePlanItem(target.copy(name = nameText.trim(), timeMinutes = t))
                            editing = null; showAdd = false
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (target.id != 0L) {
                        TextButton(onClick = { vm.deletePlanItem(target); editing = null }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { editing = null; showAdd = false }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
fun SettingsScreen(nav: NavHostController, vm: RemindUi) {
    val s by vm.settings.collectAsState()
    Scaffold(bottomBar = { BottomNav(nav, "settings") }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            ScreenTitle("Settings")
            SectionLabel("Theme")
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { t ->
                    FilterChip(selected = s.theme == t, onClick = { vm.setSetting("theme", t.name) },
                        label = { Text(t.name.lowercase().replaceFirstChar(Char::uppercase)) })
                }
            }
            Spacer(Modifier.height(8.dp))
            PlainRow(title = "Water reminders", trailing = if (s.waterEnabled) "On" else "Off",
                onClick = { vm.setSetting("water_enabled", (!s.waterEnabled).toString()) })
            PlainRow(title = "Water interval", trailing = "${s.waterInterval} min",
                onClick = {
                    val next = if (s.waterInterval >= 60) 30 else s.waterInterval + 15
                    vm.setSetting("water_interval", next.toString())
                })
            var showQuiet by remember { mutableStateOf(false) }
            PlainRow(title = "Quiet hours", trailing = "${fmtHour(s.waterEnd)} - ${fmtHour(s.waterStart)}",
                onClick = { showQuiet = true })
            if (showQuiet) {
                var fromText by remember { mutableStateOf(String.format("%02d:%02d", s.waterEnd / 60, s.waterEnd % 60)) }
                var untilText by remember { mutableStateOf(String.format("%02d:%02d", s.waterStart / 60, s.waterStart % 60)) }
                var qError by remember { mutableStateOf<String?>(null) }
                fun parseHm(t: String): Int? = runCatching {
                    val (h, m) = t.trim().split(":").map { it.toInt() }
                    require(h in 0..23 && m in 0..59); h * 60 + m
                }.getOrNull()
                AlertDialog(
                    onDismissRequest = { showQuiet = false },
                    title = { Text("Quiet hours") },
                    text = {
                        Column {
                            Text("No water reminders between these times. They can span midnight.",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = fromText, onValueChange = { fromText = it },
                                label = { Text("Quiet from (HH:mm)") }, singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = untilText, onValueChange = { untilText = it },
                                label = { Text("Quiet until (HH:mm)") }, singleLine = true)
                            qError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val from = parseHm(fromText)
                            val until = parseHm(untilText)
                            when {
                                from == null || until == null -> qError = "Use HH:mm, e.g. 22:30"
                                from == until -> qError = "Times must differ"
                                WaterPlan.windowMinutes(until, from) < 60 -> qError = "Active window must be at least 1 hour"
                                else -> {
                                    vm.setSetting("water_end", from.toString())   // quiet starts when active ends
                                    vm.setSetting("water_start", until.toString()) // quiet ends when active starts
                                    showQuiet = false
                                }
                            }
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { showQuiet = false }) { Text("Cancel") } }
                )
            }
            PlainRow(title = "Alarm default", trailing = if (s.alarmDefault) "On" else "Off",
                onClick = { vm.setSetting("alarm_default", (!s.alarmDefault).toString()) })
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var transferMsg by remember { mutableStateOf<String?>(null) }
            PlainRow(title = "Background battery", trailing = "Open",
                onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                })
            val exportLauncher = if (androidx.activity.compose.LocalActivityResultRegistryOwner.current != null) {
                rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
                    uri?.let {
                        scope.launch {
                            val ok = runCatching {
                                context.contentResolver.openOutputStream(it)?.use { out -> out.write(vm.exportJson().toByteArray()) } != null
                            }.getOrDefault(false)
                            transferMsg = if (ok) "Exported" else "Export failed"
                        }
                    }
                }
            } else null
            val importLauncher = if (androidx.activity.compose.LocalActivityResultRegistryOwner.current != null) {
                rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let {
                        scope.launch {
                            val ok = runCatching {
                                context.contentResolver.openInputStream(it)?.use { inp -> inp.readBytes().decodeToString() }
                            }.getOrNull()
                            transferMsg = if (ok == null) "Import failed" else {
                                val res = vm.importJson(ok)
                when {
                    !res.ok -> "Import failed" + (res.error?.let { ": $it" } ?: "")
                    res.droppedPhotos > 0 -> "Imported - data restored, ${res.droppedPhotos} photo(s) could not be restored"
                    else -> "Imported - data restored"
                }
                            }
                        }
                    }
                }
            } else null
            PlainRow(title = "Export data", trailing = "Save file", onClick = { exportLauncher?.launch("remind-backup.json") })
            PlainRow(title = "Import data", trailing = "Open file", onClick = { importLauncher?.launch(arrayOf("application/json", "text/plain", "*/*")) })
            transferMsg?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
            val backup by vm.backupState.collectAsState()
            val googleLauncher = if (androidx.activity.compose.LocalActivityResultRegistryOwner.current != null) {
                rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
                    vm.onGoogleSignInResult(result.data)
                }
            } else null
            PlainRow(
                title = "Google backup",
                trailing = when {
                    backup.busy -> "Working..."
                    backup.signedInEmail == null -> "Connect"
                    else -> "Back up now"
                },
                onClick = {
                    if (!backup.busy) {
                        if (backup.signedInEmail == null) {
                            vm.googleSignInIntent()?.let { googleLauncher?.launch(it) }
                        } else vm.backupNow()
                    }
                })
            if (backup.signedInEmail != null) {
                PlainRow(title = "Restore from Google", trailing = if (backup.busy) "Working..." else "Restore",
                    onClick = { if (!backup.busy) vm.restoreFromGoogle() })
            }
            val backupMsg = backup.error ?: backup.message ?: backup.signedInEmail?.let { email ->
                "Connected as $email" + (backup.lastBackupMs?.let {
                    " - last backup " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it))
                } ?: "")
            }
            backupMsg?.let { Text(it, color = if (backup.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
        }
    }
}
