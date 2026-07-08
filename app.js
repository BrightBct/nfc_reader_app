const STORAGE_KEY = "nfc-check-in-pages-v1";
const NO_GROUP = "All";
const ID_ALIASES = ["id", "person id", "student id", "employee id", "staff id", "member id", "user id", "code", "number"];
const NAME_ALIASES = ["name", "full name", "person name", "student name", "employee name", "staff name", "name (en)", "name (th)"];
const GROUP_ALIASES = ["group", "section", "department", "team", "class", "room", "unit", "division"];
const UID_ALIASES = ["card uid", "uid", "nfc uid", "card id", "tag id", "normalized uid"];
const EVENT_ALIASES = ["event name", "event", "check-in", "check in"];

const state = loadState();
let pendingImport = null;
let pendingUid = null;
let scanAutoTimer = 0;
let toastTimer = 0;

const els = {
  storageStatus: document.getElementById("storageStatus"),
  eventNameInput: document.getElementById("eventNameInput"),
  csvFileInput: document.getElementById("csvFileInput"),
  groupSelect: document.getElementById("groupSelect"),
  personSearchInput: document.getElementById("personSearchInput"),
  columnMapper: document.getElementById("columnMapper"),
  idColumnSelect: document.getElementById("idColumnSelect"),
  nameColumnSelect: document.getElementById("nameColumnSelect"),
  groupColumnSelect: document.getElementById("groupColumnSelect"),
  applyColumnsButton: document.getElementById("applyColumnsButton"),
  scanForm: document.getElementById("scanForm"),
  scanInput: document.getElementById("scanInput"),
  scanResult: document.getElementById("scanResult"),
  lastCheckinBanner: document.getElementById("lastCheckinBanner"),
  lastCheckinName: document.getElementById("lastCheckinName"),
  lastCheckinMeta: document.getElementById("lastCheckinMeta"),
  checkinToast: document.getElementById("checkinToast"),
  checkinToastTitle: document.getElementById("checkinToastTitle"),
  checkinToastMeta: document.getElementById("checkinToastMeta"),
  todayCount: document.getElementById("todayCount"),
  linkPanel: document.getElementById("linkPanel"),
  unknownUidText: document.getElementById("unknownUidText"),
  personSelect: document.getElementById("personSelect"),
  linkCardButton: document.getElementById("linkCardButton"),
  cancelLinkButton: document.getElementById("cancelLinkButton"),
  manualForm: document.getElementById("manualForm"),
  manualSearchInput: document.getElementById("manualSearchInput"),
  manualPersonSelect: document.getElementById("manualPersonSelect"),
  manualCheckinButton: document.getElementById("manualCheckinButton"),
  remainingTableBody: document.getElementById("remainingTableBody"),
  checkedInTableBody: document.getElementById("checkedInTableBody"),
  remainingCount: document.getElementById("remainingCount"),
  checkedInCount: document.getElementById("checkedInCount"),
  importPeopleButton: document.getElementById("importPeopleButton"),
  peopleImportInput: document.getElementById("peopleImportInput"),
  exportPeopleButton: document.getElementById("exportPeopleButton"),
  exportCheckinsButton: document.getElementById("exportCheckinsButton"),
  exportScansButton: document.getElementById("exportScansButton"),
  clearDataButton: document.getElementById("clearDataButton"),
  emptyRowTemplate: document.getElementById("emptyRowTemplate"),
};

bindEvents();
render();
queueFocus();

