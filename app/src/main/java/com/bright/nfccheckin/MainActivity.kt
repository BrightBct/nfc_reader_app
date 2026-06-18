package com.bright.nfccheckin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var store: LocalStore
    private var nfcAdapter: NfcAdapter? = null
    private var uiState by mutableStateOf(AppUiState())
    private val rosterPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importRoster)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = LocalStore(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val savedRoster = store.readRoster()

        uiState = uiState.copy(
            nfcAvailable = nfcAdapter != null,
            students = store.readStudents(),
            roster = savedRoster,
            selectedSection = savedRoster.firstOrNull()?.section.orEmpty(),
            checkIns = store.readCheckIns().takeLast(20).reversed(),
            status = when {
                nfcAdapter == null -> "NFC is not available on this device."
                nfcAdapter?.isEnabled == false -> "NFC is turned off in Android settings."
                else -> "Ready for card."
            },
        )

        setContent {
            NfcCheckInTheme {
                AppScreen(
                    state = uiState,
                    onStudentIdChange = { uiState = uiState.copy(studentIdInput = it) },
                    onStudentNameChange = { uiState = uiState.copy(studentNameInput = it) },
                    onSaveStudent = ::saveStudentAndCheckIn,
                    onImportRoster = {
                        rosterPicker.launch(arrayOf("*/*"))
                    },
                    onSectionSelected = { section ->
                        uiState = uiState.copy(
                            selectedSection = section,
                            rosterSearch = "",
                            status = "Group $section selected. Ready for card.",
                        )
                    },
                    onRosterSearchChange = { uiState = uiState.copy(rosterSearch = it) },
                    onRosterStudentSelected = ::linkRosterStudentAndCheckIn,
                    onClearScan = { uiState = uiState.copy(lastScan = null, status = "Ready for card.") },
                    onExport = ::exportCsvFiles,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNfcStatus()
        enableReaderMode()
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return

        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_BARCODE

        adapter.enableReaderMode(
            this,
            { tag -> handleTag(tag) },
            flags,
            Bundle(),
        )
    }

    private fun refreshNfcStatus() {
        val adapter = nfcAdapter
        uiState = uiState.copy(
            nfcAvailable = adapter != null,
            status = when {
                adapter == null -> "NFC is not available on this device."
                !adapter.isEnabled -> "NFC is turned off in Android settings."
                uiState.status == "Starting." ||
                    uiState.status == "NFC is turned off in Android settings." -> "Ready for card."
                else -> uiState.status
            },
        )
    }

    private fun handleTag(tag: Tag) {
        val scan = NfcInspector.inspect(tag)
        store.appendScan(scan)

        runOnUiThread {
            val student = uiState.students.firstOrNull { it.cardUid == scan.cardUid }
            if (scan.cardUid.isBlank()) {
                uiState = uiState.copy(
                    lastScan = scan,
                    status = "Card detected, but Android did not expose a UID.",
                )
                return@runOnUiThread
            }

            if (student == null) {
                uiState = uiState.copy(
                    lastScan = scan,
                    status = if (uiState.roster.any { it.section == uiState.selectedSection }) {
                        "Unregistered card. Select a person from group ${uiState.selectedSection}."
                    } else {
                        "Unregistered card. Enter person details to link this UID."
                    },
                    studentIdInput = "",
                    studentNameInput = "",
                )
                return@runOnUiThread
            }

            if (uiState.selectedSection.isNotBlank() &&
                student.section.isNotBlank() &&
                student.section != uiState.selectedSection
            ) {
                uiState = uiState.copy(
                    lastScan = scan,
                    status = "${student.name} belongs to group ${student.section}. Select that group first.",
                )
                return@runOnUiThread
            }

            val checkInResult = createCheckIn(student)
            uiState = uiState.copy(
                lastScan = scan,
                checkIns = store.readCheckIns().takeLast(20).reversed(),
                status = checkInResult,
            )
        }
    }

    private fun saveStudentAndCheckIn() {
        val scan = uiState.lastScan
        val cardUid = scan?.cardUid.orEmpty()
        val studentId = uiState.studentIdInput.trim()
        val studentName = uiState.studentNameInput.trim()

        when {
            cardUid.isBlank() -> {
                uiState = uiState.copy(status = "Scan a card before saving a person.")
                return
            }
            studentId.isBlank() -> {
                uiState = uiState.copy(status = "ID is required.")
                return
            }
            studentName.isBlank() -> {
                uiState = uiState.copy(status = "Name is required.")
                return
            }
        }

        val student = Student(
            cardUid = cardUid,
            studentId = studentId,
            name = studentName,
            section = uiState.selectedSection,
            createdAt = nowText(),
        )
        val updatedStudents = (uiState.students.filterNot { it.cardUid == cardUid } + student)
            .sortedWith(compareBy<Student> { it.studentId }.thenBy { it.name })

        store.saveStudents(updatedStudents)
        val checkInResult = createCheckIn(student)

        uiState = uiState.copy(
            students = updatedStudents,
            checkIns = store.readCheckIns().takeLast(20).reversed(),
            studentIdInput = "",
            studentNameInput = "",
            status = checkInResult,
        )
    }

    private fun linkRosterStudentAndCheckIn(rosterStudent: RosterStudent) {
        val cardUid = uiState.lastScan?.cardUid.orEmpty()
        if (cardUid.isBlank()) {
            uiState = uiState.copy(status = "Scan a card before selecting a person.")
            return
        }

        val existingCard = uiState.students.firstOrNull {
            it.studentId == rosterStudent.studentId && it.cardUid != cardUid
        }
        if (existingCard != null) {
            uiState = uiState.copy(
                status = "${rosterStudent.name} is already linked to a different card.",
            )
            return
        }

        val student = Student(
            cardUid = cardUid,
            studentId = rosterStudent.studentId,
            name = rosterStudent.name,
            section = rosterStudent.section,
            createdAt = nowText(),
        )
        val updatedStudents = (uiState.students.filterNot { it.cardUid == cardUid } + student)
            .sortedWith(compareBy<Student> { it.section }.thenBy { it.studentId })

        store.saveStudents(updatedStudents)
        val checkInResult = createCheckIn(student)
        uiState = uiState.copy(
            students = updatedStudents,
            checkIns = store.readCheckIns().takeLast(20).reversed(),
            rosterSearch = "",
            status = checkInResult,
        )
    }

    private fun importRoster(uri: Uri) {
        val result = runCatching {
            contentResolver.openInputStream(uri)?.use(RosterCsv::read)
                ?: error("Could not open the selected file.")
        }

        result.onSuccess { roster ->
            store.saveRoster(roster)
            val sections = roster.map { it.section }.distinct().sorted()
            val selected = uiState.selectedSection.takeIf { it in sections }
                ?: sections.firstOrNull().orEmpty()
            uiState = uiState.copy(
                roster = roster,
                selectedSection = selected,
                rosterSearch = "",
                status = "Imported ${roster.size} people. Group $selected is selected.",
            )
        }.onFailure { error ->
            uiState = uiState.copy(
                status = "CSV import failed: ${error.message ?: "invalid CSV"}",
            )
        }
    }

    private fun createCheckIn(student: Student): String {
        if (store.hasCheckInToday(student.cardUid)) {
            return "${student.name} already checked in today."
        }

        store.appendCheckIn(
            CheckInRecord(
                timestamp = nowText(),
                cardUid = student.cardUid,
                studentId = student.studentId,
                studentName = student.name,
                result = "checked_in",
            ),
        )
        return "Checked in ${student.name}."
    }

    private fun exportCsvFiles() {
        val exportedFiles = store.exportCsvCopies()
        if (exportedFiles.isEmpty()) {
            Toast.makeText(this, "No CSV files to export yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val uris = ArrayList<Uri>()
        exportedFiles.forEach { file ->
            uris += FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file,
            )
        }

        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Export CSV"))
    }
}

data class AppUiState(
    val nfcAvailable: Boolean = false,
    val status: String = "Starting.",
    val lastScan: NfcScan? = null,
    val students: List<Student> = emptyList(),
    val roster: List<RosterStudent> = emptyList(),
    val checkIns: List<CheckInRecord> = emptyList(),
    val selectedSection: String = "",
    val rosterSearch: String = "",
    val studentIdInput: String = "",
    val studentNameInput: String = "",
)

data class Student(
    val cardUid: String,
    val studentId: String,
    val name: String,
    val section: String,
    val createdAt: String,
)

data class RosterStudent(
    val studentId: String,
    val name: String,
    val nickname: String,
    val section: String,
)

data class CheckInRecord(
    val timestamp: String,
    val cardUid: String,
    val studentId: String,
    val studentName: String,
    val result: String,
)

data class NfcScan(
    val timestamp: String,
    val cardUid: String,
    val technologies: List<String>,
    val ndefRecords: List<String>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    state: AppUiState,
    onStudentIdChange: (String) -> Unit,
    onStudentNameChange: (String) -> Unit,
    onSaveStudent: () -> Unit,
    onImportRoster: () -> Unit,
    onSectionSelected: (String) -> Unit,
    onRosterSearchChange: (String) -> Unit,
    onRosterStudentSelected: (RosterStudent) -> Unit,
    onClearScan: () -> Unit,
    onExport: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "NFC Check-In",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusPanel(state)

            RosterPanel(
                roster = state.roster,
                selectedSection = state.selectedSection,
                onImportRoster = onImportRoster,
                onSectionSelected = onSectionSelected,
            )

            state.lastScan?.let { scan ->
                ScanPanel(scan = scan, onClearScan = onClearScan)
            }

            if (state.lastScan?.cardUid?.isNotBlank() == true &&
                state.students.none { it.cardUid == state.lastScan.cardUid }
            ) {
                val sectionRoster = state.roster.filter { it.section == state.selectedSection }
                if (sectionRoster.isNotEmpty()) {
                    RosterRegistrationPanel(
                        students = sectionRoster,
                        linkedStudentIds = state.students.map { it.studentId }.toSet(),
                        search = state.rosterSearch,
                        onSearchChange = onRosterSearchChange,
                        onStudentSelected = onRosterStudentSelected,
                    )
                } else {
                    RegisterPanel(
                        studentId = state.studentIdInput,
                        studentName = state.studentNameInput,
                        onStudentIdChange = onStudentIdChange,
                        onStudentNameChange = onStudentNameChange,
                        onSaveStudent = onSaveStudent,
                    )
                }
            }

            SummaryPanel(
                rosterCount = state.roster.count { it.section == state.selectedSection },
                linkedCount = state.students.count {
                    state.selectedSection.isBlank() || it.section == state.selectedSection
                },
                checkIns = state.checkIns.filter { checkIn ->
                    state.selectedSection.isBlank() ||
                        state.students.any {
                            it.studentId == checkIn.studentId &&
                                it.section == state.selectedSection
                        }
                },
                onExport = onExport,
            )
        }
    }
}

@Composable
private fun StatusPanel(state: AppUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (state.nfcAvailable) Color(0xFFE9F4EF) else Color(0xFFFFEDEA),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = state.status,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (state.nfcAvailable) Color(0xFF174B3A) else Color(0xFF7A241C),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Group: ${state.selectedSection.ifBlank { "Not selected" }} • Recent check-ins: ${state.checkIns.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RosterPanel(
    roster: List<RosterStudent>,
    selectedSection: String,
    onImportRoster: () -> Unit,
    onSectionSelected: (String) -> Unit,
) {
    val sections = roster.map { it.section }.distinct().sorted()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        text = "People List",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (roster.isEmpty()) {
                            "No CSV imported"
                        } else {
                            "${roster.size} people imported"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onImportRoster,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(if (roster.isEmpty()) "Import CSV" else "Replace CSV")
                }
            }
            if (sections.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    sections.forEach { section ->
                        FilterChip(
                            selected = section == selectedSection,
                            onClick = { onSectionSelected(section) },
                            label = { Text(section) },
                            shape = RoundedCornerShape(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RosterRegistrationPanel(
    students: List<RosterStudent>,
    linkedStudentIds: Set<String>,
    search: String,
    onSearchChange: (String) -> Unit,
    onStudentSelected: (RosterStudent) -> Unit,
) {
    val query = search.trim()
    val matches = students.filter { student ->
        query.isBlank() ||
            student.studentId.contains(query, ignoreCase = true) ||
            student.name.contains(query, ignoreCase = true) ||
            student.nickname.contains(query, ignoreCase = true)
    }.take(8)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E6)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Link Card to Person",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF5A4212),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = search,
                onValueChange = onSearchChange,
                label = { Text("Search ID, name, or nickname") },
                singleLine = true,
            )
            matches.forEach { student ->
                val linked = student.studentId in linkedStudentIds
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !linked,
                    onClick = { onStudentSelected(student) },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            text = student.name,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = buildString {
                                append(student.studentId)
                                if (student.nickname.isNotBlank()) append(" • ${student.nickname}")
                                if (linked) append(" • Already linked")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (matches.isEmpty()) {
                Text(
                    text = "No matching person found in this group.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanPanel(scan: NfcScan, onClearScan: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "Last Card",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = onClearScan,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Clear")
                }
            }

            LabelValue(label = "UID", value = scan.cardUid.ifBlank { "Not exposed" })
            LabelValue(label = "Time", value = scan.timestamp)

            Text(
                text = "Technologies",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                scan.technologies.forEach { tech ->
                    FilterChip(
                        selected = false,
                        onClick = {},
                        label = { Text(tech) },
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }

            if (scan.ndefRecords.isNotEmpty()) {
                Text(
                    text = "NDEF",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                scan.ndefRecords.forEach { record ->
                    Text(text = record, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    text = "No readable NDEF records.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RegisterPanel(
    studentId: String,
    studentName: String,
    onStudentIdChange: (String) -> Unit,
    onStudentNameChange: (String) -> Unit,
    onSaveStudent: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E6)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Register Person",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF5A4212),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = studentId,
                onValueChange = onStudentIdChange,
                label = { Text("ID") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = studentName,
                onValueChange = onStudentNameChange,
                label = { Text("Name") },
                singleLine = true,
            )
            Button(
                onClick = onSaveStudent,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Save Person")
            }
        }
    }
}

@Composable
private fun SummaryPanel(
    rosterCount: Int,
    linkedCount: Int,
    checkIns: List<CheckInRecord>,
    onExport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        text = "Today",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "$rosterCount people • $linkedCount linked cards",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onExport,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("Export CSV")
                }
            }

            if (checkIns.isEmpty()) {
                Text(
                    text = "No check-ins yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                checkIns.forEach { checkIn ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF3F1EC),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                text = checkIn.studentName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${checkIn.studentId} • ${checkIn.timestamp}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NfcCheckInTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF245C73),
            onPrimary = Color.White,
            secondary = Color(0xFF6B5B95),
            tertiary = Color(0xFF3F7D5A),
            background = Color(0xFFFAFAF7),
            surface = Color.White,
        ),
        content = content,
    )
}

object NfcInspector {
    fun inspect(tag: Tag): NfcScan {
        val ndefRecords = readNdefRecords(tag)
        return NfcScan(
            timestamp = nowText(),
            cardUid = tag.id?.toHex().orEmpty(),
            technologies = tag.techList.map { it.substringAfterLast('.') }.distinct().sorted(),
            ndefRecords = ndefRecords,
        )
    }

    private fun readNdefRecords(tag: Tag): List<String> {
        val ndef = Ndef.get(tag) ?: return emptyList()
        return runCatching {
            ndef.connect()
            val message = ndef.cachedNdefMessage ?: ndef.ndefMessage
            message?.records?.map { it.toReadableText() }.orEmpty()
        }.getOrElse { error ->
            listOf("NDEF read error: ${error.message ?: error::class.java.simpleName}")
        }.also {
            runCatching { ndef.close() }
        }
    }
}

object RosterCsv {
    fun read(input: InputStream): List<RosterStudent> {
        val lines = input.bufferedReader(StandardCharsets.UTF_8).readLines()
            .filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "The CSV is empty." }

        val headers = parseCsvLine(lines.first()).map { it.normalizeHeader() }
        val studentIdIndex = headers.findHeader(
            "id",
            "person id",
            "student id",
            "employee id",
            "staff id",
            "member id",
            "user id",
            "code",
            "number",
        )
        val nameIndex = headers.findHeader(
            "name",
            "full name",
            "person name",
            "student name",
            "employee name",
            "staff name",
            "name en",
            "name th",
        )
        val alternateNameIndex = headers.findHeader("name th", "name en")
        val nicknameIndex = headers.findHeader(
            "nickname",
            "preferred name",
            "display name",
            "nickname en",
            "nickname th",
        )
        val groupIndex = headers.findHeader(
            "group",
            "section",
            "department",
            "team",
            "class",
            "room",
            "unit",
            "division",
        )

        require(studentIdIndex >= 0) {
            "Missing ID column. Use a header such as ID, Student ID, or Employee ID."
        }
        require(nameIndex >= 0) {
            "Missing name column. Use a header such as Name or Full Name."
        }

        return lines.drop(1).mapNotNull { line ->
            val values = parseCsvLine(line)
            val studentId = values.valueAt(studentIdIndex)
            if (studentId.isBlank()) {
                null
            } else {
                val name = values.valueAt(nameIndex)
                    .ifBlank { values.valueAt(alternateNameIndex) }
                    .ifBlank { studentId }
                val nickname = values.valueAt(nicknameIndex)
                val section = values.valueAt(groupIndex).ifBlank { "All" }
                RosterStudent(
                    studentId = studentId,
                    name = name,
                    nickname = nickname,
                    section = section,
                )
            }
        }.distinctBy { it.studentId }
            .sortedWith(compareBy<RosterStudent> { it.section }.thenBy { it.studentId })
            .also { require(it.isNotEmpty()) { "No people were found in the CSV." } }
    }
}

class LocalStore(private val context: Context) {
    private val studentsFile = File(context.filesDir, "students.json")
    private val rosterFile = File(context.filesDir, "roster.json")
    private val checkInsFile = File(context.filesDir, "checkins.csv")
    private val scansFile = File(context.filesDir, "scans.csv")

    @Synchronized
    fun readStudents(): List<Student> {
        if (!studentsFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(studentsFile.readText())
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Student(
                    cardUid = item.getString("cardUid"),
                    studentId = item.getString("studentId"),
                    name = item.getString("name"),
                    section = item.optString("section"),
                    createdAt = item.getString("createdAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveStudents(students: List<Student>) {
        val array = JSONArray()
        students.forEach { student ->
            array.put(
                JSONObject()
                    .put("cardUid", student.cardUid)
                    .put("studentId", student.studentId)
                    .put("name", student.name)
                    .put("section", student.section)
                    .put("createdAt", student.createdAt),
            )
        }
        studentsFile.writeText(array.toString(2))
        writeStudentsCsv(students)
    }

    @Synchronized
    fun readRoster(): List<RosterStudent> {
        if (!rosterFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(rosterFile.readText())
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                RosterStudent(
                    studentId = item.getString("studentId"),
                    name = item.getString("name"),
                    nickname = item.optString("nickname"),
                    section = item.getString("section"),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveRoster(roster: List<RosterStudent>) {
        val array = JSONArray()
        roster.forEach { student ->
            array.put(
                JSONObject()
                    .put("studentId", student.studentId)
                    .put("name", student.name)
                    .put("nickname", student.nickname)
                    .put("section", student.section),
            )
        }
        rosterFile.writeText(array.toString(2))
    }

    @Synchronized
    fun appendScan(scan: NfcScan) {
        appendCsv(
            file = scansFile,
            header = listOf("timestamp", "card_uid", "technologies", "ndef_records"),
            row = listOf(
                scan.timestamp,
                scan.cardUid,
                scan.technologies.joinToString("|"),
                scan.ndefRecords.joinToString("|"),
            ),
        )
    }

    @Synchronized
    fun appendCheckIn(record: CheckInRecord) {
        appendCsv(
            file = checkInsFile,
            header = listOf("timestamp", "card_uid", "person_id", "person_name", "result"),
            row = listOf(
                record.timestamp,
                record.cardUid,
                record.studentId,
                record.studentName,
                record.result,
            ),
        )
    }

    @Synchronized
    fun readCheckIns(): List<CheckInRecord> {
        if (!checkInsFile.exists()) return emptyList()
        return checkInsFile.readLines()
            .drop(1)
            .mapNotNull { line ->
                val values = parseCsvLine(line)
                if (values.size < 5) {
                    null
                } else {
                    CheckInRecord(
                        timestamp = values[0],
                        cardUid = values[1],
                        studentId = values[2],
                        studentName = values[3],
                        result = values[4],
                    )
                }
            }
    }

    @Synchronized
    fun hasCheckInToday(cardUid: String): Boolean {
        val today = LocalDate.now().toString()
        return readCheckIns().any {
            it.cardUid == cardUid && it.result == "checked_in" && it.timestamp.startsWith(today)
        }
    }

    @Synchronized
    fun exportCsvCopies(): List<File> {
        val exportDir = File(context.cacheDir, "exports")
        exportDir.mkdirs()

        if (studentsFile.exists()) {
            writeStudentsCsv(readStudents())
        }

        return listOf(
            File(context.filesDir, "people.csv"),
            checkInsFile,
            scansFile,
        ).filter { it.exists() }
            .map { source ->
                val copy = File(exportDir, source.name)
                source.copyTo(copy, overwrite = true)
                copy
            }
    }

    private fun writeStudentsCsv(students: List<Student>) {
        val studentsCsv = File(context.filesDir, "people.csv")
        val lines = buildList {
            add(csvLine(listOf("card_uid", "person_id", "person_name", "group", "created_at")))
            students.forEach { student ->
                add(
                    csvLine(
                        listOf(
                            student.cardUid,
                            student.studentId,
                            student.name,
                            student.section,
                            student.createdAt,
                        ),
                    ),
                )
            }
        }
        studentsCsv.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun appendCsv(file: File, header: List<String>, row: List<String>) {
        if (!file.exists()) {
            file.writeText(csvLine(header) + "\n")
        }
        file.appendText(csvLine(row) + "\n")
    }
}

private fun NdefRecord.toReadableText(): String {
    return when {
        tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_TEXT) -> {
            parseTextPayload(payload)
        }
        tnf == NdefRecord.TNF_WELL_KNOWN && type.contentEquals(NdefRecord.RTD_URI) -> {
            parseUriPayload(payload)
        }
        tnf == NdefRecord.TNF_ABSOLUTE_URI -> {
            String(type, StandardCharsets.UTF_8)
        }
        tnf == NdefRecord.TNF_MIME_MEDIA -> {
            "${String(type, StandardCharsets.UTF_8)}: ${payload.previewText()}"
        }
        else -> {
            "TNF $tnf, type ${type.toHex()}, payload ${payload.previewText()}"
        }
    }
}

private fun parseTextPayload(payload: ByteArray): String {
    if (payload.isEmpty()) return ""
    val status = payload[0].toInt()
    val languageCodeLength = status and 0x3F
    val charset = if ((status and 0x80) == 0) StandardCharsets.UTF_8 else Charset.forName("UTF-16")
    val textStart = 1 + languageCodeLength
    if (textStart >= payload.size) return ""
    return String(payload, textStart, payload.size - textStart, charset)
}

private fun parseUriPayload(payload: ByteArray): String {
    if (payload.isEmpty()) return ""
    val prefixes = listOf(
        "",
        "http://www.",
        "https://www.",
        "http://",
        "https://",
        "tel:",
        "mailto:",
        "ftp://anonymous:anonymous@",
        "ftp://ftp.",
        "ftps://",
        "sftp://",
        "smb://",
        "nfs://",
        "ftp://",
        "dav://",
        "news:",
        "telnet://",
        "imap:",
        "rtsp://",
        "urn:",
        "pop:",
        "sip:",
        "sips:",
        "tftp:",
        "btspp://",
        "btl2cap://",
        "btgoep://",
        "tcpobex://",
        "irdaobex://",
        "file://",
        "urn:epc:id:",
        "urn:epc:tag:",
        "urn:epc:pat:",
        "urn:epc:raw:",
        "urn:epc:",
        "urn:nfc:",
    )
    val prefixIndex = payload[0].toInt() and 0xFF
    val prefix = prefixes.getOrElse(prefixIndex) { "" }
    val suffix = String(payload, 1, payload.size - 1, StandardCharsets.UTF_8)
    return prefix + suffix
}

private fun ByteArray.toHex(): String =
    joinToString(":") { byte -> "%02X".format(Locale.US, byte.toInt() and 0xFF) }

private fun ByteArray.previewText(): String {
    if (isEmpty()) return ""
    val printable = String(this, StandardCharsets.UTF_8)
        .replace(Regex("\\s+"), " ")
        .take(80)
    return printable.ifBlank { toHex() }
}

private fun nowText(): String =
    ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

private fun csvLine(values: List<String>): String =
    values.joinToString(",") { value -> "\"${value.replace("\"", "\"\"")}\"" }

private fun String.normalizeHeader(): String =
    removePrefix("\uFEFF")
        .trim()
        .lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private fun List<String>.findHeader(vararg aliases: String): Int {
    aliases.forEach { alias ->
        val index = indexOf(alias.normalizeHeader())
        if (index >= 0) return index
    }
    return -1
}

private fun List<String>.valueAt(index: Int): String =
    if (index in indices) this[index].trim() else ""

private fun parseCsvLine(line: String): List<String> {
    val values = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var index = 0

    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index += 1
            }
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                values += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
        index += 1
    }
    values += current.toString()
    return values
}