function bindEvents() {
  els.eventNameInput.addEventListener("input", () => {
    state.eventName = els.eventNameInput.value.trim();
    saveState();
    renderStatus();
  });

  els.csvFileInput.addEventListener("change", handleCsvFile);
  els.applyColumnsButton.addEventListener("click", applyPendingColumns);
  els.groupSelect.addEventListener("change", () => {
    state.selectedGroup = els.groupSelect.value || NO_GROUP;
    saveState();
    render();
    queueFocus();
  });
  els.personSearchInput.addEventListener("input", renderPeopleViews);
  els.scanForm.addEventListener("submit", event => {
    event.preventDefault();
    handleScan(els.scanInput.value);
  });
  els.scanInput.addEventListener("keydown", event => {
    if (event.key === "Enter" || event.key === "Tab") {
      event.preventDefault();
      handleScan(els.scanInput.value);
    }
  });
  els.scanInput.addEventListener("input", scheduleAutoScan);
  els.linkCardButton.addEventListener("click", linkPendingCard);
  els.cancelLinkButton.addEventListener("click", clearPendingLink);
  els.manualSearchInput.addEventListener("input", renderManualPeople);
  els.manualForm.addEventListener("submit", event => {
    event.preventDefault();
    manualCheckin();
  });
  els.importPeopleButton.addEventListener("click", () => els.peopleImportInput.click());
  els.peopleImportInput.addEventListener("change", handlePeopleImportFile);
  els.exportPeopleButton.addEventListener("click", () => exportCsv("people.csv", peopleExportRows()));
  els.exportCheckinsButton.addEventListener("click", () => exportCsv("checkins.csv", checkinExportRows()));
  els.exportScansButton.addEventListener("click", () => exportCsv("scans.csv", scanExportRows()));
  els.clearDataButton.addEventListener("click", clearLocalData);
}

function loadState() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
    return {
      eventName: saved.eventName || "NFC Check-In",
      selectedGroup: saved.selectedGroup || NO_GROUP,
      people: Array.isArray(saved.people) ? saved.people.map(normalizeSavedPerson) : [],
      checkins: Array.isArray(saved.checkins) ? saved.checkins.map(normalizeSavedCheckin) : [],
      scans: Array.isArray(saved.scans) ? saved.scans.map(normalizeSavedScan) : [],
    };
  } catch {
    return { eventName: "NFC Check-In", selectedGroup: NO_GROUP, people: [], checkins: [], scans: [] };
  }
}

function saveState() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

function normalizeSavedPerson(person) {
  return { ...person, uid: normalizeUid(person.uid) || person.uid || "" };
}

function normalizeSavedCheckin(checkin) {
  return { ...checkin, uid: normalizeUid(checkin.uid) || checkin.uid || "" };
}

function normalizeSavedScan(scan) {
  return { ...scan, uid: normalizeUid(scan.uid) || scan.uid || "" };
}

function handleCsvFile(event) {
  const file = event.target.files && event.target.files[0];
  if (!file) return;

  const reader = new FileReader();
  reader.onload = () => {
    const parsed = parseCsv(String(reader.result || ""));
    if (!parsed.headers.length || !parsed.rows.length) {
      showResult("CSV has no readable rows.", "warn");
      els.csvFileInput.value = "";
      return;
    }
    pendingImport = parsed;
    setupColumnMapper(parsed.headers);
    els.columnMapper.hidden = false;
    showResult(`Loaded ${parsed.rows.length} CSV rows. Check the columns, then use them.`, "ok");
    els.csvFileInput.value = "";
  };
  reader.onerror = () => {
    showResult("Could not read the CSV file.", "warn");
    els.csvFileInput.value = "";
  };
  reader.readAsText(file);
}

function setupColumnMapper(headers) {
  fillColumnSelect(els.idColumnSelect, headers, findHeader(headers, ID_ALIASES));
  fillColumnSelect(els.nameColumnSelect, headers, findHeader(headers, NAME_ALIASES));
  fillColumnSelect(els.groupColumnSelect, ["", ...headers], findHeader(headers, GROUP_ALIASES));
}

function fillColumnSelect(select, headers, selectedValue) {
  select.innerHTML = "";
  headers.forEach(header => {
    const option = document.createElement("option");
    option.value = header;
    option.textContent = header || "No group column";
    select.append(option);
  });
  select.value = selectedValue || headers[0] || "";
}

function applyPendingColumns() {
  if (!pendingImport) return;

  const idColumn = els.idColumnSelect.value;
  const nameColumn = els.nameColumnSelect.value;
  const groupColumn = els.groupColumnSelect.value;
  if (!idColumn || !nameColumn) {
    showResult("Choose both ID and name columns.", "warn");
    return;
  }

  const people = pendingImport.rows
    .map((row, index) => ({
      key: stablePersonKey(row[idColumn], row[nameColumn], index),
      id: cleanCell(row[idColumn]),
      name: cleanCell(row[nameColumn]),
      group: cleanCell(groupColumn ? row[groupColumn] : "") || NO_GROUP,
      uid: "",
      importedAt: new Date().toISOString(),
      sourceColumns: row,
    }))
    .filter(person => person.id || person.name);

  state.people = people;
  state.selectedGroup = groupOptions()[0] || NO_GROUP;
  pendingImport = null;
  els.columnMapper.hidden = true;
  els.csvFileInput.value = "";
  saveState();
  render();
  showResult(`Imported ${people.length} people.`, "ok");
  queueFocus();
}

function handlePeopleImportFile(event) {
  const file = event.target.files && event.target.files[0];
  if (!file) return;

  const reader = new FileReader();
  reader.onload = () => {
    const parsed = parseCsv(String(reader.result || ""));
    const result = peopleFromImportedPeopleCsv(parsed);
    if (!result.people.length) {
      showResult("People import needs name, ID, and card UID columns.", "warn");
      els.peopleImportInput.value = "";
      return;
    }

    if (state.people.length > 0) {
      const confirmed = window.confirm("Replace the current people and card mappings with this People CSV? Check-ins and scans will stay.");
      if (!confirmed) {
        els.peopleImportInput.value = "";
        queueFocus();
        return;
      }
    }

    state.people = result.people;
    if (result.eventName) state.eventName = result.eventName;
    state.selectedGroup = groupOptions()[0] || NO_GROUP;
    pendingImport = null;
    clearPendingLink();
    saveState();
    render();
    showResult(`Imported ${result.people.length} people with ${result.linkedCount} linked cards.`, "ok");
    els.peopleImportInput.value = "";
    queueFocus();
  };
  reader.onerror = () => {
    showResult("Could not read the People CSV file.", "warn");
    els.peopleImportInput.value = "";
  };
  reader.readAsText(file);
}

function peopleFromImportedPeopleCsv(parsed) {
  const headers = parsed.headers || [];
  const idColumn = findHeader(headers, ID_ALIASES);
  const nameColumn = findHeader(headers, NAME_ALIASES);
  const groupColumn = findHeader(headers, GROUP_ALIASES);
  const uidColumn = findHeader(headers, UID_ALIASES);
  const eventColumn = findHeader(headers, EVENT_ALIASES);
  if (!idColumn || !nameColumn || !uidColumn) return { eventName: "", linkedCount: 0, people: [] };

  const people = parsed.rows
    .map((row, index) => ({
      key: stablePersonKey(row[idColumn], row[nameColumn], index),
      id: cleanCell(row[idColumn]),
      name: cleanCell(row[nameColumn]),
      group: cleanCell(groupColumn ? row[groupColumn] : "") || NO_GROUP,
      uid: normalizeUid(row[uidColumn]),
      importedAt: cleanCell(row["Imported At"]) || new Date().toISOString(),
      sourceColumns: row,
    }))
    .filter(person => person.id || person.name);

  return {
    eventName: eventColumn ? cleanCell(parsed.rows.find(row => cleanCell(row[eventColumn]))?.[eventColumn]) : "",
    linkedCount: people.filter(person => person.uid).length,
    people,
  };
}

function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = "";
  let inQuotes = false;

  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    const next = text[index + 1];

    if (char === '"') {
      if (inQuotes && next === '"') {
        cell += '"';
        index += 1;
      } else {
        inQuotes = !inQuotes;
      }
    } else if (char === "," && !inQuotes) {
      row.push(cell);
      cell = "";
    } else if ((char === "\n" || char === "\r") && !inQuotes) {
      if (char === "\r" && next === "\n") index += 1;
      row.push(cell);
      if (row.some(value => value.trim() !== "")) rows.push(row);
      row = [];
      cell = "";
    } else {
      cell += char;
    }
  }

  row.push(cell);
  if (row.some(value => value.trim() !== "")) rows.push(row);

  const headers = (rows.shift() || []).map(cleanCell);
  return {
    headers,
    rows: rows.map(values => {
      const record = {};
      headers.forEach((header, index) => {
        record[header] = cleanCell(values[index] || "");
      });
      return record;
    }),
  };
}

function findHeader(headers, aliases) {
  const normalized = headers.map(header => [header, normalizeHeader(header)]);
  const exact = normalized.find(([, header]) => aliases.includes(header));
  if (exact) return exact[0];
  const partial = normalized.find(([, header]) => aliases.some(alias => header.includes(alias)));
  return partial ? partial[0] : "";
}

function normalizeHeader(value) {
  return String(value || "").trim().toLowerCase().replace(/\s+/g, " ");
}

function cleanCell(value) {
  return String(value || "").trim();
}

function stablePersonKey(id, name, index) {
  return `${cleanCell(id) || "no-id"}::${cleanCell(name) || "no-name"}::${index}`;
}

function handleScan(rawValue) {
  window.clearTimeout(scanAutoTimer);
  const rawUid = cleanCell(rawValue);
  const uid = normalizeUid(rawUid);
  if (!uid) {
    showResult("No UID received.", "warn");
    queueFocus();
    return;
  }

  const scannedAt = new Date().toISOString();
  const person = state.people.find(item => item.uid === uid);
  state.scans.unshift({
    scannedAt,
    rawUid,
    uid,
    result: person ? "known" : "unknown",
    personId: person ? person.id : "",
    personName: person ? person.name : "",
    group: person ? person.group : state.selectedGroup,
  });

  if (person) {
    clearPendingLink();
    completeCheckin(person, uid, scannedAt, `${person.name || person.id} checked in.`);
    return;
  } else {
    pendingUid = uid;
    renderLinkPanel();
    showResult(`Unknown card ${uid}. Link it to a person.`, "warn");
  }

  els.scanInput.value = "";
  saveState();
  render();
  queueFocus();
}

function scheduleAutoScan() {
  window.clearTimeout(scanAutoTimer);
  const value = els.scanInput.value.trim();
  const compact = value.replace(/[^0-9A-Fa-f]/g, "");
  if (compact.length < 8) return;
  scanAutoTimer = window.setTimeout(() => {
    if (els.scanInput.value.trim() === value) handleScan(value);
  }, 320);
}

function normalizeUid(value) {
  const trimmed = cleanCell(value).toUpperCase();
  if (!trimmed) return "";

  const digitsOnly = trimmed.replace(/\D/g, "");
  if (digitsOnly === trimmed && digitsOnly.length > 8) {
    const decimalUid = decimalCardNumberToUid(digitsOnly);
    if (decimalUid) return decimalUid;
  }

  const legacyDecimal = legacyDecimalUidToUid(trimmed);
  if (legacyDecimal) return legacyDecimal;

  const hexOnly = trimmed.replace(/[^0-9A-F]/g, "");
  if (hexOnly.length >= 4 && hexOnly.length % 2 === 0) {
    return hexOnly.match(/.{1,2}/g).join(":");
  }
  return trimmed.replace(/\s+/g, "");
}

function decimalCardNumberToUid(value) {
  try {
    let number = BigInt(value);
    if (number <= 0n || number > 0xffffffffn) return "";

    const littleEndianBytes = [];
    for (let index = 0; index < 4; index += 1) {
      littleEndianBytes.push(Number(number & 0xffn));
      number >>= 8n;
    }

    if (number !== 0n) return "";
    return littleEndianBytes
      .map(byte => byte.toString(16).toUpperCase().padStart(2, "0"))
      .join(":");
  } catch {
    return "";
  }
}

function legacyDecimalUidToUid(value) {
  if (!/^(\d{2}:){4}\d{2}$/.test(value)) return "";
  return decimalCardNumberToUid(value.replace(/:/g, ""));
}

function recordCheckin(person, uid, scannedAt) {
  const localDate = new Date(scannedAt).toLocaleDateString("en-CA");
  const duplicate = state.checkins.find(checkin => (
    checkin.localDate === localDate &&
    (checkin.personKey === person.key || (uid && checkin.uid === uid))
  ));

  if (duplicate) {
    duplicate.scannedAt = scannedAt;
    duplicate.uid = uid;
    duplicate.personName = person.name;
    duplicate.personId = person.id;
    duplicate.group = person.group;
    duplicate.eventName = state.eventName;
    duplicate.method = uid ? "card" : "manual";
    return;
  }

  state.checkins.unshift({
    scannedAt,
    localDate,
    eventName: state.eventName,
    personKey: person.key,
    personName: person.name,
    personId: person.id,
    group: person.group,
    uid,
    method: uid ? "card" : "manual",
  });
}

function linkPendingCard() {
  if (!pendingUid) return;
  const linkedUid = pendingUid;
  const key = els.personSelect.value;
  const person = state.people.find(item => item.key === key);
  if (!person) {
    showResult("Choose a person before linking.", "warn");
    return;
  }

  const existing = state.people.find(item => item.uid === linkedUid && item.key !== person.key);
  if (existing) existing.uid = "";

  person.uid = linkedUid;
  const scannedAt = new Date().toISOString();
  state.scans.unshift({
    scannedAt,
    rawUid: linkedUid,
    uid: linkedUid,
    result: "linked",
    personId: person.id,
    personName: person.name,
    group: person.group,
  });
  clearPendingLink();
  completeCheckin(person, linkedUid, scannedAt, `${person.name || person.id} linked and checked in.`);
}

function completeCheckin(person, uid, scannedAt, message) {
  recordCheckin(person, uid, scannedAt);
  saveState();
  showResult(message, "ok");
  showCheckinToast(person, uid, scannedAt);
  els.scanInput.value = "";
  render();
  queueFocus();
}

function manualCheckin() {
  const person = state.people.find(item => item.key === els.manualPersonSelect.value);
  if (!person) {
    showResult("Choose a person for manual check-in.", "warn");
    els.manualSearchInput.focus();
    return;
  }

  const scannedAt = new Date().toISOString();
  state.scans.unshift({
    scannedAt,
    rawUid: "",
    uid: "",
    result: "manual",
    personId: person.id,
    personName: person.name,
    group: person.group,
  });
  completeCheckin(person, "", scannedAt, `${person.name || person.id} manually checked in.`);
  els.manualSearchInput.value = "";
  renderManualPeople();
}

function clearPendingLink() {
  pendingUid = null;
  els.linkPanel.hidden = true;
  els.unknownUidText.textContent = "";
}

function render() {
  els.eventNameInput.value = state.eventName;
  renderStatus();
  renderGroups();
  renderPeopleViews();
  renderTodayCount();
}

function renderStatus() {
  const rosterText = state.people.length === 1 ? "1 person" : `${state.people.length} people`;
  const checkinText = state.checkins.length === 1 ? "1 check-in" : `${state.checkins.length} check-ins`;
  els.storageStatus.textContent = `${state.eventName || "NFC Check-In"} · ${rosterText} · ${checkinText} · saved in this browser`;
}

function renderGroups() {
  const groups = groupOptions();
  els.groupSelect.innerHTML = "";
  groups.forEach(group => {
    const option = document.createElement("option");
    option.value = group;
    option.textContent = group;
    els.groupSelect.append(option);
  });
  if (!groups.includes(state.selectedGroup)) state.selectedGroup = groups[0] || NO_GROUP;
  els.groupSelect.value = state.selectedGroup;
}

function groupOptions() {
  const groups = [...new Set(state.people.map(person => person.group || NO_GROUP))].sort((a, b) => a.localeCompare(b));
  return groups.length ? groups : [NO_GROUP];
}

function renderPeopleViews() {
  renderAttendanceTables();
  renderLinkPanel();
  renderManualPeople();
}

function filteredPeople() {
  const query = els.personSearchInput.value.trim().toLowerCase();
  return state.people
    .filter(person => (state.selectedGroup === NO_GROUP ? true : person.group === state.selectedGroup))
    .filter(person => {
      if (!query) return true;
      return [person.name, person.id, person.uid, person.group].some(value => String(value || "").toLowerCase().includes(query));
    })
    .sort((a, b) => (a.name || a.id).localeCompare(b.name || b.id));
}

function renderAttendanceTables() {
  const todayCheckins = todaysCheckinsForSelectedGroup();
  const checkedPersonKeys = new Set(todayCheckins.map(checkin => checkin.personKey).filter(Boolean));
  const remainingPeople = filteredPeople().filter(person => !checkedPersonKeys.has(person.key));

  els.remainingCount.textContent = `${remainingPeople.length}`;
  els.checkedInCount.textContent = `${todayCheckins.length}`;
  renderRemainingTable(remainingPeople);
  renderCheckedInTable(todayCheckins);
}

function todaysCheckinsForSelectedGroup() {
  const today = new Date().toLocaleDateString("en-CA");
  return state.checkins
    .filter(checkin => checkin.localDate === today)
    .filter(checkin => state.selectedGroup === NO_GROUP ? true : checkin.group === state.selectedGroup)
    .sort((a, b) => new Date(b.scannedAt) - new Date(a.scannedAt));
}

function renderRemainingTable(people) {
  els.remainingTableBody.innerHTML = "";
  if (!people.length) {
    els.remainingTableBody.append(emptyRow());
    return;
  }

  people.forEach(person => {
    const row = document.createElement("tr");
    appendCell(row, person.name);
    appendCell(row, person.id);
    appendCell(row, person.group);
    appendCell(row, person.uid || "Not linked");
    els.remainingTableBody.append(row);
  });
}

function renderCheckedInTable(checkins) {
  els.checkedInTableBody.innerHTML = "";
  if (!checkins.length) {
    els.checkedInTableBody.append(emptyRow());
    return;
  }

  checkins.forEach(checkin => {
    const row = document.createElement("tr");
    appendCell(row, formatTime(checkin.scannedAt));
    appendCell(row, checkin.personName);
    appendCell(row, checkin.personId);
    appendCell(row, checkin.uid || "Manual");
    els.checkedInTableBody.append(row);
  });
}

function renderLinkPanel() {
  if (!pendingUid) return;
  els.linkPanel.hidden = false;
  els.unknownUidText.textContent = pendingUid;
  const people = filteredPeople().filter(person => !person.uid || person.uid === pendingUid);
  els.personSelect.innerHTML = "";

  people.forEach(person => {
    const option = document.createElement("option");
    option.value = person.key;
    option.textContent = `${person.name || "No name"} · ${person.id || "No ID"} · ${person.group || NO_GROUP}`;
    els.personSelect.append(option);
  });

  els.linkCardButton.disabled = people.length === 0;
}

function renderManualPeople() {
  const people = manualPeople();
  els.manualPersonSelect.innerHTML = "";

  if (!people.length) {
    const option = document.createElement("option");
    option.value = "";
    option.textContent = state.people.length ? "No matching people" : "Import people first";
    els.manualPersonSelect.append(option);
    els.manualCheckinButton.disabled = true;
    return;
  }

  people.forEach(person => {
    const option = document.createElement("option");
    option.value = person.key;
    option.textContent = `${person.name || "No name"} · ${person.id || "No ID"} · ${person.group || NO_GROUP}`;
    els.manualPersonSelect.append(option);
  });
  els.manualCheckinButton.disabled = false;
}

function manualPeople() {
  const query = els.manualSearchInput.value.trim().toLowerCase();
  return state.people
    .filter(person => (state.selectedGroup === NO_GROUP ? true : person.group === state.selectedGroup))
    .filter(person => {
      if (!query) return true;
      return [person.name, person.id, person.group].some(value => String(value || "").toLowerCase().includes(query));
    })
    .sort((a, b) => (a.name || a.id).localeCompare(b.name || b.id))
    .slice(0, 80);
}

function renderTodayCount() {
  const today = new Date().toLocaleDateString("en-CA");
  const count = state.checkins.filter(checkin => checkin.localDate === today).length;
  els.todayCount.textContent = `${count} today`;
}

function appendCell(row, value) {
  const cell = document.createElement("td");
  cell.textContent = value || "";
  row.append(cell);
}

function emptyRow() {
  return els.emptyRowTemplate.content.firstElementChild.cloneNode(true);
}

function formatTime(value) {
  if (!value) return "";
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

function showResult(message, tone) {
  els.scanResult.textContent = message;
  els.scanResult.className = `scan-result ${tone || ""}`.trim();
}

function showCheckinToast(person, uid, scannedAt) {
  window.clearTimeout(toastTimer);
  const title = person.name || person.id || "Checked in";
  const meta = [person.id, person.group, formatTime(scannedAt), uid]
    .filter(Boolean)
    .join(" · ");

  els.lastCheckinBanner.hidden = false;
  els.lastCheckinName.textContent = title;
  els.lastCheckinMeta.textContent = meta;

  els.checkinToast.hidden = false;
  els.checkinToastTitle.textContent = title;
  els.checkinToastMeta.textContent = meta;
  const animate = window.requestAnimationFrame || (callback => window.setTimeout(callback, 0));
  animate(() => {
    els.checkinToast.classList.add("show");
  });
  toastTimer = window.setTimeout(() => {
    els.checkinToast.classList.remove("show");
  }, 5000);
}

function queueFocus() {
  window.setTimeout(() => els.scanInput.focus(), 40);
}

function peopleExportRows() {
  return [
    ["Event Name", "Name", "ID", "Group", "Card UID", "Imported At"],
    ...state.people.map(person => [state.eventName, person.name, person.id, person.group, person.uid, person.importedAt]),
  ];
}

function checkinExportRows() {
  return [
    ["Event Name", "Scanned At", "Local Date", "Name", "ID", "Group", "Card UID", "Method"],
    ...state.checkins.map(checkin => [
      checkin.eventName,
      checkin.scannedAt,
      checkin.localDate,
      checkin.personName,
      checkin.personId,
      checkin.group,
      checkin.uid,
      checkin.method || (checkin.uid ? "card" : "manual"),
    ]),
  ];
}

function scanExportRows() {
  return [
    ["Scanned At", "Raw UID", "Normalized UID", "Result", "Name", "ID", "Group"],
    ...state.scans.map(scan => [
      scan.scannedAt,
      scan.rawUid,
      scan.uid,
      scan.result,
      scan.personName,
      scan.personId,
      scan.group,
    ]),
  ];
}

function exportCsv(filename, rows) {
  const csv = rows.map(row => row.map(csvCell).join(",")).join("\r\n");
  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

function csvCell(value) {
  const text = String(value || "");
  if (/[",\r\n]/.test(text)) return `"${text.replace(/"/g, '""')}"`;
  return text;
}

function clearLocalData() {
  const confirmed = window.confirm("Clear imported roster, linked cards, check-ins, and scans from this browser?");
  if (!confirmed) return;
  localStorage.removeItem(STORAGE_KEY);
  state.eventName = "NFC Check-In";
  state.selectedGroup = NO_GROUP;
  state.people = [];
  state.checkins = [];
  state.scans = [];
  pendingImport = null;
  clearPendingLink();
  render();
  showResult("Local browser data cleared.", "ok");
  queueFocus();
}
