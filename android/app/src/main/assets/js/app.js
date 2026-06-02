// Client Routing, State, Calibration, Tuner, and Results Charting - PitchFlight

// Global User State
let currentUser = {
  id: 1,
  username: "Singer",
  voice_type: "Not Calibrated",
  min_pitch_hz: 80.0,
  max_pitch_hz: 400.0,
  current_level: 1,
  total_score: 0
};

// Global active view state
let currentActiveView = "welcome-view";

// Audio and Game instances (accessed from window globals)
const audio = window.audioEngine;

// Database global instance and in-memory caches
let localDB = null;
let dbCache = {
  currentUser: null,
  allUsers: [],
  cachedStats: [],
  offlineQueue: [],
  localNotePerformance: [],
  unlockedAchievements: [],
  offlinePracticeLogs: []
};

// Stats History Pagination Variables
let statsOffset = 0;
const statsLimit = 20;
let statsHistoryRows = [];

// Global function to initialize local db
async function initLocalDatabase() {
  try {
    localDB = new VocalTrainerDB();
    await localDB.init();
    
    // Load settings
    const storedUser = await localDB.get('settings', 'currentUser');
    if (storedUser) {
      dbCache.currentUser = storedUser;
      currentUser = storedUser;
    }
    
    // Load others
    dbCache.allUsers = await localDB.getAll('users');
    dbCache.cachedStats = await localDB.getAll('progress');
    dbCache.offlineQueue = await localDB.getAll('offlineQueue');
    dbCache.localNotePerformance = await localDB.getAll('notePerformance');
    dbCache.unlockedAchievements = await localDB.getAll('achievements');
    dbCache.offlinePracticeLogs = await localDB.getAll('practiceLogs');
    
    // Sort cachedStats descending
    dbCache.cachedStats.sort((a, b) => new Date(b.completed_at || b.timestamp) - new Date(a.completed_at || a.timestamp));

    console.log("[DB] IndexedDB loaded into cache successfully");
    updateSyncStatusBadge();
    
    // Restore last active view state (preventing state loss on pull-to-refresh)
    const lastView = sessionStorage.getItem('lastView');
    if (lastView && lastView !== 'welcome-view') {
      setTimeout(() => {
        // Switch to last active view if calibrated/logged in
        if (currentUser && currentUser.voice_type !== 'Not Calibrated') {
          switchView(lastView);
        }
      }, 200);
    }
  } catch (err) {
    console.error("[DB] Failed to load IndexedDB:", err);
  }
}

async function saveCurrentUserLocal(user) {
  currentUser = user;
  dbCache.currentUser = user;
  if (localDB) {
    await localDB.put('settings', user, 'currentUser');
    await localDB.put('users', user);
  }
  updateSyncStatusBadge();
}

async function saveAllUsersLocal(usersList) {
  dbCache.allUsers = usersList;
  if (localDB) {
    await localDB.clear('users');
    for (const u of usersList) {
      await localDB.put('users', u);
    }
  }
}

async function addToOfflineQueueLocal(item) {
  if (!item.queueId) {
    item.queueId = crypto.randomUUID ? crypto.randomUUID() : 'q_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
  }
  dbCache.offlineQueue.push(item);
  if (localDB) {
    await localDB.put('offlineQueue', item);
  }
  updateSyncStatusBadge();
}

async function saveOfflineQueueLocal(queueList) {
  dbCache.offlineQueue = queueList;
  if (localDB) {
    await localDB.clear('offlineQueue');
    for (const item of queueList) {
      if (!item.queueId) {
        item.queueId = crypto.randomUUID ? crypto.randomUUID() : 'q_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
      }
      await localDB.put('offlineQueue', item);
    }
  }
  updateSyncStatusBadge();
}

async function saveCachedStatsLocal(statsList) {
  dbCache.cachedStats = statsList;
  if (localDB) {
    await localDB.clear('progress');
    for (const s of statsList) {
      if (!s.id) s.id = Date.now() + Math.random();
      await localDB.put('progress', s);
    }
  }
}

async function saveNotePerformanceLocal(notePerfList) {
  dbCache.localNotePerformance = notePerfList;
  if (localDB) {
    await localDB.clear('notePerformance');
    for (const p of notePerfList) {
      await localDB.put('notePerformance', p);
    }
  }
}

async function saveUnlockedAchievementsLocal(achievementsList) {
  dbCache.unlockedAchievements = achievementsList;
  if (localDB) {
    await localDB.clear('achievements');
    for (const a of achievementsList) {
      await localDB.put('achievements', a);
    }
  }
}

async function saveOfflinePracticeLogsLocal(logsList) {
  dbCache.offlinePracticeLogs = logsList;
  if (localDB) {
    await localDB.clear('practiceLogs');
    for (const l of logsList) {
      await localDB.put('practiceLogs', l);
    }
  }
}

// Client Concurrency Sync Mutex Lock
let isSyncingOfflineQueue = false;

// Global Client-Side Error Logging and Propagation
window.addEventListener('error', (e) => {
  logClientErrorLocally('error', e.message, `${e.filename}:${e.lineno}:${e.colno}`, e.error ? e.error.stack : '');
});

window.addEventListener('unhandledrejection', (e) => {
  logClientErrorLocally('unhandledrejection', e.reason ? e.reason.message || String(e.reason) : 'Promise rejection', '', e.reason ? e.reason.stack : '');
});

async function logClientErrorLocally(type, message, location, stack) {
  const errorObj = {
    queueId: 'err_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9),
    type,
    message,
    location,
    stack,
    timestamp: new Date().toISOString(),
    userAgent: navigator.userAgent
  };
  
  console.error("[Local Error Buffer Cached]:", message, errorObj);
  
  if (localDB) {
    try {
      await localDB.put('errorLogs', errorObj);
    } catch (err) {
      console.warn("Failed to cache client diagnostic error to IndexedDB:", err);
    }
  }
  
  // Try sending it right away if online
  syncErrorLogs().catch(() => {});
}

async function syncErrorLogs() {
  if (!localDB) return;
  try {
    const errorLogs = await localDB.getAll('errorLogs');
    if (errorLogs.length === 0) return;
    
    console.log(`Syncing ${errorLogs.length} client error logs from offline buffer...`);
    for (const log of errorLogs) {
      try {
        const response = await fetch('/api/diagnostics/log', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(log)
        });
        const resData = await response.json();
        if (response.ok && resData.status === 'received') {
          await localDB.delete('errorLogs', log.queueId);
        }
      } catch (err) {
        break; // Stop and retry later if server is down/offline
      }
    }
  } catch (err) {
    console.warn("Failed to sync error logs:", err);
  }
}

// Hardware Audio Disconnect Event Listener
window.addEventListener('mic-disconnected', (e) => {
  alert("Microphone connection lost! Please verify your physical device connections or browser permission states.");
  if (game && game.isRunning) {
    game.stop();
  }
  switchView('dashboard-view');
});

// Database Write Warning Event Listener
window.addEventListener('db-write-error', (e) => {
  console.warn("[Local Database Event] Write error detected:", e.detail.error);
  let warningBanner = document.getElementById('db-warning-banner');
  if (!warningBanner) {
    warningBanner = document.createElement('div');
    warningBanner.id = 'db-warning-banner';
    warningBanner.style.cssText = 'position: fixed; bottom: 20px; right: 20px; background: rgba(255, 170, 0, 0.95); color: #000; padding: 15px; border-radius: 8px; z-index: 100000; font-size: 0.9rem; max-width: 350px; box-shadow: 0 4px 15px rgba(0,0,0,0.3); border-left: 5px solid #e65c00; font-family: sans-serif;';
    
    warningBanner.innerHTML = `
      <div style="font-weight: bold; margin-bottom: 5px; display: flex; align-items: center; gap: 5px;">
        <i class="fa-solid fa-triangle-exclamation"></i> Storage Warning
      </div>
      <div style="font-size: 0.85rem; line-height: 1.4;">
        Failed to save progress locally. Your browser storage might be full or private browsing may restrict local database access.
      </div>
      <button onclick="this.parentElement.remove()" style="margin-top: 10px; background: #000; color: #fff; border: none; padding: 4px 8px; border-radius: 4px; font-weight: bold; cursor: pointer; font-size: 0.75rem;">Dismiss</button>
    `;
    document.body.appendChild(warningBanner);
  }
});


function updateSyncStatusBadge() {
  const badge = document.getElementById('sync-status-indicator');
  const text = document.getElementById('sync-status-text');
  const icon = document.getElementById('sync-status-icon');
  
  if (!badge) return;
  
  if (!currentUser || currentUser.voice_type === 'Not Calibrated') {
    badge.style.display = 'none';
    return;
  }
  
  badge.style.display = 'flex';
  // Count only progress types or both inside the unified queue
  const queueLength = dbCache.offlineQueue.length;
  
  if (queueLength > 0) {
    text.innerText = `${queueLength} pending`;
    icon.className = "fa-solid fa-arrows-rotate fa-spin";
    badge.style.borderColor = "var(--neon-purple)";
    text.style.color = "var(--neon-purple)";
  } else {
    text.innerText = "Synced";
    icon.className = "fa-solid fa-check";
    badge.style.borderColor = "rgba(0, 242, 254, 0.2)";
    text.style.color = "var(--text-secondary)";
  }
}
let game = null;
let sheetEngine = null;

// UI Loop IDs
let tunerIntervalId = null;
let warmupIntervalId = null;
let waveformIntervalId = null;

// Preloaded MIDI Presets for instant Karaoke testing (in case user has no MIDI file)
const presetSongs = {
  "happy-birthday": [
    { midi: 60, time: 0.5, duration: 0.5, name: "Hap-" },
    { midi: 60, time: 1.0, duration: 0.5, name: "py" },
    { midi: 62, time: 1.5, duration: 1.0, name: "Birth-" },
    { midi: 60, time: 2.5, duration: 1.0, name: "day" },
    { midi: 65, time: 3.5, duration: 1.0, name: "to" },
    { midi: 64, time: 4.5, duration: 2.0, name: "you" },
    
    { midi: 60, time: 7.0, duration: 0.5, name: "Hap-" },
    { midi: 60, time: 7.5, duration: 0.5, name: "py" },
    { midi: 62, time: 8.0, duration: 1.0, name: "Birth-" },
    { midi: 60, time: 9.0, duration: 1.0, name: "day" },
    { midi: 67, time: 10.0, duration: 1.0, name: "to" },
    { midi: 65, time: 11.0, duration: 2.0, name: "you" },
    
    { midi: 60, time: 13.5, duration: 0.5, name: "Hap-" },
    { midi: 60, time: 14.0, duration: 0.5, name: "py" },
    { midi: 72, time: 14.5, duration: 1.0, name: "Birth-" },
    { midi: 69, time: 15.5, duration: 1.0, name: "day" },
    { midi: 65, time: 16.5, duration: 1.0, name: "dear" },
    { midi: 64, time: 17.5, duration: 1.0, name: "Sin-" },
    { midi: 62, time: 18.5, duration: 1.5, name: "ger" },
    
    { midi: 70, time: 20.5, duration: 0.5, name: "Hap-" },
    { midi: 70, time: 21.0, duration: 0.5, name: "py" },
    { midi: 69, time: 21.5, duration: 1.0, name: "Birth-" },
    { midi: 65, time: 22.5, duration: 1.0, name: "day" },
    { midi: 67, time: 23.5, duration: 1.0, name: "to" },
    { midi: 65, time: 24.5, duration: 2.5, name: "you" }
  ],
  "twinkle": [
    { midi: 60, time: 0.5, duration: 0.8, name: "Twin-" },
    { midi: 60, time: 1.5, duration: 0.8, name: "kle" },
    { midi: 67, time: 2.5, duration: 0.8, name: "twin-" },
    { midi: 67, time: 3.5, duration: 0.8, name: "kle" },
    { midi: 69, time: 4.5, duration: 0.8, name: "lit-" },
    { midi: 69, time: 5.5, duration: 0.8, name: "tle" },
    { midi: 67, time: 6.5, duration: 1.5, name: "star" },
    
    { midi: 65, time: 8.5, duration: 0.8, name: "How" },
    { midi: 65, time: 9.5, duration: 0.8, name: "I" },
    { midi: 64, time: 10.5, duration: 0.8, name: "won-" },
    { midi: 64, time: 11.5, duration: 0.8, name: "der" },
    { midi: 62, time: 12.5, duration: 0.8, name: "what" },
    { midi: 62, time: 13.5, duration: 0.8, name: "you" },
    { midi: 60, time: 14.5, duration: 1.5, name: "are" }
  ],
  "amazing-grace": [
    { midi: 57, time: 1.0, duration: 0.8, name: "A-" },
    { midi: 60, time: 2.0, duration: 1.8, name: "ma-" },
    { midi: 64, time: 4.0, duration: 0.5, name: "zing" },
    { midi: 60, time: 4.5, duration: 0.8, name: "grace" },
    { midi: 64, time: 5.5, duration: 1.8, name: "how" },
    { midi: 62, time: 7.5, duration: 0.8, name: "sweet" },
    { midi: 60, time: 8.5, duration: 1.8, name: "the" },
    { midi: 57, time: 10.5, duration: 0.8, name: "sound" },
    { midi: 55, time: 11.5, duration: 2.0, name: "that" },
    { midi: 57, time: 14.0, duration: 0.8, name: "saved" }
  ],
  "ode-to-joy": [
    { midi: 64, time: 0.5, duration: 0.4, name: "Joy" },
    { midi: 64, time: 1.0, duration: 0.4, name: "bright" },
    { midi: 65, time: 1.5, duration: 0.4, name: "spark" },
    { midi: 67, time: 2.0, duration: 0.4, name: "of" },
    { midi: 67, time: 2.5, duration: 0.4, name: "heav'n-" },
    { midi: 65, time: 3.0, duration: 0.4, name: "ly" },
    { midi: 64, time: 3.5, duration: 0.4, name: "glo-" },
    { midi: 62, time: 4.0, duration: 0.4, name: "ry" },
    { midi: 60, time: 4.5, duration: 0.4, name: "Daugh-" },
    { midi: 60, time: 5.0, duration: 0.4, name: "ter" },
    { midi: 62, time: 5.5, duration: 0.4, name: "of" },
    { midi: 64, time: 6.0, duration: 0.4, name: "E-" },
    { midi: 64, time: 6.5, duration: 0.6, name: "ly-" },
    { midi: 62, time: 7.2, duration: 0.3, name: "si-" },
    { midi: 62, time: 7.6, duration: 0.8, name: "um" }
  ],
  "row-boat": [
    { midi: 60, time: 0.5, duration: 0.5, name: "Row" },
    { midi: 60, time: 1.1, duration: 0.5, name: "row" },
    { midi: 60, time: 1.7, duration: 0.5, name: "row" },
    { midi: 62, time: 2.3, duration: 0.3, name: "your" },
    { midi: 64, time: 2.7, duration: 0.8, name: "boat" },
    { midi: 64, time: 3.6, duration: 0.5, name: "Gent-" },
    { midi: 62, time: 4.2, duration: 0.3, name: "ly" },
    { midi: 64, time: 4.6, duration: 0.5, name: "down" },
    { midi: 65, time: 5.2, duration: 0.3, name: "the" },
    { midi: 67, time: 5.6, duration: 1.0, name: "stream" },
    { midi: 72, time: 7.0, duration: 0.3, name: "Mer-" },
    { midi: 72, time: 7.3, duration: 0.3, name: "ri-" },
    { midi: 72, time: 7.6, duration: 0.3, name: "ly" },
    { midi: 67, time: 7.9, duration: 0.3, name: "mer-" },
    { midi: 67, time: 8.2, duration: 0.3, name: "ri-" },
    { midi: 67, time: 8.5, duration: 0.3, name: "ly" },
    { midi: 64, time: 8.8, duration: 0.3, name: "mer-" },
    { midi: 64, time: 9.1, duration: 0.3, name: "ri-" },
    { midi: 64, time: 9.4, duration: 0.3, name: "ly" },
    { midi: 60, time: 9.7, duration: 0.3, name: "mer-" },
    { midi: 60, time: 10.0, duration: 0.3, name: "ri-" },
    { midi: 60, time: 10.3, duration: 0.3, name: "ly" },
    { midi: 67, time: 10.7, duration: 0.5, name: "Life" },
    { midi: 64, time: 11.3, duration: 0.3, name: "is" },
    { midi: 62, time: 11.7, duration: 0.5, name: "but" },
    { midi: 60, time: 12.3, duration: 1.2, name: "a dream" }
  ],
  "london-bridge": [
    { midi: 67, time: 0.5, duration: 0.6, name: "Lon-" },
    { midi: 69, time: 1.2, duration: 0.3, name: "don" },
    { midi: 67, time: 1.6, duration: 0.5, name: "bridge" },
    { midi: 65, time: 2.2, duration: 0.5, name: "is" },
    { midi: 64, time: 2.8, duration: 0.5, name: "fall-" },
    { midi: 65, time: 3.4, duration: 0.5, name: "ing" },
    { midi: 67, time: 4.0, duration: 0.8, name: "down" },
    { midi: 62, time: 5.0, duration: 0.5, name: "fall-" },
    { midi: 64, time: 5.6, duration: 0.5, name: "ing" },
    { midi: 65, time: 6.2, duration: 0.8, name: "down" },
    { midi: 64, time: 7.2, duration: 0.5, name: "fall-" },
    { midi: 65, time: 7.8, duration: 0.5, name: "ing" },
    { midi: 67, time: 8.4, duration: 0.8, name: "down" }
  ],
  "frere-jacques": [
    { midi: 60, time: 0.5, duration: 0.5, name: "Frè-" },
    { midi: 62, time: 1.1, duration: 0.5, name: "re" },
    { midi: 64, time: 1.7, duration: 0.5, name: "Jac-" },
    { midi: 60, time: 2.3, duration: 0.5, name: "ques" },
    { midi: 60, time: 3.0, duration: 0.5, name: "Frè-" },
    { midi: 62, time: 3.6, duration: 0.5, name: "re" },
    { midi: 64, time: 4.2, duration: 0.5, name: "Jac-" },
    { midi: 60, time: 4.8, duration: 0.5, name: "ques" },
    { midi: 64, time: 5.5, duration: 0.5, name: "Dor-" },
    { midi: 65, time: 6.1, duration: 0.5, name: "mez" },
    { midi: 67, time: 6.7, duration: 0.8, name: "vous?" },
    { midi: 64, time: 7.8, duration: 0.5, name: "Dor-" },
    { midi: 65, time: 8.4, duration: 0.5, name: "mez" },
    { midi: 67, time: 9.0, duration: 0.8, name: "vous?" }
  ],
  "scarborough-fair": [
    { midi: 62, time: 0.5, duration: 0.8, name: "Are" },
    { midi: 62, time: 1.4, duration: 0.4, name: "you" },
    { midi: 69, time: 1.9, duration: 0.8, name: "go-" },
    { midi: 69, time: 2.8, duration: 0.8, name: "ing" },
    { midi: 64, time: 3.7, duration: 0.5, name: "to" },
    { midi: 65, time: 4.3, duration: 0.3, name: "Scar-" },
    { midi: 64, time: 4.7, duration: 0.5, name: "bo-" },
    { midi: 62, time: 5.3, duration: 1.2, name: "rough" },
    { midi: 69, time: 6.8, duration: 0.8, name: "Fair?" }
  ],
  "danny-boy": [
    { midi: 62, time: 0.5, duration: 0.5, name: "Oh" },
    { midi: 64, time: 1.1, duration: 0.3, name: "Dan-" },
    { midi: 66, time: 1.5, duration: 0.5, name: "ny" },
    { midi: 67, time: 2.1, duration: 0.5, name: "boy" },
    { midi: 66, time: 2.7, duration: 0.3, name: "the" },
    { midi: 67, time: 3.1, duration: 0.5, name: "pipes" },
    { midi: 69, time: 3.7, duration: 0.3, name: "the" },
    { midi: 71, time: 4.1, duration: 0.5, name: "pipes" },
    { midi: 69, time: 4.7, duration: 0.3, name: "are" },
    { midi: 67, time: 5.1, duration: 1.2, name: "call-" },
    { midi: 64, time: 6.4, duration: 0.8, name: "ing" }
  ],
  "la-cucaracha": [
    { midi: 60, time: 0.5, duration: 0.2, name: "La" },
    { midi: 60, time: 0.8, duration: 0.2, name: "cu-" },
    { midi: 60, time: 1.1, duration: 0.2, name: "ca-" },
    { midi: 65, time: 1.4, duration: 0.5, name: "ra-" },
    { midi: 69, time: 2.0, duration: 0.8, name: "cha" },
    { midi: 60, time: 3.0, duration: 0.2, name: "La" },
    { midi: 60, time: 3.3, duration: 0.2, name: "cu-" },
    { midi: 60, time: 3.6, duration: 0.2, name: "ca-" },
    { midi: 65, time: 3.9, duration: 0.5, name: "ra-" },
    { midi: 69, time: 4.5, duration: 0.8, name: "cha" }
  ],
  "red-river": [
    { midi: 55, time: 0.5, duration: 0.8, name: "From" },
    { midi: 60, time: 1.4, duration: 0.4, name: "this" },
    { midi: 64, time: 1.9, duration: 0.8, name: "val-" },
    { midi: 67, time: 2.8, duration: 0.8, name: "ley" },
    { midi: 67, time: 3.7, duration: 0.4, name: "they" },
    { midi: 65, time: 4.2, duration: 0.4, name: "say" },
    { midi: 64, time: 4.7, duration: 0.4, name: "you" },
    { midi: 65, time: 5.2, duration: 0.4, name: "are" },
    { midi: 64, time: 5.7, duration: 0.8, name: "go-" },
    { midi: 62, time: 6.6, duration: 1.2, name: "ing" }
  ],
  "auld-lang-syne": [
    { midi: 60, time: 0.5, duration: 0.5, name: "Should" },
    { midi: 65, time: 1.1, duration: 0.8, name: "auld" },
    { midi: 65, time: 2.0, duration: 0.3, name: "ac-" },
    { midi: 65, time: 2.4, duration: 0.5, name: "quain-" },
    { midi: 69, time: 3.0, duration: 0.8, name: "tance" },
    { midi: 67, time: 3.9, duration: 0.5, name: "be" },
    { midi: 65, time: 4.5, duration: 0.8, name: "for-" },
  ]
};

// Error handlers are now defined inline in index.html to catch early loading exceptions

// Initializer
document.addEventListener("DOMContentLoaded", () => {
  // Defensive initialization so that a failure in one module doesn't block the rest
  try {
    if (typeof PitchFlightGame !== 'undefined') {
      game = new PitchFlightGame("game-canvas");
    } else {
      console.error("PitchFlightGame class is not defined. Game engine skipped.");
    }
  } catch (err) {
    console.error("Error initializing game engine:", err);
  }

  try {
    if (typeof SheetMusicEngine !== 'undefined') {
      sheetEngine = new SheetMusicEngine("sheet-canvas");
    } else {
      console.error("SheetMusicEngine class is not defined. Sheet engine skipped.");
    }
  } catch (err) {
    console.error("Error initializing sheet music engine:", err);
  }
  
  const safeSetup = (name, setupFn) => {
    try {
      setupFn();
    } catch (err) {
      console.error(`Error during ${name}:`, err);
    }
  };

  safeSetup("setupRouting", setupRouting);
  safeSetup("setupProfileForm", setupProfileForm);
  safeSetup("setupCalibration", setupCalibration);
  safeSetup("setupTuner", setupTuner);
  safeSetup("setupVocalStudio", setupVocalStudio);
  safeSetup("setupKaraokeUploader", setupKaraokeUploader);
  safeSetup("setupWarmups", setupWarmups);
  safeSetup("setupSheetMusic", setupSheetMusic);
  safeSetup("setupPlannerView", setupPlannerView);

  // Load IndexedDB database first, then user data and curriculum on startup
  try {
    initLocalDatabase().then(() => {
      loadCurriculum().then(() => {
        loadUserProfile();
        loadAllUserProfiles();
      });
    });
  } catch (err) {
    console.error("Error loading user profiles and curriculum:", err);
  }


  // Bind Stats Load More Button
  const statsLoadMoreBtn = document.getElementById('stats-load-more-btn');
  if (statsLoadMoreBtn) {
    statsLoadMoreBtn.addEventListener('click', () => {
      renderStatsHistory(true);
    });
  }
  // Register Service Worker for offline PWA support
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/service-worker.js')
      .then(reg => console.log('Service Worker registered successfully:', reg.scope))
      .catch(err => console.error('Service Worker registration failed:', err));
  }
});

// Load Profile from backend SQLite with localStorage cache
async function loadUserProfile() {
  try {
    const res = await fetch('/api/user', { headers: { 'X-User-Id': currentUser ? currentUser.id : '' } });
    if (!res.ok) throw new Error("HTTP error " + res.status);
    const user = await res.json();
    if (user && user.queued) throw new Error("Request queued offline");
    if (user && user.username) {
      currentUser = user;
      saveCurrentUserLocal(currentUser);
      updateUIWithUser();
      // Try to sync offline progress if we are online and profile loaded successfully
      syncOfflineQueue().catch(err => console.error("Error syncing offline queue on load:", err));
    } else {
      throw new Error("Invalid user profile response");
    }
  } catch (err) {
    console.warn("Offline: loading user profile from local storage");
    const localUser = JSON.stringify(dbCache.currentUser);
    if (localUser) {
      currentUser = JSON.parse(localUser);
      updateUIWithUser();
    }
  }
}

// Load All User Profiles for welcome dropdown selection with local storage fallback
async function loadAllUserProfiles() {
  let users = [];
  const select = document.getElementById('user-profile-select');
  const selectSection = document.getElementById('profile-select-section');

  try {
    const res = await fetch('/api/users');
    if (!res.ok) throw new Error("HTTP error " + res.status);
    users = await res.json();
    if (users && users.queued) throw new Error("Request queued offline");
    if (!Array.isArray(users)) throw new Error("Invalid response format");
    saveAllUsersLocal(users);
  } catch (err) {
    console.warn("Offline: loading all profiles from local storage");
    const localUsers = JSON.stringify(dbCache.allUsers);
    users = localUsers ? JSON.parse(localUsers) : [currentUser];
  }
  
  if (users && users.length > 0) {
    select.innerHTML = users.map(u => 
      `<option value="${u.id}">${u.username} (${u.voice_type})</option>`
    ).join('');
    selectSection.style.display = 'block';
  } else {
    selectSection.style.display = 'none';
  }
}

let identifyIntervalId = null;
let identifyTimeLeft = 3.0;
let identifyFrequencies = [];

async function startVoiceIdentification() {
  const feedback = document.getElementById('identify-feedback');
  const btn = document.getElementById('profile-identify-btn');
  
  if (identifyIntervalId) {
    cancelAnimationFrame(identifyIntervalId);
    identifyIntervalId = null;
  }

  try {
    await audio.init();
    
    btn.disabled = true;
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Preparing Microphone...';
    
    feedback.style.display = 'block';
    feedback.style.color = 'var(--neon-cyan)';
    feedback.innerText = "Hum a steady note when ready...";
    
    // Smooth transition: brief delay to clear mic popup then start active listening
    setTimeout(() => {
      btn.innerHTML = '<i class="fa-solid fa-wave-square fa-pulse"></i> Listening for Hum...';
      feedback.innerText = "Listening... (Hum a comfortable note)";
      feedback.style.color = 'var(--neon-green)';
      
      identifyFrequencies = [];
      identifyTimeLeft = 8.0; // 8 seconds maximum window to start humming
      runVoiceIdentifyLoop();
    }, 800);
    
  } catch(e) {
    alert("Microphone access is required for voice identification: " + e.message);
    btn.disabled = false;
    btn.innerHTML = '<i class="fa-solid fa-microphone"></i> Hum to Identify Profile';
    feedback.style.display = 'none';
  }
}

function runVoiceIdentifyLoop() {
  const feedback = document.getElementById('identify-feedback');
  const btn = document.getElementById('profile-identify-btn');
  
  const pitch = audio.detectPitch();
  if (pitch.frequency > 0 && pitch.midi > 20 && pitch.midi < 100) {
    identifyFrequencies.push(pitch.frequency);
    const progress = Math.min(100, Math.round((identifyFrequencies.length / 30) * 100));
    feedback.innerText = `HUM RECEIVED: Analyzing pitch... ${progress}%`;
    feedback.style.color = 'var(--neon-green)';
  }
  
  identifyTimeLeft -= 0.016;
  
  if (identifyFrequencies.length >= 30) {
    // We gathered enough stable pitch samples! Auto-succeed immediately
    btn.disabled = false;
    btn.innerHTML = '<i class="fa-solid fa-microphone"></i> Hum to Identify Profile';
    
    identifyFrequencies.sort((a,b) => a - b);
    const medianFreq = identifyFrequencies[Math.floor(identifyFrequencies.length / 2)];
    
    feedback.style.color = 'var(--neon-cyan)';
    feedback.innerText = "Matching voice bounds against database...";
    
    sendVocalIdentification(medianFreq);
  } else if (identifyTimeLeft > 0) {
    identifyIntervalId = requestAnimationFrame(runVoiceIdentifyLoop);
  } else {
    // Timed out
    btn.disabled = false;
    btn.innerHTML = '<i class="fa-solid fa-microphone"></i> Hum to Identify Profile';
    feedback.style.color = 'var(--neon-orange)';
    feedback.innerText = "Listening timed out. Please click again and hum a steady note.";
  }
}

async function sendVocalIdentification(frequency) {
  const feedback = document.getElementById('identify-feedback');
  
  try {
    const response = await fetch('/api/users/identify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-User-Id': currentUser ? currentUser.id : '' },
      body: JSON.stringify({ frequency })
    });
    
    if (!response.ok) throw new Error("HTTP error " + response.status);
    const result = await response.json();
    if (result.queued) throw new Error("Request queued offline");
    if (result.error) {
      feedback.style.color = 'var(--neon-orange)';
      feedback.innerText = `Match failed: ${result.error}`;
      return;
    }
    
    if (result.matched) {
      feedback.style.color = 'var(--neon-green)';
      feedback.innerHTML = `<i class="fa-solid fa-circle-check"></i> Welcome back, <strong>${result.user.username}</strong>!<br><span style="font-size: 0.75rem; color: var(--text-muted);">Voice matched within ${result.distance} semitones. Logging in...</span>`;
      
      currentUser = result.user;
      saveCurrentUserLocal(currentUser);
      
      setTimeout(() => {
        updateUIWithUser();
        feedback.style.display = 'none';
      }, 1500);
    }
  } catch(err) {
    console.warn("Offline: Performing vocal identification locally", err);
    // Local offline match matching the server math
    const freqToMidi = (f) => 69 + 12 * Math.log2(f / 440.0);
    const humMidi = freqToMidi(frequency);
    
    const allUsers = dbCache.allUsers;
    const calibratedUsers = allUsers.filter(u => u.voice_type && u.voice_type !== 'Not Calibrated');
    
    if (calibratedUsers.length === 0) {
      feedback.style.color = 'var(--neon-orange)';
      feedback.innerText = "No calibrated offline profiles found. Please register and calibrate first.";
      return;
    }
    
    let closestUser = null;
    let minDistance = Infinity;
    
    calibratedUsers.forEach(user => {
      const minMidi = freqToMidi(user.min_pitch_hz);
      const maxMidi = freqToMidi(user.max_pitch_hz);
      const centerMidi = (minMidi + maxMidi) / 2;
      const distance = Math.abs(humMidi - centerMidi);
      if (distance < minDistance) {
        minDistance = distance;
        closestUser = user;
      }
    });
    
    if (closestUser && minDistance <= 6.0) { // Limit match to within 6 semitones (1/2 octave)
      currentUser = closestUser;
      saveCurrentUserLocal(currentUser);
      feedback.style.color = 'var(--neon-green)';
      feedback.innerHTML = `<i class="fa-solid fa-circle-check"></i> (Offline) Welcome back, <strong>${closestUser.username}</strong>!<br><span style="font-size: 0.75rem; color: var(--text-muted);">Voice matched within ${Math.round(minDistance * 10) / 10} semitones. Logging in...</span>`;
      setTimeout(() => {
        updateUIWithUser();
        feedback.style.display = 'none';
      }, 1500);
    } else {
      feedback.style.color = 'var(--neon-orange)';
      feedback.innerText = "Could not match any vocal range profile offline within 6 semitones.";
    }
  }
}

function updateUIWithUser() {
  document.getElementById('username-display').innerText = currentUser.username;
  document.getElementById('avatar-initial').innerText = currentUser.username.charAt(0).toUpperCase();
  document.getElementById('user-level-val').innerText = currentUser.current_level;
  
  // Show/Hide navigation depending on user calibration status
  if (currentUser.voice_type !== "Not Calibrated") {
    document.getElementById('main-nav').style.display = 'flex';
    document.getElementById('user-badge').style.display = 'flex';
    
    // If we're on the welcome screen, auto-forward to Daily Planner
    if (currentActiveView === 'welcome-view') {
      switchView('planner-view');
    }
  } else {
    document.getElementById('main-nav').style.display = 'none';
    document.getElementById('user-badge').style.display = 'none';
    if (currentActiveView !== 'welcome-view' && currentActiveView !== 'calibration-view') {
      switchView('welcome-view');
    }
  }
}

// Single Page Application routing setup
function setupRouting() {
  document.querySelectorAll('[data-view]').forEach(link => {
    link.addEventListener('click', (e) => {
      const viewId = e.currentTarget.getAttribute('data-view');
      switchView(viewId);
    });
  });

  document.getElementById('logo-btn').addEventListener('click', () => {
    if (currentUser.voice_type !== "Not Calibrated") {
      switchView('dashboard-view');
    } else {
      switchView('welcome-view');
    }
  });

  document.getElementById('dashboard-calibrate-btn').addEventListener('click', () => {
    switchView('calibration-view');
    startCalibrationProcess();
  });

  const switchBtn = document.getElementById('header-switch-profile-btn');
  if (switchBtn) {
    switchBtn.addEventListener('click', () => {
      stopAllActiveLoops();
      switchView('welcome-view');
      document.getElementById('main-nav').style.display = 'none';
      document.getElementById('user-badge').style.display = 'none';
      loadAllUserProfiles();
    });
  }
}

function switchView(viewId, pushHistory = true) {
  // Stop active processes
  stopAllActiveLoops();

  // Deactivate sections
  document.querySelectorAll('.view-section').forEach(view => {
    view.classList.remove('active');
  });

  // Activate target
  const targetView = document.getElementById(viewId);
  if (targetView) {
    targetView.classList.add('active');
    currentActiveView = viewId;
    sessionStorage.setItem('lastView', viewId);
  }

  // Update navigation visual highlight
  document.querySelectorAll('.nav-link').forEach(link => {
    if (link.getAttribute('data-view') === viewId) {
      link.classList.add('active');
    } else {
      link.classList.remove('active');
    }
  });

  // Push history state if requested
  if (pushHistory) {
    try {
      history.pushState({ viewId }, "", '#' + viewId);
    } catch (e) {
      console.warn("history.pushState failed:", e);
    }
  }

  // Populate dynamic dashboard or stats on entry
  if (viewId === 'dashboard-view') {
    renderCurriculumMap();
  } else if (viewId === 'planner-view') {
    renderPracticeCalendar();
  } else if (viewId === 'stats-view') {
    renderStatsHistory();
  } else if (viewId === 'character-view') {
    renderCharacterView();
  } else if (viewId === 'profile-view') {
    renderVocalProfile();
  }
}

function stopAllActiveLoops() {
  if (audio) {
    audio.stop();
  }
  if (game) {
    game.stop();
  }
  if (sheetEngine) {
    sheetEngine.stop();
    const sheetBtn = document.getElementById('sheet-toggle-btn');
    if (sheetBtn) {
      sheetBtn.innerHTML = '<i class="fa-solid fa-microphone"></i> Start Practice';
      sheetBtn.className = 'btn btn-primary';
    }
  }
  if (tunerIntervalId) {
    cancelAnimationFrame(tunerIntervalId);
    tunerIntervalId = null;
  }
  if (warmupIntervalId) {
    cancelAnimationFrame(warmupIntervalId);
    warmupIntervalId = null;
  }
  if (waveformIntervalId) {
    cancelAnimationFrame(waveformIntervalId);
    waveformIntervalId = null;
  }
  if (identifyIntervalId) {
    cancelAnimationFrame(identifyIntervalId);
    identifyIntervalId = null;
    const identifyBtn = document.getElementById('profile-identify-btn');
    if (identifyBtn) {
      identifyBtn.disabled = false;
      identifyBtn.innerHTML = '<i class="fa-solid fa-microphone"></i> Hum to Identify Profile';
    }
  }
  if (studioIsActive) {
    // Stop vocal studio loop
    const toggleBtn = document.getElementById('studio-toggle-btn');
    if (toggleBtn) {
      studioIsActive = false;
      if (studioIntervalId) {
        cancelAnimationFrame(studioIntervalId);
        studioIntervalId = null;
      }
      toggleBtn.innerHTML = '<i class="fa-solid fa-microphone"></i> Start Practice';
      toggleBtn.classList.remove('btn-accent');
      toggleBtn.classList.add('btn-primary');
    }
  }
  
  // Reset Tuner buttons
  const tunerBtn = document.getElementById('tuner-toggle-btn');
  if (tunerBtn) {
    tunerBtn.innerHTML = '<i class="fa-solid fa-power-off"></i> Start Tuner';
    tunerBtn.classList.remove('btn-accent');
    tunerBtn.classList.add('btn-primary');
  }

  // Hide floating exit freeplay button
  const exitFreeplayBtn = document.getElementById('game-exit-freeplay-btn');
  if (exitFreeplayBtn) {
    exitFreeplayBtn.style.display = 'none';
  }
}

// 1. Setup Welcome Profile Setup Form
function setupProfileForm() {
  const form = document.getElementById('profile-form');
  const selectBtn = document.getElementById('profile-select-btn');
  const profileSelect = document.getElementById('user-profile-select');
  const identifyBtn = document.getElementById('profile-identify-btn');
   // Submit profile creation
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const username = document.getElementById('username-input').value;
    const voicePreference = document.getElementById('voice-type-select').value;
    
    let newUser;
    try {
      // 1. Call Backend to create the profile row
      const createRes = await fetch('/api/users/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-User-Id': currentUser ? currentUser.id : '' },
        body: JSON.stringify({ username })
      });
      if (!createRes.ok) throw new Error("HTTP error " + createRes.status);
      newUser = await createRes.json();
      if (newUser.queued) throw new Error("Request queued offline");
      if (newUser.error) {
        alert("Error creating profile: " + newUser.error);
        return;
      }
    } catch (err) {
      console.warn("Offline: Creating mock user profile locally", err);
      newUser = {
        id: crypto.randomUUID ? crypto.randomUUID() : 'offline_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9), isOfflineProfile: true, // Unique local UUID
        username: username,
        voice_type: 'Not Calibrated',
        min_pitch_hz: 80.0,
        max_pitch_hz: 400.0,
        current_level: 1,
        total_score: 0
      };
      
      let allUsers = dbCache.allUsers;
      allUsers.push(newUser);
      saveAllUsersLocal(allUsers);
    }

    currentUser = newUser;
    saveCurrentUserLocal(currentUser);

    // 2. Set preset ranges or go to Calibration view
    if (voicePreference === 'Custom') {
      switchView('calibration-view');
      startCalibrationProcess();
    } else {
      // Apply standard ranges
      let minHz = 82.4; // E2 (Bass)
      let maxHz = 329.6; // E4
      
      if (voicePreference === 'Tenor') {
        minHz = 130.8; // C3
        maxHz = 523.3; // C5
      } else if (voicePreference === 'Alto') {
        minHz = 174.6; // F3
        maxHz = 698.5; // F5
      } else if (voicePreference === 'Soprano') {
        minHz = 261.6; // C4
        maxHz = 1046.5; // C6
      }
      
      currentUser.voice_type = voicePreference;
      currentUser.min_pitch_hz = minHz;
      currentUser.max_pitch_hz = maxHz;
      
      await saveUserProfile();
      updateUIWithUser();
    }
  });

  // Select existing profile
  if (selectBtn) {
    selectBtn.addEventListener('click', async () => {
      const selectedId = profileSelect.value;
      if (!selectedId) return;

      try {
        const selectRes = await fetch('/api/users/select', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-User-Id': currentUser ? currentUser.id : '' },
          body: JSON.stringify({ id: parseInt(selectedId) })
        });
        if (!selectRes.ok) throw new Error("HTTP error " + selectRes.status);
        const selectedUser = await selectRes.json();
        if (selectedUser.queued) throw new Error("Request queued offline");
        if (selectedUser.error) {
          alert("Error selecting profile: " + selectedUser.error);
          return;
        }

        currentUser = selectedUser;
        saveCurrentUserLocal(currentUser);
        updateUIWithUser();
      } catch (err) {
        console.warn("Offline: Selecting user profile locally", err);
        const allUsers = dbCache.allUsers;
        const found = allUsers.find(u => u.id == selectedId);
        if (found) {
          currentUser = found;
          saveCurrentUserLocal(currentUser);
          updateUIWithUser();
        } else {
          alert("Failed to find selected profile locally offline.");
        }
      }
    });
  }

  // Voice Identification Matching ("Who Am I?")
  if (identifyBtn) {
    identifyBtn.addEventListener('click', () => {
      startVoiceIdentification();
    });
  }
}

// Send profile update to backend Express DB with local storage cache
async function saveUserProfile() {
  saveCurrentUserLocal(currentUser);
  
  // Also update this profile in the allUsers list
  let allUsers = dbCache.allUsers;
  const index = allUsers.findIndex(u => u.id === currentUser.id);
  if (index !== -1) {
    allUsers[index] = currentUser;
  } else {
    allUsers.push(currentUser);
  }
  saveAllUsersLocal(allUsers);

  try {
    await fetch('/api/user', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-User-Id': currentUser ? currentUser.id : '' },
      body: JSON.stringify({
        voice_type: currentUser.voice_type,
        min_pitch_hz: currentUser.min_pitch_hz,
        max_pitch_hz: currentUser.max_pitch_hz,
        total_score: currentUser.total_score
      })
    });
  } catch (err) {
    console.warn("Offline: User profile saved locally. Check backend sync later.");
  }
}

// 2. Setup Interactive Voice Calibration view
let calibrationMidiPoints = [];
let baseMidiCalibrated = 0;
let lowMidiCalibrated = 0;
let highMidiCalibrated = 0;
let isCountingDown = false;
let countdownValue = 3;
let countdownIntervalId = null;
let calibrationTimeLeft = 15;
let calibrationRecordIntervalId = null;
let calibrationHasVoiceStarted = false;
let calibrationLastVoiceTime = 0;

function startCalibrationProcess() {
  calibrationMidiPoints = [];
  baseMidiCalibrated = 0;
  lowMidiCalibrated = 0;
  highMidiCalibrated = 0;
  isCountingDown = false;
  calibrationTimeLeft = 15;
  calibrationHasVoiceStarted = false;
  calibrationLastVoiceTime = 0;
  
  if (countdownIntervalId) clearInterval(countdownIntervalId);
  if (calibrationRecordIntervalId) clearInterval(calibrationRecordIntervalId);
  
  document.getElementById('calibration-step-title').innerText = "Voice Range Finder";
  document.getElementById('calibration-step-desc').innerText = "Click the mic, wait for the 3-second countdown, then free hum for up to 15 seconds. Slide from your lowest note to your highest. We will automatically finish when you stop singing!";
  
  document.getElementById('calib-base-note').innerText = "--";
  document.getElementById('calib-low-note').innerText = "--";
  document.getElementById('calib-high-note').innerText = "--";
  document.getElementById('calibration-pitch-display').innerText = "--";
  
  const progressBar = document.getElementById('calibration-progress-bar');
  if (progressBar) progressBar.style.width = '0%';
  
  document.getElementById('calib-next-btn').style.display = 'none';
  document.getElementById('calibration-mic-btn').style.display = 'flex';
}

function setupCalibration() {
  const micBtn = document.getElementById('calibration-mic-btn');
  const nextBtn = document.getElementById('calib-next-btn');
  const progressBar = document.getElementById('calibration-progress-bar');
  
  micBtn.addEventListener('click', async () => {
    if (isCountingDown) return;
    
    if (micBtn.classList.contains('listening')) {
      stopCalibrationListening();
    } else {
      // Start 3-second countdown before listening
      try {
        await audio.init(); // Initialize audio context early so permission prompt is cleared
        
        isCountingDown = true;
        countdownValue = 3;
        micBtn.classList.add('listening');
        micBtn.style.background = 'var(--accent-warning)';
        
        document.getElementById('calibration-pitch-display').innerText = `${countdownValue}...`;
        
        countdownIntervalId = setInterval(() => {
          countdownValue--;
          if (countdownValue > 0) {
            document.getElementById('calibration-pitch-display').innerText = `${countdownValue}...`;
          } else {
            // Start recording immediately
            clearInterval(countdownIntervalId);
            countdownIntervalId = null;
            isCountingDown = false;
            
            document.getElementById('calibration-pitch-display').innerText = "GO! HUM FREELY!";
            document.getElementById('calibration-pitch-display').style.color = 'var(--neon-green)';
            micBtn.style.background = 'var(--accent-magenta)';
            
            // Start 15-second active recording
            calibrationMidiPoints = [];
            calibrationTimeLeft = 15;
            calibrationHasVoiceStarted = false;
            calibrationLastVoiceTime = Date.now();
            
            if (progressBar) progressBar.style.width = '0%';
            
            // Start visual animations/polling
            runCalibrationLoop();
            
            // Start clock timer
            calibrationRecordIntervalId = setInterval(() => {
              calibrationTimeLeft--;
              if (progressBar) {
                progressBar.style.width = `${((15 - calibrationTimeLeft) / 15) * 100}%`;
              }
              
              if (calibrationTimeLeft <= 0) {
                clearInterval(calibrationRecordIntervalId);
                calibrationRecordIntervalId = null;
                finishCalibrationRecording();
              }
            }, 1000);
          }
        }, 1000); // Precise 1-second intervals
        
      } catch (err) {
        alert("Microphone permission required for calibration!");
        isCountingDown = false;
        micBtn.classList.remove('listening');
      }
    }
  });
  
  nextBtn.addEventListener('click', async () => {
    // Completed calibration! Save range profile and finish
    currentUser.min_pitch_hz = audio.midiToFreq(lowMidiCalibrated);
    currentUser.max_pitch_hz = audio.midiToFreq(highMidiCalibrated);
    
    const semitonesSpan = highMidiCalibrated - lowMidiCalibrated;
    let voiceClass = "Baritone";
    if (lowMidiCalibrated < 45) voiceClass = "Bass";
    else if (lowMidiCalibrated < 57) voiceClass = "Tenor";
    else if (lowMidiCalibrated < 62) voiceClass = "Alto";
    else voiceClass = "Soprano";
    
    currentUser.voice_type = `${voiceClass} (${semitonesSpan} semitones)`;
    
    await saveUserProfile();
    updateUIWithUser();
    switchView('dashboard-view');
  });
}

function finishCalibrationRecording() {
  stopCalibrationListening();
  
  if (calibrationMidiPoints.length < 8) {
    document.getElementById('calibration-pitch-display').innerText = "No voice heard!";
    document.getElementById('calibration-step-desc').innerText = "We couldn't capture enough clean hums. Make sure you are humming close to the mic and click start again.";
    
    document.getElementById('calibration-mic-btn').style.display = 'flex';
    document.getElementById('calib-next-btn').style.display = 'none';
    return;
  }

  // Calculate final bounds using robust percentiles (eliminates audio glitch squeaks)
  calibrationMidiPoints.sort((a,b) => a - b);
  const len = calibrationMidiPoints.length;
  
  lowMidiCalibrated = calibrationMidiPoints[Math.floor(len * 0.05)]; // 5th percentile
  highMidiCalibrated = calibrationMidiPoints[Math.floor(len * 0.95)]; // 95th percentile
  baseMidiCalibrated = calibrationMidiPoints[Math.floor(len * 0.50)]; // 50th percentile (median)

  // Safeguard octave range
  if (highMidiCalibrated <= lowMidiCalibrated) {
    highMidiCalibrated = lowMidiCalibrated + 12;
  }

  // Update final UI texts
  document.getElementById('calib-base-note').innerText = audio.midiToNoteName(baseMidiCalibrated);
  document.getElementById('calib-low-note').innerText = audio.midiToNoteName(lowMidiCalibrated);
  document.getElementById('calib-high-note').innerText = audio.midiToNoteName(highMidiCalibrated);

  // Classify vocal type
  const semitonesSpan = highMidiCalibrated - lowMidiCalibrated;
  let voiceClass = "Baritone";
  if (lowMidiCalibrated < 45) voiceClass = "Bass";
  else if (lowMidiCalibrated < 57) voiceClass = "Tenor";
  else if (lowMidiCalibrated < 62) voiceClass = "Alto / Mezzo";
  else voiceClass = "Soprano";

  document.getElementById('calibration-step-title').innerText = "Calibration Complete!";
  document.getElementById('calibration-step-desc').innerText = `We identified you as a ${voiceClass} with a vocal range of ${semitonesSpan} semitones (${audio.midiToNoteName(lowMidiCalibrated)} to ${audio.midiToNoteName(highMidiCalibrated)}).`;
  document.getElementById('calibration-pitch-display').innerText = voiceClass;
  document.getElementById('calibration-pitch-display').style.color = '';

  // Swap buttons
  document.getElementById('calibration-mic-btn').style.display = 'none';
  const nextBtn = document.getElementById('calib-next-btn');
  nextBtn.innerText = "Save & Finish";
  nextBtn.style.display = 'block';
}

function stopCalibrationListening() {
  const micBtn = document.getElementById('calibration-mic-btn');
  micBtn.classList.remove('listening');
  micBtn.style.background = '';
  
  if (countdownIntervalId) {
    clearInterval(countdownIntervalId);
    countdownIntervalId = null;
  }
  if (calibrationRecordIntervalId) {
    clearInterval(calibrationRecordIntervalId);
    calibrationRecordIntervalId = null;
  }
  isCountingDown = false;
  
  if (warmupIntervalId) {
    cancelAnimationFrame(warmupIntervalId);
    warmupIntervalId = null;
  }
}

function runCalibrationLoop() {
  if (!micBtnIsListening()) return;
  
  const pitch = audio.detectPitch();
  if (pitch.frequency > 0 && pitch.midi > 20 && pitch.midi < 100) {
    document.getElementById('calibration-pitch-display').innerText = pitch.note;
    document.getElementById('calibration-pitch-display').style.color = 'var(--neon-green)';
    calibrationMidiPoints.push(pitch.midi);
    
    calibrationHasVoiceStarted = true;
    calibrationLastVoiceTime = Date.now();
    
    // Live update range metrics boxes as user hums
    if (calibrationMidiPoints.length >= 5) {
      const tempPoints = [...calibrationMidiPoints].sort((a,b) => a - b);
      const len = tempPoints.length;
      
      const liveLow = tempPoints[Math.floor(len * 0.05)];
      const liveHigh = tempPoints[Math.floor(len * 0.95)];
      const liveBase = tempPoints[Math.floor(len * 0.50)];
      
      document.getElementById('calib-base-note').innerText = audio.midiToNoteName(liveBase);
      document.getElementById('calib-low-note').innerText = audio.midiToNoteName(liveLow);
      document.getElementById('calib-high-note').innerText = audio.midiToNoteName(liveHigh);
    }
  } else {
    if (!calibrationHasVoiceStarted) {
      document.getElementById('calibration-pitch-display').innerText = "Sing Now...";
      document.getElementById('calibration-pitch-display').style.color = '';
    } else {
      document.getElementById('calibration-pitch-display').innerText = "Listening...";
      document.getElementById('calibration-pitch-display').style.color = 'var(--neon-cyan)';
      
      // Auto-stop if silent for more than 1.5 seconds
      if (Date.now() - calibrationLastVoiceTime > 1500) {
        finishCalibrationRecording();
        return;
      }
    }
  }
  
  warmupIntervalId = requestAnimationFrame(runCalibrationLoop);
}

function micBtnIsListening() {
  const micBtn = document.getElementById('calibration-mic-btn');
  return micBtn && micBtn.classList.contains('listening') && !isCountingDown;
}

// 3. Render Curriculum Dashboard dynamically
let localCurriculumLevels = [];

async function loadCurriculum() {
  try {
    const res = await fetch('/js/curriculum.json');
    if (res.ok) {
      localCurriculumLevels = await res.json();
      console.log(`Curriculum: Loaded ${localCurriculumLevels.length} levels dynamically.`);
    } else {
      throw new Error("HTTP " + res.status);
    }
  } catch (err) {
    console.error("Curriculum: Failed to load curriculum JSON:", err);
  }
}

function drawCurriculumContainer(levels, history, user) {
  const container = document.getElementById('curriculum-map-container');
  if (!container) return;
  container.innerHTML = '';
  
  levels.forEach(lvl => {
    const isLocked = lvl.id > user.current_level;
    const historyRecord = history.find(h => h.level_id === lvl.id);
    
    const nodeWrapper = document.createElement('div');
    nodeWrapper.className = `curriculum-node-wrapper`;
    
    let starsHtml = '';
    if (historyRecord) {
      for (let i = 0; i < 3; i++) {
        if (i < historyRecord.stars_earned) starsHtml += '<i class="fa-solid fa-star"></i>';
        else starsHtml += '<i class="fa-regular fa-star"></i>';
      }
    }

    nodeWrapper.innerHTML = `
      <div class="curriculum-node ${isLocked ? 'locked' : ''} ${lvl.id === user.current_level ? 'active-node' : ''}" data-level-id="${lvl.id}">
        <div class="node-icon">
          <i class="fa-solid ${getLevelIconClass(lvl.skill)}"></i>
        </div>
        <div class="node-details">
          <div class="node-milestone">Level ${lvl.milestoneLevel || lvl.milestone || lvl.id} • ${lvl.difficulty}</div>
          <h3>${lvl.name}</h3>
          <p>${lvl.description}</p>
          ${starsHtml ? `<div class="stars-rating">${starsHtml} <span style="color: var(--text-muted); font-size: 0.75rem; margin-left: 0.5rem;">Score: ${historyRecord.score}</span></div>` : ''}
        </div>
      </div>
    `;
    
    if (!isLocked) {
      nodeWrapper.querySelector('.curriculum-node').addEventListener('click', () => {
        launchGameLevel(lvl);
      });
    }
    
    container.appendChild(nodeWrapper);
  });
}

async function renderCurriculumMap() {
  const container = document.getElementById('curriculum-map-container');
  if (!container) return;
  container.innerHTML = '<div class="text-center" style="color: var(--text-muted);">Loading Curriculum Tree...</div>';
  
  try {
    const levelsRes = await fetch('/api/levels');
    if (!levelsRes.ok) throw new Error("Failed to load levels");
    const levels = await levelsRes.json();
    if (levels.queued || !Array.isArray(levels)) throw new Error("Invalid levels response");
    
    const userRes = await fetch('/api/user', { headers: { 'X-User-Id': currentUser ? currentUser.id : '' } });
    if (!userRes.ok) throw new Error("Failed to load user profile");
    const user = await userRes.json();
    if (user.queued || !user.username) throw new Error("Invalid user profile");
    currentUser = user;
    
    const statsRes = await fetch('/api/stats?limit=100', { headers: { 'X-User-Id': currentUser ? currentUser.id : '' } });
    if (!statsRes.ok) throw new Error("Failed to load stats history");
    const history = await statsRes.json();
    if (history.queued || !Array.isArray(history)) throw new Error("Invalid stats history response");
    await saveCachedStatsLocal(history);

    drawCurriculumContainer(levels, history, currentUser);
  } catch (err) {
    console.warn("Offline: loading curriculum map from local storage fallback", err);
    
    const user = currentUser;
    const history = dbCache.cachedStats;
    const levels = localCurriculumLevels;
    drawCurriculumContainer(levels, history, user);
  }
}

function getLevelIconClass(skill) {
  switch(skill) {
    case "Pitch Matching": return "fa-music";
    case "Breath Stability": return "fa-compass";
    case "Pitch Glides": return "fa-arrow-trend-up";
    case "Interval Jumping": return "fa-arrows-up-to-line";
    case "Vocal Agility": return "fa-rabbit-running";
    case "Vocal Endurance": return "fa-hourglass-half";
    case "Vibrato Control": return "fa-wave-square";
    case "Interval Precision": return "fa-bullseye";
    case "Vocal Mastery": return "fa-trophy";
    default: return "fa-microphone";
  }
}

// 4. Setup Chromatic Tuner & Chord Analyzer logic
let tunerMode = 'chromatic'; // 'chromatic' or 'chord'

function setupTuner() {
  const toggleBtn = document.getElementById('tuner-toggle-btn');
  const ledFlat = document.getElementById('tuner-led-flat');
  const ledTuned = document.getElementById('tuner-led-tuned');
  const ledSharp = document.getElementById('tuner-led-sharp');
  const needle = document.getElementById('tuner-needle');
  const noteDisplay = document.getElementById('tuner-note-val');
  const centsDisplay = document.getElementById('tuner-cents-val');
  const hzDisplay = document.getElementById('tuner-hz-val');
  
  const modeChromaticBtn = document.getElementById('tuner-mode-chromatic-btn');
  const modeChordBtn = document.getElementById('tuner-mode-chord-btn');
  
  const monophonicPanel = document.getElementById('tuner-monophonic-panel');
  const polyphonicPanel = document.getElementById('tuner-polyphonic-panel');
  const descChromatic = document.getElementById('tuner-control-desc-chromatic');
  const descChord = document.getElementById('tuner-control-desc-chord');
  
  modeChromaticBtn.addEventListener('click', () => {
    tunerMode = 'chromatic';
    modeChromaticBtn.className = 'btn btn-primary btn-sm';
    modeChordBtn.className = 'btn btn-secondary btn-sm';
    monophonicPanel.style.display = 'flex';
    polyphonicPanel.style.display = 'none';
    descChromatic.style.display = 'block';
    descChord.style.display = 'none';
    
    if (audio.analyser) {
      audio.analyser.fftSize = 2048;
    }
  });
  
  modeChordBtn.addEventListener('click', () => {
    tunerMode = 'chord';
    modeChromaticBtn.className = 'btn btn-secondary btn-sm';
    modeChordBtn.className = 'btn btn-primary btn-sm';
    monophonicPanel.style.display = 'none';
    polyphonicPanel.style.display = 'flex';
    descChromatic.style.display = 'none';
    descChord.style.display = 'block';
    
    if (audio.analyser) {
      audio.analyser.fftSize = 4096;
    }
  });

  toggleBtn.addEventListener('click', async () => {
    if (toggleBtn.classList.contains('btn-accent')) {
      // Stop tuner
      stopAllActiveLoops();
    } else {
      // Start tuner
      stopAllActiveLoops();
      try {
        await audio.init();
        toggleBtn.innerHTML = '<i class="fa-solid fa-square"></i> Stop Tuner';
        toggleBtn.classList.remove('btn-primary');
        toggleBtn.classList.add('btn-accent');
        
        // Ensure proper FFT size on start
        if (tunerMode === 'chord') {
          audio.analyser.fftSize = 4096;
        } else {
          audio.analyser.fftSize = 2048;
        }

        runTunerLoop();
      } catch (e) {
        alert("Microphone stream is required for the tuner & analyzer!");
      }
    }
  });

  function runTunerLoop() {
    if (!toggleBtn.classList.contains('btn-accent')) return;

    if (tunerMode === 'chromatic') {
      const data = audio.detectPitch();
      
      if (data.frequency > 0) {
        noteDisplay.innerText = data.note;
        hzDisplay.innerText = Math.round(data.frequency * 10) / 10 + " Hz";
        
        const cents = data.cents;
        
        // Update live Register and Dynamics Badges in Tuner view
        const tunerReg = document.getElementById('tuner-register-badge');
        const tunerDyn = document.getElementById('tuner-dynamics-badge');
        
        let register = "Chest Voice";
        if (data.midi < 53) register = "Chest (Low)";
        else if (data.midi < 64) register = "Chest Resonance";
        else if (data.midi < 73) register = "Mixed Resonance";
        else if (data.midi < 88) register = "Head / Falsetto";
        else register = "Whistle Register";
        
        if (tunerReg) {
          tunerReg.innerText = `Register: ${register}`;
          if (data.midi < 64) {
            tunerReg.style.color = "var(--neon-green)";
            tunerReg.style.borderColor = "var(--neon-green)";
          } else if (data.midi < 73) {
            tunerReg.style.color = "var(--neon-purple)";
            tunerReg.style.borderColor = "var(--neon-purple)";
          } else {
            tunerReg.style.color = "var(--neon-cyan)";
            tunerReg.style.borderColor = "var(--neon-cyan)";
          }
        }
        
        let dynamics = "Mezzo-Forte (mf)";
        let vol = data.volumePct || 0;
        if (vol < 8) dynamics = "Whisper (pp)";
        else if (vol < 26) dynamics = "Piano (p)";
        else if (vol < 56) dynamics = "Mezzo-Forte (mf)";
        else dynamics = "Forte (f)";
        
        if (tunerDyn) {
          tunerDyn.innerText = `Dynamics: ${dynamics} (${vol}%)`;
          if (vol < 26) {
            tunerDyn.style.color = "var(--neon-cyan)";
            tunerDyn.style.borderColor = "var(--neon-cyan)";
          } else if (vol < 56) {
            tunerDyn.style.color = "var(--neon-purple)";
            tunerDyn.style.borderColor = "var(--neon-purple)";
          } else {
            tunerDyn.style.color = "var(--neon-orange)";
            tunerDyn.style.borderColor = "var(--neon-orange)";
          }
        }
        
        // Visual text
        if (Math.abs(cents) <= 5) {
          centsDisplay.innerText = "In Tune!";
          centsDisplay.className = "tuner-cents-deviation tuned";
          noteDisplay.className = "tuner-note-letter tuned";
          
          ledTuned.classList.add('active');
          ledFlat.classList.remove('active');
          ledSharp.classList.remove('active');
          needle.classList.add('in-tune');
        } else if (cents < -5) {
          centsDisplay.innerText = `${Math.abs(cents)} cents flat`;
          centsDisplay.className = "tuner-cents-deviation flat";
          noteDisplay.className = "tuner-note-letter";
          
          ledFlat.classList.add('active');
          ledTuned.classList.remove('active');
          ledSharp.classList.remove('active');
          needle.classList.remove('in-tune');
        } else {
          centsDisplay.innerText = `${cents} cents sharp`;
          centsDisplay.className = "tuner-cents-deviation sharp";
          noteDisplay.className = "tuner-note-letter";
          
          ledSharp.classList.add('active');
          ledTuned.classList.remove('active');
          ledFlat.classList.remove('active');
          needle.classList.remove('in-tune');
        }

        // Rotate needle: clamp to max ±45 degrees (which translates to ±50 cents)
        const degrees = Math.max(-45, Math.min(45, cents * 0.9));
        needle.style.transform = `rotate(${degrees}deg)`;
      } else {
        // Clear
        noteDisplay.innerText = "--";
        centsDisplay.innerText = "-- cents";
        centsDisplay.className = "tuner-cents-deviation";
        noteDisplay.className = "tuner-note-letter";
        hzDisplay.innerText = "-- Hz";
        
        const tunerReg = document.getElementById('tuner-register-badge');
        const tunerDyn = document.getElementById('tuner-dynamics-badge');
        if (tunerReg) tunerReg.innerText = "Register: --";
        if (tunerDyn) tunerDyn.innerText = "Dynamics: --";
        
        ledFlat.classList.remove('active');
        ledTuned.classList.remove('active');
        ledSharp.classList.remove('active');
        needle.classList.remove('in-tune');
        needle.style.transform = `rotate(0deg)`;
      }
    } else {
      // Chord mode
      const chordData = audio.getDetectedChord();
      const chordNameVal = document.getElementById('tuner-chord-name-val');
      const chordNotesList = document.getElementById('tuner-chord-notes-list');
      
      chordNameVal.innerText = chordData.chordName;
      
      if (chordData.notes.length > 0) {
        chordNotesList.innerHTML = chordData.notes.map(note => 
          `<span style="background: rgba(0, 242, 254, 0.12); border: 1px solid var(--neon-cyan); padding: 0.35rem 0.9rem; border-radius: 20px; font-family: var(--font-mono); font-size: 0.95rem; font-weight: bold; color: var(--neon-cyan); text-shadow: var(--shadow-neon-cyan);">${note}</span>`
        ).join('');
      } else {
        chordNotesList.innerHTML = `<span style="background: rgba(255,255,255,0.02); border: 1px solid var(--border-glass); padding: 0.3rem 0.8rem; border-radius: 20px; font-family: var(--font-mono); font-size: 0.9rem; color: var(--text-muted);">No notes</span>`;
      }
    }

    tunerIntervalId = requestAnimationFrame(runTunerLoop);
  }
}

// 5. Setup Vocal Warmups View
function setupWarmups() {
  document.querySelectorAll('.start-warmup-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const type = e.currentTarget.getAttribute('data-warmup');
      launchWarmupChallenge(type);
    });
  });
}

function launchWarmupChallenge(type) {
  let warmupNotes = [];
  let name = "";
  
  // Safe default boundaries in case user or audio engine properties are uninitialized
  let minMidi = 48; // C3
  let maxMidi = 72; // C5
  
  try {
    const minHz = (currentUser && currentUser.min_pitch_hz) ? currentUser.min_pitch_hz : 80.0;
    const maxHz = (currentUser && currentUser.max_pitch_hz) ? currentUser.max_pitch_hz : 400.0;
    
    if (audio && typeof audio.freqToMidi === 'function') {
      minMidi = Math.round(audio.freqToMidi(minHz)) || 48;
      maxMidi = Math.round(audio.freqToMidi(maxHz)) || 72;
    }
  } catch (err) {
    console.error("Error computing pitch boundaries for warmup:", err);
  }
  
  const midMidi = Math.round((minMidi + maxMidi) / 2);
  
  if (type === 'siren') {
    name = "Siren Slide Warmup";
    // Generate scrolling slide note blocks matching a sine wave from min note to high note
    for (let i = 0; i < 15; i++) {
      const progressRatio = i / 14;
      const angle = progressRatio * Math.PI * 2;
      const midiOffset = Math.round(Math.sin(angle - Math.PI/2) * (maxMidi - minMidi) / 2.5);
      const targetMidi = midMidi + midiOffset;
      warmupNotes.push({
        midi: targetMidi,
        time: 1.0 + (i * 0.7),
        duration: 0.6,
        name: `~`
      });
    }
  } else if (type === 'humming') {
    name = "Humming Stability Warmup";
    // Sustain single pitch in center of range
    warmupNotes = [
      { midi: midMidi, time: 1.0, duration: 6.0, name: "Hum..." }
    ];
  } else if (type === 'registers') {
    name = "Register Break Explorer";
    // Stepping notes to identify chest/head break
    const quarterStep = Math.round((maxMidi - minMidi) * 0.25);
    warmupNotes = [
      { midi: minMidi + quarterStep, time: 1.0, duration: 2.0, name: "Chest Register" },
      { midi: midMidi, time: 3.5, duration: 2.0, name: "Mixed Resonance" },
      { midi: maxMidi - quarterStep, time: 6.0, duration: 2.0, name: "Head Register" }
    ];
  }

  // Create virtual level and launch game
  const virtualLevel = {
    id: -99, // negative values represent practice/warmups (not saved to database)
    name: name,
    description: "Practice your resonance placement",
    notes: warmupNotes
  };
  
  launchGameLevel(virtualLevel);
}

// 6. Setup Karaoke MIDI File Uploader
let importedMidiNotes = null;

function setupKaraokeUploader() {
  const dropzone = document.getElementById('midi-dropzone');
  const fileInput = document.getElementById('midi-file-input');
  
  dropzone.addEventListener('click', () => fileInput.click());
  
  dropzone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropzone.style.borderColor = 'var(--neon-cyan)';
    dropzone.style.background = 'rgba(0, 242, 254, 0.04)';
  });
  
  dropzone.addEventListener('dragleave', () => {
    dropzone.style.borderColor = 'var(--border-glass)';
    dropzone.style.background = 'transparent';
  });

  dropzone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropzone.style.borderColor = 'var(--border-glass)';
    dropzone.style.background = 'transparent';
    if (e.dataTransfer.files.length > 0) {
      handleMidiFile(e.dataTransfer.files[0]);
    }
  });

  fileInput.addEventListener('change', (e) => {
    if (e.target.files.length > 0) {
      handleMidiFile(e.target.files[0]);
    }
  });

  // Karaoke Preloaded Presets buttons
  document.querySelectorAll('.play-preset-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const presetId = e.currentTarget.getAttribute('data-preset');
      const songNotes = presetSongs[presetId];
      if (songNotes) {
        // Create custom level
        const nameMap = {
          'happy-birthday': 'Happy Birthday (Karaoke)',
          'twinkle': 'Twinkle Twinkle (Karaoke)',
          'amazing-grace': 'Amazing Grace (Karaoke)',
          'ode-to-joy': 'Ode to Joy (Karaoke)',
          'row-boat': 'Row Your Boat (Karaoke)',
          'london-bridge': 'London Bridge (Karaoke)',
          'frere-jacques': 'Frère Jacques (Karaoke)',
          'scarborough-fair': 'Scarborough Fair (Karaoke)',
          'danny-boy': 'Danny Boy (Karaoke)',
          'la-cucaracha': 'La Cucaracha (Karaoke)',
          'red-river': 'Red River Valley (Karaoke)',
          'auld-lang-syne': 'Auld Lang Syne (Karaoke)'
        };
        const levelData = {
          id: -100, // Custom Karaoke
          name: nameMap[presetId],
          notes: JSON.parse(JSON.stringify(songNotes)) // copy note structure
        };
        
        launchGameLevel(levelData);
      }
    });
  });
}

function handleMidiFile(file) {
  const reader = new FileReader();
  reader.onload = function(e) {
    try {
      const midiNotes = window.MidiParser.parse(e.target.result);
      console.log("Successfully parsed MIDI notes:", midiNotes.length);
      
      importedMidiNotes = midiNotes;
      
      const levelData = {
        id: -100, // Custom Karaoke
        name: file.name.replace(/\.[^/.]+$/, "") + " (Karaoke)",
        notes: midiNotes
      };
      
      launchGameLevel(levelData);
    } catch(err) {
      alert("Error parsing MIDI file. Make sure it is a valid Standard Format 0/1 MIDI: " + err.message);
    }
  };
  reader.readAsArrayBuffer(file);
}

// 7. Launch Level & Bind Audio Live Streams
async function launchGameLevel(lvl) {
  switchView('game-view');
  
  // Set HUD Title
  document.getElementById('game-task-name').innerText = lvl.name;
  
  // Clean HUD fields
  document.getElementById('game-accuracy-val').innerText = "0%";
  document.getElementById('game-score-val').innerText = "0";
  document.getElementById('game-note-val').innerText = "--";
  document.getElementById('game-stars-val').innerHTML = '<i class="fa-regular fa-star"></i><i class="fa-regular fa-star"></i><i class="fa-regular fa-star"></i>';
  
  // Reset Live inputs visualization canvas
  const visualizerCanvas = document.getElementById('waveform-canvas');
  const vCtx = visualizerCanvas.getContext('2d');
  vCtx.clearRect(0,0, visualizerCanvas.width, visualizerCanvas.height);

  // Overlay screen activation
  const overlay = document.getElementById('game-start-overlay');
  const overlayTitle = document.getElementById('game-title-overlay');
  const overlayDesc = document.getElementById('game-desc-overlay');
  const startBtn = document.getElementById('game-start-btn');
  const freeplayBtn = document.getElementById('game-freeplay-btn');
  const exitFreeplayBtn = document.getElementById('game-exit-freeplay-btn');
  
  overlayTitle.innerText = lvl.name;
  overlayDesc.innerText = lvl.description || "Warmup your voice and target the highlighted scrolling channels.";
  overlay.style.display = 'flex';
  exitFreeplayBtn.style.display = 'none';

  // Handle custom backing tracks if in Karaoke mode
  const backingAudioInput = document.getElementById('mp3-file-input');
  const backingFile = (lvl.id === -100 && backingAudioInput && backingAudioInput.files.length > 0) 
    ? backingAudioInput.files[0] 
    : null;

  let levelNotes = [];
  let userMinMidi = 48;
  let userMaxMidi = 72;

  const prepareLevelData = async () => {
    await audio.init();
    levelNotes = JSON.parse(JSON.stringify(lvl.notes || getStandardNotesForLevel(lvl)));
    
    const minHz = (currentUser && currentUser.min_pitch_hz) ? currentUser.min_pitch_hz : 80.0;
    const maxHz = (currentUser && currentUser.max_pitch_hz) ? currentUser.max_pitch_hz : 400.0;
    
    if (audio && typeof audio.freqToMidi === 'function') {
      userMinMidi = Math.round(audio.freqToMidi(minHz)) || 48;
      userMaxMidi = Math.round(audio.freqToMidi(maxHz)) || 72;
    } else {
      userMinMidi = 48;
      userMaxMidi = 72;
    }
    const userMidMidi = (userMinMidi + userMaxMidi) / 2;
    
    let songMin = 127;
    let songMax = 0;
    levelNotes.forEach(n => {
      if (n.midi < songMin) songMin = n.midi;
      if (n.midi > songMax) songMax = n.midi;
    });
    
    if (levelNotes.length > 0) {
      const songMid = (songMin + songMax) / 2;
      const transposeOffset = Math.round(userMidMidi - songMid);
      
      levelNotes.forEach(n => {
        n.midi += transposeOffset;
        if (!n.name) n.name = audio.midiToNoteName(n.midi);
      });
      console.log(`Transposed song by ${transposeOffset} semitones to fit user bounds: ${userMinMidi} to ${userMaxMidi}`);
    }
  };

  // Click start level
  startBtn.onclick = async () => {
    overlay.style.display = 'none';
    exitFreeplayBtn.style.display = 'none';
    document.getElementById('game-task-name').innerText = lvl.name;
    
    try {
      await prepareLevelData();
      game.start(levelNotes, userMinMidi, userMaxMidi, backingFile, (report) => {
        showVocalReportCard(lvl.id, report);
      }, false);
      
      // Start live waveform canvas drawer
      drawWaveformLoop();
    } catch(err) {
      alert("Microphone connection failed! Please verify settings: " + err.message);
      switchView('dashboard-view');
    }
  };

  if (freeplayBtn) {
    freeplayBtn.onclick = async () => {
      overlay.style.display = 'none';
      exitFreeplayBtn.style.display = 'block';
      document.getElementById('game-task-name').innerText = lvl.name + " (Free Play)";
      
      try {
        await prepareLevelData();
        game.start(levelNotes, userMinMidi, userMaxMidi, backingFile, (report) => {
          showVocalReportCard(lvl.id, report);
        }, true); // isFreePlay = true
        
        drawWaveformLoop();
      } catch(err) {
        alert("Microphone connection failed! Please verify settings: " + err.message);
        switchView('dashboard-view');
      }
    };
  }

  if (exitFreeplayBtn) {
    exitFreeplayBtn.onclick = async () => {
      stopAllActiveLoops();
      exitFreeplayBtn.style.display = 'none';
      document.getElementById('game-task-name').innerText = lvl.name;
      
      try {
        await prepareLevelData();
        game.start(levelNotes, userMinMidi, userMaxMidi, backingFile, (report) => {
          showVocalReportCard(lvl.id, report);
        }, false); // isFreePlay = false
        
        drawWaveformLoop();
      } catch(err) {
        alert("Microphone connection failed! Please verify settings: " + err.message);
        switchView('dashboard-view');
      }
    };
  }
}

function getStandardNotesForLevel(lvl) {
  // Translate properties of levels standard arrays
  if (lvl.type === 'hold') {
    return [{ midi: lvl.targetMidi, time: 1.0, duration: lvl.durationMs/1000, name: lvl.targetNote }];
  } else if (lvl.type === 'glide') {
    // Generate steps representing the glide curve
    const steps = [];
    const stepCount = 10;
    const duration = lvl.durationMs / 1000;
    for (let i = 0; i < stepCount; i++) {
      const t = i / (stepCount - 1);
      const midi = Math.round(lvl.startMidi + t * (lvl.endMidi - lvl.startMidi));
      steps.push({
        midi: midi,
        time: 1.0 + t * duration * 0.8,
        duration: (duration * 0.8) / stepCount,
        name: i === 0 ? "Start" : (i === stepCount-1 ? "End" : "~")
      });
    }
    return steps;
  } else if (lvl.type === 'interval' || lvl.type === 'run' || lvl.type === 'mastery') {
    const steps = [];
    const noteDur = (lvl.durationMs / 1000) / lvl.notes.length;
    lvl.notes.forEach((note, idx) => {
      steps.push({
        midi: note,
        time: 1.0 + idx * noteDur,
        duration: noteDur * 0.9,
        name: audio.midiToNoteName(note)
      });
    });
    return steps;
  } else if (lvl.type === 'vibrato') {
    return [{ midi: lvl.targetMidi, time: 1.0, duration: lvl.durationMs/1000, name: lvl.targetNote + " ~" }];
  }
  return [];
}

// Draw oscilloscope waveform below game canvas
function drawWaveformLoop() {
  if (!game.isActive) return;

  const canvas = document.getElementById('waveform-canvas');
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  
  // Adapt container widths
  const rect = canvas.getBoundingClientRect();
  canvas.width = rect.width * dpr;
  canvas.height = rect.height * dpr;
  ctx.scale(dpr, dpr);
  
  const w = rect.width;
  const h = rect.height;

  ctx.fillStyle = '#06060c';
  ctx.fillRect(0, 0, w, h);

  const data = audio.getWaveform();
  if (data) {
    ctx.lineWidth = 1.5;
    ctx.strokeStyle = '#00f2fe';
    
    // Draw centered horizontal line
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.05)';
    ctx.beginPath();
    ctx.moveTo(0, h/2);
    ctx.lineTo(w, h/2);
    ctx.stroke();
    
    ctx.strokeStyle = '#00f2fe';
    ctx.beginPath();
    const sliceWidth = w / data.length;
    let x = 0;
    
    for (let i = 0; i < data.length; i++) {
      const v = data[i] / 128.0;
      const y = (v * h) / 2;
      
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
      
      x += sliceWidth;
    }
    ctx.lineTo(w, h/2);
    ctx.stroke();
  }

  waveformIntervalId = requestAnimationFrame(drawWaveformLoop);
}

// 8. Show Vocal Report Card (Performance Summary)
async function showVocalReportCard(levelId, report) {
  switchView('results-view');
  
  // Set Headers
  const title = document.getElementById('results-title');
  if (levelId === -99) {
    title.innerText = "Warmup Complete!";
  } else if (levelId === -100) {
    title.innerText = "Song Finished!";
  } else {
    title.innerText = `Level ${levelId} Cleared!`;
  }
  
  // Set Score
  document.getElementById('results-score').innerText = report.score;
  document.getElementById('res-metric-accuracy').innerText = report.accuracy + "%";
  document.getElementById('lbl-metric-accuracy').innerText = `Avg deviation: ${report.avgCentsError} cents`;
  
  document.getElementById('res-metric-stability').innerText = report.jitterScore + "%";
  document.getElementById('lbl-metric-stability').innerText = `Pitch jitter: ${Math.round(report.avgCentsError * 0.15 * 10) / 10} Hz`;
  
  document.getElementById('res-metric-agility').innerText = report.agilityScore + "%";
  document.getElementById('lbl-metric-agility').innerText = `Note transition: ${report.avgTransitionMs}ms`;
  
  const vibratoVal = document.getElementById('res-metric-vibrato');
  const vibratoLbl = document.getElementById('lbl-metric-vibrato');
  if (report.vibratoScore > 0) {
    vibratoVal.innerText = report.vibratoScore + "%";
    vibratoLbl.innerText = `${Math.round(report.vibratoHz * 10) / 10}Hz @ ±${report.vibratoDepthCents}c`;
    document.getElementById('card-metric-vibrato').className = "glass-panel metric-card excellent";
  } else {
    vibratoVal.innerText = "N/A";
    vibratoLbl.innerText = "Straight Tone";
    document.getElementById('card-metric-vibrato').className = "glass-panel metric-card";
  }

  // Adjust card colors based on scores
  styleMetricCard('card-metric-accuracy', report.accuracy);
  styleMetricCard('card-metric-stability', report.jitterScore);
  styleMetricCard('card-metric-agility', report.agilityScore);

  // Set Coaching narrative advice
  document.getElementById('res-feedback-text').innerText = report.feedbackText;

  // Set Stars
  let starsStr = '';
  for (let i = 0; i < 3; i++) {
    if (i < report.stars) starsStr += '<i class="fa-solid fa-star"></i>';
    else starsStr += '<i class="fa-regular fa-star"></i>';
  }
  document.getElementById('results-stars').innerHTML = starsStr;

  // Draw Comparison Chart (Canvas drawing of Target Notes vs. Sung Pitch)
  drawResultsChart(game.notes, report.pitchHistory);

  // Bind buttons
  document.getElementById('results-dashboard-btn').onclick = () => {
    switchView('dashboard-view');
  };
  
  document.getElementById('results-retry-btn').onclick = () => {
    // Retry level
    const originalLevel = {
      id: levelId,
      name: document.getElementById('game-task-name').innerText,
      notes: game.notes
    };
    launchGameLevel(originalLevel);
  };

  // POST progress to SQLite database if this is a real level
  if (levelId > 0) {
    try {
      const response = await fetch('/api/progress', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-User-Id': currentUser ? currentUser.id : '' },
        body: JSON.stringify({
          level_id: levelId,
          stars_earned: report.stars,
          score: report.score,
          pitch_accuracy_pct: report.accuracy,
          stability_score: report.jitterScore,
          agility_score: report.agilityScore,
          feedback_text: report.feedbackText,
          notePerformanceData: report.notePerformanceData,
          completed_at: new Date().toISOString()
        })
      });
      if (!response.ok) throw new Error("HTTP error " + response.status);
      const data = await response.json();
      if (data.queued) throw new Error("Request queued offline");
      console.log("Progress saved to database:", data);
      currentUser.current_level = data.unlockedLevel;
    } catch(err) {
      console.warn("Offline: saving level progress to local storage fallback", err);
      
      // Queue for offline sync
      addToOfflineQueueLocal({
        type: 'progress',
        level_id: levelId,
        stars_earned: report.stars,
        score: report.score,
        pitch_accuracy_pct: report.accuracy,
        stability_score: report.jitterScore,
        agility_score: report.agilityScore,
        feedback_text: report.feedbackText,
        notePerformanceData: report.notePerformanceData,
        user_id: currentUser ? currentUser.id : '',
        completed_at: new Date().toISOString()
      });
      
      // Update unlocked level locally
      if (levelId >= currentUser.current_level && levelId < (localCurriculumLevels.length || 100)) {
        currentUser.current_level = levelId + 1;
      }
      currentUser.total_score += report.score;
      saveCurrentUserLocal(currentUser);

      // Cache stats in local storage for stats log and profile timeline
      const cachedStats = dbCache.cachedStats;
      const statRecord = {
        id: Date.now(),
        user_id: currentUser.id,
        level_id: levelId,
        stars_earned: report.stars,
        score: report.score,
        pitch_accuracy_pct: report.accuracy,
        stability_score: report.jitterScore,
        agility_score: report.agilityScore,
        feedback_text: report.feedbackText,
        timestamp: new Date().toISOString(),
        level_name: document.getElementById('game-task-name').innerText,
        skill: 'Singing'
      };
      cachedStats.unshift(statRecord);
      saveCachedStatsLocal(cachedStats);

      // Save note performance to local storage
      if (report.notePerformanceData && Array.isArray(report.notePerformanceData)) {
        let localNotePerformance = dbCache.localNotePerformance;
        report.notePerformanceData.forEach(item => {
          const existing = localNotePerformance.find(p => p.midi_note === item.midi);
          if (existing) {
            existing.avg_accuracy = Math.round((existing.avg_accuracy + item.accuracy) / 2);
            existing.max_sustain = Math.max(existing.max_sustain, item.sustain);
          } else {
            localNotePerformance.push({
              midi_note: item.midi,
              avg_accuracy: item.accuracy,
              max_sustain: item.sustain
            });
          }
        });
        saveNotePerformanceLocal(localNotePerformance);
      }

      // Add to achievements local checks if appropriate
      let unlockedAchievements = dbCache.unlockedAchievements;
      const checkAndUnlock = (key) => {
        if (!unlockedAchievements.some(a => a.achievement_key === key)) {
          unlockedAchievements.push({
            achievement_key: key,
            unlocked_at: new Date().toISOString()
          });
        }
      };
      if (levelId === 1) checkAndUnlock('first_flight');
      if (report.stars === 3) checkAndUnlock('triple_star');
      if (report.accuracy >= 95) checkAndUnlock('pitch_perfection');
      if ((levelId === 2 || levelId === 6) && report.jitterScore >= 90) checkAndUnlock('iron_lung');
      if (levelId === 5 && report.agilityScore >= 80) checkAndUnlock('agility_runner');
      if (levelId === 7 && report.accuracy >= 80) checkAndUnlock('vibrato_wizard');
      if (levelId >= 5) checkAndUnlock('halfway_there');
      if (levelId === 9) checkAndUnlock('virtuoso');
      saveUnlockedAchievementsLocal(unlockedAchievements);
    }
  }
}

function styleMetricCard(id, score) {
  const card = document.getElementById(id);
  if (score >= 85) card.className = "glass-panel metric-card excellent";
  else if (score >= 60) card.className = "glass-panel metric-card good";
  else card.className = "glass-panel metric-card needs-work";
}

// Draws the target notes (blue channels) overlaid with user pitch tracks (purple dots)
function drawResultsChart(targetNotes, pitchHistory) {
  const canvas = document.getElementById('pitch-graph-canvas');
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  
  const rect = canvas.getBoundingClientRect();
  canvas.width = rect.width * dpr;
  canvas.height = rect.height * dpr;
  ctx.scale(dpr, dpr);
  
  const w = rect.width;
  const h = rect.height;

  // Clear
  ctx.fillStyle = '#06060c';
  ctx.fillRect(0, 0, w, h);

  if (targetNotes.length === 0) return;

  // Determine timeline boundary
  const lastNote = targetNotes[targetNotes.length - 1];
  const maxTime = lastNote.time + lastNote.duration + 1.0;
  
  // Determine MIDI pitch boundary
  let minMidi = 127;
  let maxMidi = 0;
  targetNotes.forEach(n => {
    if (n.midi < minMidi) minMidi = n.midi;
    if (n.midi > maxMidi) maxMidi = n.midi;
  });
  
  // Buffer boundaries slightly
  minMidi = Math.max(0, minMidi - 2);
  maxMidi = Math.min(127, maxMidi + 2);
  const midiRange = maxMidi - minMidi;

  // Helper coordinate mapper
  const getX = (t) => (t / maxTime) * (w - 80) + 40;
  const getY = (m) => h - ((m - minMidi) / midiRange) * (h - 60) - 30;

  // Draw grid lines
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.04)';
  ctx.lineWidth = 1;
  for (let m = minMidi; m <= maxMidi; m++) {
    const yVal = getY(m);
    ctx.beginPath();
    ctx.moveTo(40, yVal);
    ctx.lineTo(w - 40, yVal);
    ctx.stroke();

    // Write note name on vertical axis
    if (m % 2 === 0 || midiRange < 8) {
      ctx.fillStyle = 'rgba(255,255,255,0.2)';
      ctx.font = '10px JetBrains Mono';
      ctx.fillText(audio.midiToNoteName(m), 10, yVal + 3);
    }
  }

  // 1. Draw Target note bars (Blue)
  targetNotes.forEach(n => {
    const xStart = getX(n.time);
    const xEnd = getX(n.time + n.duration);
    const yVal = getY(n.midi);
    
    ctx.fillStyle = 'rgba(0, 242, 254, 0.25)';
    ctx.strokeStyle = '#00f2fe';
    ctx.lineWidth = 1.5;
    
    ctx.beginPath();
    ctx.roundRect(xStart, yVal - 8, xEnd - xStart, 16, 5);
    ctx.fill();
    ctx.stroke();
  });

  // 2. Draw User Sung Pitch line (Purple/Pink dots)
  if (pitchHistory.length > 1) {
    ctx.strokeStyle = '#b927fc';
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    
    let isDrawing = false;
    for (let i = 0; i < pitchHistory.length; i++) {
      const pt = pitchHistory[i];
      
      // Prevent connecting lines across silent gaps (e.g. if time gap > 0.35s)
      if (i > 0 && (pt.time - pitchHistory[i-1].time) > 0.35) {
        ctx.stroke();
        isDrawing = false;
      }
      
      const xVal = getX(pt.time);
      const yVal = getY(pt.midi);
      
      // Clamp to graph boundary
      if (yVal >= 0 && yVal <= h) {
        if (!isDrawing) {
          ctx.beginPath();
          ctx.moveTo(xVal, yVal);
          isDrawing = true;
        } else {
          ctx.lineTo(xVal, yVal);
        }
      }
    }
    if (isDrawing) {
      ctx.stroke();
    }
  }
}

// 9. Fetch historical stats from SQLite (Refactored for Resiliency & Pagination)
async function renderStatsHistory(append = false) {
  const tbody = document.getElementById('history-log-rows');
  const loadMoreBtn = document.getElementById('stats-load-more-btn');
  
  if (!append) {
    statsOffset = 0;
    statsHistoryRows = [];
    tbody.innerHTML = '<tr><td colspan="8" class="text-center">Loading session history...</td></tr>';
    if (loadMoreBtn) loadMoreBtn.style.display = 'none';
  }
  
  try {
    const res = await fetch(`/api/stats?limit=${statsLimit}&offset=${statsOffset}`, { 
      headers: { 'X-User-Id': currentUser ? currentUser.id : '' } 
    });
    
    if (!res.ok) throw new Error("HTTP error " + res.status);
    const rows = await res.json();
    
    if (!append) {
      tbody.innerHTML = '';
      await saveCachedStatsLocal(rows); // Cache first page
    }
    
    renderStatsRows(rows, append);
    
    statsHistoryRows = statsHistoryRows.concat(rows);
    statsOffset += rows.length;
    
    if (loadMoreBtn) {
      // If we loaded exactly the limit size, there are likely more rows left
      if (rows.length === statsLimit) {
        loadMoreBtn.style.display = 'inline-block';
      } else {
        loadMoreBtn.style.display = 'none';
      }
    }
  } catch (err) {
    console.warn("Offline fallback: Loading stats from local IndexedDB cache");
    if (!append) {
      tbody.innerHTML = '';
      const localRows = dbCache.cachedStats;
      renderStatsRows(localRows, false);
      if (loadMoreBtn) loadMoreBtn.style.display = 'none';
    } else {
      if (loadMoreBtn) loadMoreBtn.style.display = 'none';
    }
  }
}

function renderStatsRows(rows, append) {
  const tbody = document.getElementById('history-log-rows');
  if (!append && rows.length === 0) {
    tbody.innerHTML = '<tr><td colspan="8" class="text-center" style="color: var(--text-muted); padding: 2rem;">No levels completed yet. Start training on the Curriculum!</td></tr>';
    return;
  }
  
  rows.forEach(row => {
    const tr = document.createElement('tr');
    const date = new Date(row.timestamp).toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
    
    let starsHtml = '';
    for (let i = 0; i < 3; i++) {
      if (i < row.stars_earned) starsHtml += '<i class="fa-solid fa-star" style="color: var(--neon-orange)"></i>';
      else starsHtml += '<i class="fa-regular fa-star" style="color: var(--text-muted)"></i>';
    }

    tr.innerHTML = `
      <td style="font-family: var(--font-mono); font-size: 0.85rem;">${date}</td>
      <td style="font-weight: 600;">${row.level_name}</td>
      <td><span style="background: rgba(185, 39, 252, 0.1); border: 1px solid rgba(185, 39, 252, 0.2); padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.8rem; color: var(--neon-purple);">${row.skill}</span></td>
      <td>${starsHtml}</td>
      <td style="font-family: var(--font-mono); font-weight: bold;">${row.score}</td>
      <td style="color: var(--neon-green); font-weight: 500;">${Math.round(row.pitch_accuracy_pct)}%</td>
      <td style="color: var(--neon-cyan); font-weight: 500;">${Math.round(row.stability_score)}%</td>
      <td style="font-size: 0.85rem; color: var(--text-secondary); max-width: 300px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;" title="${row.feedback_text}">${row.feedback_text}</td>
    `;
    tbody.appendChild(tr);
  });
}


// 10. Vocal Practice Studio (Sandbox mode with detailed analytics and rolling graph)
let studioIsActive = false;
let studioTime = 0;
let studioPitchPoints = []; // [{ time, midi, targetMidi }]
let studioMatchFrames = 0;
let studioTotalFrames = 0;
let studioCurrentHoldFrames = 0;
let studioMaxHoldFrames = 0;
let studioIntonationDiffs = [];
let studioJitterSum = 0;
let studioIntervalId = null;

function setupVocalStudio() {
  const toggleBtn = document.getElementById('studio-toggle-btn');
  const targetSelect = document.getElementById('studio-target-select');
  const canvas = document.getElementById('studio-timeline-canvas');
  const noteDisplay = document.getElementById('studio-note-val');
  const registerDisplay = document.getElementById('studio-register-val');
  const jitterPct = document.getElementById('studio-jitter-pct');
  const jitterBar = document.getElementById('studio-jitter-bar');
  const centsDisplay = document.getElementById('studio-cents-offset');
  const flatBar = document.getElementById('studio-cents-bar-flat');
  const sharpBar = document.getElementById('studio-cents-bar-sharp');
  
  const avgAccDisplay = document.getElementById('studio-avg-acc');
  const maxHoldDisplay = document.getElementById('studio-max-hold');
  const estJitterDisplay = document.getElementById('studio-est-jitter');
  const feedbackBox = document.getElementById('studio-feedback');
  const feedbackText = document.getElementById('studio-feedback-text');

  // New Isolation Suite selectors and guides
  const focusSelect = document.getElementById('studio-focus-select');
  const paramPitch = document.getElementById('studio-param-pitch');
  const paramChord = document.getElementById('studio-param-chord');
  const paramInterval = document.getElementById('studio-param-interval');
  const paramVibrato = document.getElementById('studio-param-vibrato');
  const modeGuide = document.getElementById('studio-mode-guide');

  // Change listener to show/hide parameters
  focusSelect.addEventListener('change', () => {
    paramPitch.style.display = 'none';
    paramChord.style.display = 'none';
    paramInterval.style.display = 'none';
    paramVibrato.style.display = 'none';
    
    const mode = focusSelect.value;
    if (mode === 'pitch') {
      paramPitch.style.display = 'flex';
      modeGuide.innerText = "Single Pitch Match: Sing and lock onto the target line!";
      modeGuide.style.color = "var(--neon-cyan)";
    } else if (mode === 'chord') {
      paramChord.style.display = 'flex';
      modeGuide.innerText = "Chord Triad Builder: Voice all 3 triad notes (C Major: C - E - G) to clear!";
      modeGuide.style.color = "var(--neon-green)";
    } else if (mode === 'interval') {
      paramInterval.style.display = 'flex';
      modeGuide.innerText = "Interval Leap: Sing C4, then leap to target. Measures agility in ms.";
      modeGuide.style.color = "var(--neon-purple)";
    } else if (mode === 'vibrato') {
      paramVibrato.style.display = 'flex';
      modeGuide.innerText = "Vibrato Control: Sustain the anchor and let pitch wave oscillate.";
      modeGuide.style.color = "var(--neon-orange)";
    }
  });
  
  // Trigger initial change layout
  focusSelect.dispatchEvent(new Event('change'));

  // Wire up audio play guides
  document.getElementById('studio-play-guide-btn').addEventListener('click', () => {
    const midi = parseInt(targetSelect.value);
    if (midi > 0) audio.playGuideTone(midi, 0.8);
  });

  document.getElementById('studio-play-chord-btn').addEventListener('click', () => {
    const chordVal = document.getElementById('studio-chord-select').value;
    let chordNotes = [60, 64, 67];
    if (chordVal === 'G-Maj') chordNotes = [55, 59, 62];
    else if (chordVal === 'A-Min') chordNotes = [57, 60, 64];
    else if (chordVal === 'F-Maj') chordNotes = [53, 57, 60];
    
    chordNotes.forEach((n, idx) => {
      setTimeout(() => {
        audio.playGuideTone(n, 0.6);
      }, idx * 250);
    });
  });

  document.getElementById('studio-play-interval-btn').addEventListener('click', () => {
    const intVal = parseInt(document.getElementById('studio-interval-select').value);
    audio.playGuideTone(60, 0.5);
    setTimeout(() => {
      audio.playGuideTone(60 + intVal, 0.5);
    }, 450);
  });

  document.getElementById('studio-play-vibrato-btn').addEventListener('click', () => {
    const midi = parseInt(document.getElementById('studio-vibrato-select').value);
    audio.playGuideTone(midi, 0.8);
  });

  toggleBtn.addEventListener('click', async () => {
    if (toggleBtn.classList.contains('btn-accent')) {
      stopVocalStudio();
    } else {
      stopAllActiveLoops();
      try {
        await audio.init();
        
        // Reset state
        studioIsActive = true;
        studioTime = 0;
        studioPitchPoints = [];
        studioMatchFrames = 0;
        studioTotalFrames = 0;
        studioCurrentHoldFrames = 0;
        studioMaxHoldFrames = 0;
        studioIntonationDiffs = [];
        studioJitterSum = 0;

        // Mode-specific reset states
        window.studioChordMatchedStatus = [false, false, false];
        window.studioIntervalLeapTimes = [];
        window.studioIntervalLastTransitionStart = null;
        window.studioIntervalWasInRoot = false;
        
        toggleBtn.innerHTML = '<i class="fa-solid fa-square"></i> Stop Practice';
        toggleBtn.classList.remove('btn-primary');
        toggleBtn.classList.add('btn-accent');
        feedbackBox.style.display = 'none';
        
        runStudioLoop();
      } catch (e) {
        alert("Microphone connection required for Vocal Practice Studio!");
      }
    }
  });

  function stopVocalStudio() {
    studioIsActive = false;
    if (studioIntervalId) {
      cancelAnimationFrame(studioIntervalId);
      studioIntervalId = null;
    }
    
    toggleBtn.innerHTML = '<i class="fa-solid fa-microphone"></i> Start Practice';
    toggleBtn.classList.remove('btn-accent');
    toggleBtn.classList.add('btn-primary');
    
    // Compile summary feedback diagnostic report
    if (studioTotalFrames > 0) {
      const accuracy = Math.round((studioMatchFrames / studioTotalFrames) * 100);
      const holdTimeSec = Math.round((studioMaxHoldFrames * 16.66) / 100) / 10;
      
      let avgCentsError = 0;
      if (studioIntonationDiffs.length > 0) {
        const sum = studioIntonationDiffs.reduce((a,b)=>a+b, 0);
        avgCentsError = Math.round(sum / studioIntonationDiffs.length);
      }
      
      const selectedMode = focusSelect.value;
      let msg = "";
      
      if (selectedMode === 'pitch') {
        if (accuracy < 30) {
          msg = `Pitch Match: High variation. Make sure to play the Target Tone to tune your ear. Practice locking onto the reference line. Cents error: ${avgCentsError}c.`;
        } else if (accuracy < 75) {
          msg = `Pitch Match: Good job matching target note! Your average deviation is ${avgCentsError} cents. Try humming to settle key centers and decrease drift.`;
        } else {
          msg = `Pitch Match: Professional intonation! Hitting target note centers with a tiny average error of ${avgCentsError} cents. Peak hold time: ${holdTimeSec}s.`;
        }
      } else if (selectedMode === 'chord') {
        const matchedNotesCount = window.studioChordMatchedStatus.filter(x => x).length;
        const chordVal = document.getElementById('studio-chord-select').value;
        if (matchedNotesCount === 0) {
          msg = `Chord Triad Builder: No chord components matched. Strum or listen to the Arpeggio guide tone, then practice humming notes individually.`;
        } else if (matchedNotesCount < 3) {
          msg = `Chord Triad Builder: Partially matched chord! You voiced ${matchedNotesCount} of 3 notes for ${chordVal}. Keep practicing leaping and supporting your pitch.`;
        } else {
          msg = `Chord Triad Builder: Chord matched perfectly! You successfully voiced all 3 notes (triad) for the ${chordVal} chord. Fantastic intonation agility!`;
        }
      } else if (selectedMode === 'interval') {
        if (window.studioIntervalLeapTimes.length === 0) {
          msg = `Interval Leap Precision: No leaps completed. Settle on the root note C4, then jump/slide up to the selected target interval to check agility.`;
        } else {
          const avgLeap = Math.round(window.studioIntervalLeapTimes.reduce((a,b)=>a+b,0)/window.studioIntervalLeapTimes.length);
          if (avgLeap < 90) {
            msg = `Interval Leap Precision: Elite transition speed! Average leap transition is a rapid ${avgLeap}ms. You transition notes like a professional.`;
          } else {
            msg = `Interval Leap Precision: Good leaps. Average transition speed is ${avgLeap}ms. Focus on matching intervals precisely without sliding or scooping.`;
          }
        }
      } else if (selectedMode === 'vibrato') {
        const stats = getLiveVibratoStats();
        if (stats.vibratoHz > 0) {
          msg = `Vibrato Control: Periodic throat pitch waves detected! Rate: ${Math.round(stats.vibratoHz*10)/10}Hz, depth: ±${Math.round(stats.vibratoDepth)} cents. This is within the professional range of 5-7Hz.`;
        } else {
          msg = `Vibrato Control: No steady throat oscillation detected. Settle on the target note with a straight tone first, then relax your jaw to introduce small cycles.`;
        }
      }
      
      feedbackText.innerText = msg;
      feedbackBox.style.display = 'block';
    }
  }

  function getLiveVibratoStats() {
    const activePts = studioPitchPoints.filter(p => p.midi !== null);
    if (activePts.length < 30) return { vibratoHz: 0, vibratoDepth: 0 };
    
    let peaks = [];
    for (let i = 2; i < activePts.length - 2; i++) {
      const prev = activePts[i - 1].midi;
      const curr = activePts[i].midi;
      const next = activePts[i + 1].midi;
      
      const slope1 = curr - prev;
      const slope2 = next - curr;
      
      if ((slope1 > 0 && slope2 < 0) || (slope1 < 0 && slope2 > 0)) {
        peaks.push(activePts[i]);
      }
    }
    
    if (peaks.length >= 4) {
      let periodSum = 0;
      let ampSum = 0;
      for (let j = 0; j < peaks.length - 1; j++) {
        periodSum += (peaks[j + 1].time - peaks[j].time);
        ampSum += Math.abs(peaks[j + 1].midi - peaks[j].midi);
      }
      
      const avgPeriod = (periodSum / (peaks.length - 1)) * 2;
      const avgAmp = (ampSum / (peaks.length - 1)) * 100;
      
      const hz = 1 / avgPeriod;
      if (hz >= 3.5 && hz <= 8.5 && avgAmp >= 15 && avgAmp <= 180) {
        return { vibratoHz: hz, vibratoDepth: avgAmp };
      }
    }
    return { vibratoHz: 0, vibratoDepth: 0 };
  }

  function runStudioLoop() {
    if (!studioIsActive) return;

    // Detect pitch
    const pitch = audio.detectPitch();
    const mode = focusSelect.value;
    
    studioTime += 0.016; // increment timer roughly at 60fps
    studioTotalFrames++;

    let effectiveTargetMidi = -1;
    let cents = 0;

    // Mode-specific calculations
    if (mode === 'pitch') {
      const targetMidi = parseInt(targetSelect.value);
      effectiveTargetMidi = targetMidi;
      if (pitch.frequency > 0) {
        cents = (targetMidi !== -1) ? audio.getCentsDeviation(pitch.frequency, targetMidi) : pitch.cents;
      }
    } else if (mode === 'chord') {
      const chordVal = document.getElementById('studio-chord-select').value;
      let chordNotes = [60, 64, 67];
      if (chordVal === 'G-Maj') chordNotes = [55, 59, 62];
      else if (chordVal === 'A-Min') chordNotes = [57, 60, 64];
      else if (chordVal === 'F-Maj') chordNotes = [53, 57, 60];
      
      if (pitch.frequency > 0) {
        let closestIndex = -1;
        let minDiff = Infinity;
        
        chordNotes.forEach((n, idx) => {
          const diff = Math.abs(audio.getCentsDeviation(pitch.frequency, n));
          if (diff < minDiff) {
            minDiff = diff;
            closestIndex = idx;
          }
        });
        
        if (closestIndex !== -1 && minDiff <= 40) {
          window.studioChordMatchedStatus[closestIndex] = true;
          effectiveTargetMidi = chordNotes[closestIndex];
          cents = audio.getCentsDeviation(pitch.frequency, effectiveTargetMidi);
          
          const notesText = chordNotes.map(n => audio.midiToNoteName(n)).join(', ');
          const matchedString = chordNotes.map((n, i) => `${audio.midiToNoteName(n)}: ${window.studioChordMatchedStatus[i] ? '✅' : '❌'}`).join(' | ');
          modeGuide.innerText = `Voicing ${chordVal} chord [${notesText}] : ${matchedString}`;
        } else {
          if (closestIndex !== -1) {
            effectiveTargetMidi = chordNotes[closestIndex];
            cents = audio.getCentsDeviation(pitch.frequency, effectiveTargetMidi);
          }
        }
      }
    } else if (mode === 'interval') {
      const intVal = parseInt(document.getElementById('studio-interval-select').value);
      const rootMidi = 60; // C4
      const endMidi = 60 + intVal;
      
      if (pitch.frequency > 0) {
        const rootDiff = Math.abs(audio.getCentsDeviation(pitch.frequency, rootMidi));
        const endDiff = Math.abs(audio.getCentsDeviation(pitch.frequency, endMidi));
        
        if (rootDiff <= 40) {
          window.studioIntervalWasInRoot = true;
          window.studioIntervalLastTransitionStart = studioTime;
          effectiveTargetMidi = rootMidi;
          cents = audio.getCentsDeviation(pitch.frequency, rootMidi);
          modeGuide.innerText = `Leap practice: Stabilized on C4 root. Now jump up to ${audio.midiToNoteName(endMidi)}!`;
        } else if (endDiff <= 40) {
          if (window.studioIntervalWasInRoot && window.studioIntervalLastTransitionStart !== null) {
            const leapDurationMs = Math.round((studioTime - window.studioIntervalLastTransitionStart) * 1000);
            if (leapDurationMs > 40 && leapDurationMs < 1200) {
              window.studioIntervalLeapTimes.push(leapDurationMs);
              modeGuide.innerText = `Leap MATCHED! Transition Speed: ${leapDurationMs}ms. Repeat leaps to track agility.`;
            }
            window.studioIntervalWasInRoot = false;
            window.studioIntervalLastTransitionStart = null;
          } else {
            modeGuide.innerText = `Stabilized on target note. Slide down to C4 root and leap again!`;
          }
          effectiveTargetMidi = endMidi;
          cents = audio.getCentsDeviation(pitch.frequency, endMidi);
        } else {
          if (rootDiff < endDiff) {
            effectiveTargetMidi = rootMidi;
            cents = audio.getCentsDeviation(pitch.frequency, rootMidi);
          } else {
            effectiveTargetMidi = endMidi;
            cents = audio.getCentsDeviation(pitch.frequency, endMidi);
          }
        }
      }
    } else if (mode === 'vibrato') {
      const vibMidi = parseInt(document.getElementById('studio-vibrato-select').value);
      effectiveTargetMidi = vibMidi;
      if (pitch.frequency > 0) {
        cents = audio.getCentsDeviation(pitch.frequency, vibMidi);
        
        if (studioTotalFrames > 30) {
          const stats = getLiveVibratoStats();
          if (stats.vibratoHz > 0) {
            modeGuide.innerText = `Vibrato Active: Rate ${Math.round(stats.vibratoHz*10)/10}Hz | Depth ±${Math.round(stats.vibratoDepth)}c`;
          } else {
            modeGuide.innerText = `Hold steady anchor to measure throat vibrato waves...`;
          }
        }
      }
    }

    if (pitch.frequency > 0) {
      noteDisplay.innerText = pitch.note;
      
      let register = "Chest Voice";
      if (pitch.midi < 53) register = "Chest (Low)";
      else if (pitch.midi < 64) register = "Chest Resonance";
      else if (pitch.midi < 73) register = "Mixed Resonance";
      else if (pitch.midi < 88) register = "Head / Falsetto";
      else register = "Whistle Register";
      
      registerDisplay.innerText = register;
      
      if (pitch.midi < 64) registerDisplay.style.color = "var(--neon-green)";
      else if (pitch.midi < 73) registerDisplay.style.color = "var(--neon-purple)";
      else registerDisplay.style.color = "var(--neon-cyan)";

      centsDisplay.innerText = `${cents > 0 ? '+' : ''}${cents} cents`;
      
      if (cents < 0) {
        const width = Math.min(50, Math.abs(cents)) * 2;
        flatBar.style.width = `${width}%`;
        sharpBar.style.width = `0%`;
        centsDisplay.className = "tuner-cents-deviation flat";
        centsDisplay.style.color = 'var(--neon-orange)';
      } else {
        const width = Math.min(50, cents) * 2;
        sharpBar.style.width = `${width}%`;
        flatBar.style.width = `0%`;
        centsDisplay.className = "tuner-cents-deviation sharp";
        centsDisplay.style.color = 'var(--neon-purple)';
      }

      // Volume dynamics updates in Studio
      const studioDynVal = document.getElementById('studio-dynamics-val');
      const studioDynFill = document.getElementById('studio-dynamics-fill');
      let dynamics = "Mezzo-Forte (mf)";
      let vol = pitch.volumePct || 0;
      if (vol < 8) dynamics = "Whisper (pp)";
      else if (vol < 26) dynamics = "Piano (p)";
      else if (vol < 56) dynamics = "Mezzo-Forte (mf)";
      else dynamics = "Forte (f)";
      
      if (studioDynVal) {
        studioDynVal.innerText = `${dynamics} (${vol}%)`;
        if (vol < 26) studioDynVal.style.color = "var(--neon-cyan)";
        else if (vol < 56) studioDynVal.style.color = "var(--neon-purple)";
        else studioDynVal.style.color = "var(--neon-orange)";
      }
      if (studioDynFill) {
        studioDynFill.style.width = `${Math.min(100, vol)}%`;
      }

      const centsAbs = Math.abs(cents);
      
      if (centsAbs <= 5) {
        centsDisplay.style.color = 'var(--neon-green)';
        centsDisplay.innerText = 'In Tune!';
      }

      if (centsAbs <= 40) {
        studioMatchFrames++;
        studioCurrentHoldFrames++;
        if (studioCurrentHoldFrames > studioMaxHoldFrames) {
          studioMaxHoldFrames = studioCurrentHoldFrames;
        }
        
        studioIntonationDiffs.push(centsAbs);
        
        if (studioIntonationDiffs.length > 1) {
          const prev = studioIntonationDiffs[studioIntonationDiffs.length - 2];
          studioJitterSum += Math.abs(centsAbs - prev);
        }
      } else {
        studioCurrentHoldFrames = 0;
      }
      
      studioPitchPoints.push({
        time: studioTime,
        midi: pitch.midi,
        targetMidi: effectiveTargetMidi !== -1 ? effectiveTargetMidi : null
      });

    } else {
      noteDisplay.innerText = "--";
      registerDisplay.innerText = "--";
      centsDisplay.innerText = "-- cents";
      centsDisplay.style.color = '';
      flatBar.style.width = `0%`;
      sharpBar.style.width = `0%`;
      
      const studioDynVal = document.getElementById('studio-dynamics-val');
      const studioDynFill = document.getElementById('studio-dynamics-fill');
      if (studioDynVal) studioDynVal.innerText = "--";
      if (studioDynFill) studioDynFill.style.width = "0%";
      
      studioCurrentHoldFrames = 0;
      
      studioPitchPoints.push({
        time: studioTime,
        midi: null,
        targetMidi: effectiveTargetMidi !== -1 ? effectiveTargetMidi : null
      });
    }

    if (studioPitchPoints.length > 250) {
      studioPitchPoints.shift();
    }

    const avgAcc = Math.round((studioMatchFrames / studioTotalFrames) * 100);
    avgAccDisplay.innerText = `${avgAcc}%`;
    
    const peakSec = Math.round((studioMaxHoldFrames * 16.66) / 100) / 10;
    maxHoldDisplay.innerText = `${peakSec}s`;
    
    if (studioIntonationDiffs.length > 1) {
      const avgJitter = studioJitterSum / (studioIntonationDiffs.length - 1);
      const stabilityPct = Math.max(10, Math.round(100 - (avgJitter * 5.5)));
      jitterPct.innerText = `${stabilityPct}%`;
      jitterBar.style.width = `${stabilityPct}%`;
      
      const jitterHz = Math.round(avgJitter * 0.06 * 10) / 10;
      estJitterDisplay.innerText = `${jitterHz} Hz`;
      
      if (stabilityPct >= 85) {
        jitterBar.style.background = 'var(--neon-green)';
        jitterPct.style.color = 'var(--neon-green)';
      } else if (stabilityPct >= 60) {
        jitterBar.style.background = 'var(--neon-cyan)';
        jitterPct.style.color = 'var(--neon-cyan)';
      } else {
        jitterBar.style.background = 'var(--neon-orange)';
        jitterPct.style.color = 'var(--neon-orange)';
      }
    } else {
      jitterPct.innerText = "100%";
      jitterBar.style.width = "100%";
      estJitterDisplay.innerText = "-- Hz";
    }

    drawStudioTimeline(canvas, studioPitchPoints);

    studioIntervalId = requestAnimationFrame(runStudioLoop);
  }
}

function drawStudioTimeline(canvas, points) {
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  
  const rect = canvas.getBoundingClientRect();
  canvas.width = rect.width * dpr;
  canvas.height = rect.height * dpr;
  ctx.scale(dpr, dpr);
  
  const w = rect.width;
  const h = rect.height;

  // Clear
  ctx.fillStyle = '#06060c';
  ctx.fillRect(0, 0, w, h);

  if (points.length === 0) return;

  // Read active focus mode to center boundaries
  const focusMode = document.getElementById('studio-focus-select') ? document.getElementById('studio-focus-select').value : 'pitch';
  let minMidi = 52;
  let maxMidi = 76;

  if (focusMode === 'pitch') {
    const targetMidi = parseInt(document.getElementById('studio-target-select').value);
    if (targetMidi !== -1) {
      minMidi = targetMidi - 7;
      maxMidi = targetMidi + 7;
    } else {
      let activePitches = points.filter(p => p.midi !== null).map(p => p.midi);
      if (activePitches.length > 0) {
        const avgMidi = Math.round(activePitches.reduce((a,b)=>a+b,0)/activePitches.length);
        minMidi = avgMidi - 6;
        maxMidi = avgMidi + 6;
      }
    }
  } else if (focusMode === 'chord') {
    const chordVal = document.getElementById('studio-chord-select').value;
    let chordNotes = [60, 64, 67];
    if (chordVal === 'G-Maj') chordNotes = [55, 59, 62];
    else if (chordVal === 'A-Min') chordNotes = [57, 60, 64];
    else if (chordVal === 'F-Maj') chordNotes = [53, 57, 60];
    
    minMidi = Math.min(...chordNotes) - 3;
    maxMidi = Math.max(...chordNotes) + 3;
  } else if (focusMode === 'interval') {
    const intVal = parseInt(document.getElementById('studio-interval-select').value);
    minMidi = 60 - 3;
    maxMidi = 60 + intVal + 3;
  } else if (focusMode === 'vibrato') {
    const vibMidi = parseInt(document.getElementById('studio-vibrato-select').value);
    minMidi = vibMidi - 5;
    maxMidi = vibMidi + 5;
  }

  const midiRange = maxMidi - minMidi;

  const getX = (index) => (index / 250) * (w - 60) + 40;
  const getY = (m) => h - ((m - minMidi) / midiRange) * (h - 40) - 20;

  // Draw grid lines
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.03)';
  ctx.lineWidth = 1;
  for (let m = minMidi; m <= maxMidi; m++) {
    const yVal = getY(m);
    ctx.beginPath();
    ctx.moveTo(40, yVal);
    ctx.lineTo(w - 20, yVal);
    ctx.stroke();

    ctx.fillStyle = 'rgba(255,255,255,0.15)';
    ctx.font = '10px JetBrains Mono';
    ctx.fillText(audio.midiToNoteName(m), 12, yVal + 3);
  }

  // Draw target lines helper
  const drawTargetLine = (tMidi, color = 'rgba(0, 242, 254, 0.35)', isMatched = false) => {
    const yTarget = getY(tMidi);
    
    ctx.save();
    ctx.strokeStyle = color;
    ctx.lineWidth = isMatched ? 3.5 : 2.5;
    ctx.shadowBlur = isMatched ? 12 : 6;
    ctx.shadowColor = color;
    ctx.beginPath();
    ctx.moveTo(40, yTarget);
    ctx.lineTo(w - 20, yTarget);
    ctx.stroke();
    
    // Draw safe target zone boundaries (±40 cents)
    const yFlat = getY(tMidi - 0.4);
    const ySharp = getY(tMidi + 0.4);
    ctx.fillStyle = isMatched ? 'rgba(0, 255, 135, 0.06)' : 'rgba(0, 242, 254, 0.04)';
    ctx.fillRect(40, ySharp, w - 60, yFlat - ySharp);
    ctx.restore();
  };

  // Render mode targets
  if (focusMode === 'pitch') {
    const targetMidi = parseInt(document.getElementById('studio-target-select').value);
    if (targetMidi !== -1) {
      drawTargetLine(targetMidi);
    }
  } else if (focusMode === 'chord') {
    const chordVal = document.getElementById('studio-chord-select').value;
    let chordNotes = [60, 64, 67];
    if (chordVal === 'G-Maj') chordNotes = [55, 59, 62];
    else if (chordVal === 'A-Min') chordNotes = [57, 60, 64];
    else if (chordVal === 'F-Maj') chordNotes = [53, 57, 60];
    
    chordNotes.forEach((n, idx) => {
      const isMatched = window.studioChordMatchedStatus ? window.studioChordMatchedStatus[idx] : false;
      const color = isMatched ? 'rgba(0, 255, 135, 0.6)' : 'rgba(0, 242, 254, 0.35)';
      drawTargetLine(n, color, isMatched);
    });
  } else if (focusMode === 'interval') {
    const intVal = parseInt(document.getElementById('studio-interval-select').value);
    drawTargetLine(60, 'rgba(0, 242, 254, 0.3)');
    drawTargetLine(60 + intVal, 'rgba(185, 39, 252, 0.45)');
  } else if (focusMode === 'vibrato') {
    const vibMidi = parseInt(document.getElementById('studio-vibrato-select').value);
    drawTargetLine(vibMidi);
  }

  // Draw User Pitch history trail
  ctx.save();
  ctx.strokeStyle = '#b927fc';
  ctx.lineWidth = 3;
  ctx.shadowBlur = 12;
  ctx.shadowColor = 'rgba(185, 39, 252, 0.4)';
  ctx.beginPath();
  
  let drawing = false;
  for (let i = 0; i < points.length; i++) {
    const pt = points[i];
    const xVal = getX(i);
    
    if (pt.midi === null) {
      if (drawing) {
        ctx.stroke();
        drawing = false;
      }
      continue;
    }
    
    const yVal = getY(pt.midi);
    
    if (yVal >= 0 && yVal <= h) {
      if (!drawing) {
        ctx.beginPath();
        ctx.moveTo(xVal, yVal);
        drawing = true;
      } else {
        ctx.lineTo(xVal, yVal);
      }
    }
  }
  if (drawing) {
    ctx.stroke();
  }
  ctx.restore();
}

const achievementDefinitions = [
  {
    key: "pitch_explorer",
    title: "Vocal Pathfinder",
    desc: "Complete your voice range calibration.",
    icon: "fa-compass",
    gold: false
  },
  {
    key: "first_flight",
    title: "First Flight",
    desc: "Complete your first curriculum level.",
    icon: "fa-feather",
    gold: false
  },
  {
    key: "triple_star",
    title: "Three-Star Vocalist",
    desc: "Earn a perfect 3-star rating on any level.",
    icon: "fa-star",
    gold: false
  },
  {
    key: "pitch_perfection",
    title: "Pitch Perfection",
    desc: "Achieve >= 95% intonation accuracy.",
    icon: "fa-bullseye",
    gold: false
  },
  {
    key: "iron_lung",
    title: "Iron Lung",
    desc: "Hold a note with >= 90% stability on Level 2 or 6.",
    icon: "fa-lungs",
    gold: true
  },
  {
    key: "agility_runner",
    title: "Agility Runner",
    desc: "Achieve >= 80% agility on Level 5 (Pentatonic Runner).",
    icon: "fa-person-running",
    gold: false
  },
  {
    key: "vibrato_wizard",
    title: "Vibrato Wizard",
    desc: "Achieve >= 80% accuracy on Level 7 (Vibrato Waves).",
    icon: "fa-wave-square",
    gold: true
  },
  {
    key: "halfway_there",
    title: "Resonance Champion",
    desc: "Reach curriculum Level 5.",
    icon: "fa-medal",
    gold: false
  },
  {
    key: "virtuoso",
    title: "Vocal Virtuoso",
    desc: "Complete Level 9 (Vocal Rollercoaster).",
    icon: "fa-trophy",
    gold: true
  },
  {
    key: "karaoke_star",
    title: "Karaoke Star",
    desc: "Complete a custom karaoke song or preset track.",
    icon: "fa-microphone",
    gold: false
  }
];

function renderAchievementsGrid(achievements) {
  const grid = document.getElementById('achievements-grid');
  if (!grid) return;
  grid.innerHTML = '';
  
  achievementDefinitions.forEach(def => {
    const unlockedRecord = achievements.find(a => a.achievement_key === def.key);
    const isUnlocked = !!unlockedRecord;
    
    const card = document.createElement('div');
    card.className = `achievement-card ${isUnlocked ? '' : 'locked'} ${def.gold ? 'gold' : ''}`;
    
    let dateText = "Locked";
    if (isUnlocked) {
      const date = new Date(unlockedRecord.unlocked_at).toLocaleDateString(undefined, {
        month: 'short',
        day: 'numeric'
      });
      dateText = `Unlocked ${date}`;
    }
    
    card.innerHTML = `
      <div class="achievement-icon">
        <i class="fa-solid ${isUnlocked ? def.icon : 'fa-lock'}"></i>
      </div>
      <div class="achievement-info">
        <h4>${def.title}</h4>
        <p>${def.desc}</p>
        <span style="font-size: 0.65rem; color: ${isUnlocked ? 'var(--neon-green)' : 'var(--text-muted)'}; margin-top: 0.2rem; display: inline-block;">${dateText}</span>
      </div>
    `;
    
    grid.appendChild(card);
  });
}

async function renderCharacterView() {
  try {
    const res = await fetch('/api/character-status', { headers: { 'X-User-Id': currentUser ? currentUser.id : '' } });
    if (!res.ok) throw new Error("Failed to fetch character status");
    const status = await res.json();
    if (status.queued || !status.user) throw new Error("Invalid character status response");
    
    // Update user info
    const user = status.user;
    currentUser = user;
    saveCurrentUserLocal(currentUser);
    
    document.getElementById('character-name').innerText = user.username;
    document.getElementById('character-avatar').innerText = user.username.charAt(0).toUpperCase();
    document.getElementById('character-level-badge').innerText = user.current_level;
    document.getElementById('character-voice-type').innerText = user.voice_type;
    document.getElementById('character-xp').innerText = user.total_score + " XP";
    
    // XP Bar (scales towards a max of 3000 XP)
    const xpPercent = Math.min(100, Math.round((user.total_score / 3000) * 100));
    document.getElementById('character-xp-bar').style.width = `${xpPercent}%`;
    
    // Map Level to Titles
    let title = "Apprentice Hummingbird 🐦";
    const lvl = user.current_level;
    if (lvl >= 9) title = "Vocal Virtuoso 👑";
    else if (lvl >= 8) title = "Vibrato Maestro ⚡";
    else if (lvl >= 6) title = "Choral Champion 🏆";
    else if (lvl >= 4) title = "Resonance Ranger 🎙️";
    else if (lvl >= 2) title = "Vocal Novice 🎶";
    document.getElementById('character-title').innerText = title;
    
    // Update stats averages
    document.getElementById('char-stat-accuracy').innerText = Math.round(status.stats.avg_accuracy) + "%";
    document.getElementById('char-stat-stability').innerText = Math.round(status.stats.avg_stability) + "%";
    document.getElementById('char-stat-agility').innerText = Math.round(status.stats.avg_agility) + "%";
    document.getElementById('char-levels-cleared').innerText = status.stats.levels_completed;
    document.getElementById('char-songs-sung').innerText = status.stats.karaoke_completed;
    
    // Render Achievements
    renderAchievementsGrid(status.achievements);
    
  } catch (err) {
    console.warn("Offline: loading character status from local storage", err);
    
    const user = currentUser;
    document.getElementById('character-name').innerText = user.username;
    document.getElementById('character-avatar').innerText = user.username.charAt(0).toUpperCase();
    document.getElementById('character-level-badge').innerText = user.current_level;
    document.getElementById('character-voice-type').innerText = user.voice_type;
    document.getElementById('character-xp').innerText = user.total_score + " XP";
    
    // XP Bar (scales towards a max of 3000 XP)
    const xpPercent = Math.min(100, Math.round((user.total_score / 3000) * 100));
    document.getElementById('character-xp-bar').style.width = `${xpPercent}%`;
    
    // Map Level to Titles
    let title = "Apprentice Hummingbird 🐦";
    const lvl = user.current_level;
    if (lvl >= 9) title = "Vocal Virtuoso 👑";
    else if (lvl >= 8) title = "Vibrato Maestro ⚡";
    else if (lvl >= 6) title = "Choral Champion 🏆";
    else if (lvl >= 4) title = "Resonance Ranger 🎙️";
    else if (lvl >= 2) title = "Vocal Novice 🎶";
    document.getElementById('character-title').innerText = title;

    // Load statistics from IndexedDB cache
    const cachedStats = dbCache.cachedStats || [];
    let avgAcc = 0;
    let avgStability = 0;
    let avgAgility = 0;
    let levelsCompleted = 0;
    let karaokeCompleted = 0;

    if (cachedStats.length > 0) {
      cachedStats.forEach(s => {
        if (s.level_id === -100) {
          karaokeCompleted++;
        } else {
          levelsCompleted++;
        }
        avgAcc += s.pitch_accuracy_pct || 0;
        avgStability += s.stability_score || 0;
        avgAgility += s.agility_score || 0;
      });
      avgAcc = Math.round(avgAcc / cachedStats.length);
      avgStability = Math.round(avgStability / cachedStats.length);
      avgAgility = Math.round(avgAgility / cachedStats.length);
    }

    document.getElementById('char-stat-accuracy').innerText = avgAcc + "%";
    document.getElementById('char-stat-stability').innerText = avgStability + "%";
    document.getElementById('char-stat-agility').innerText = avgAgility + "%";
    document.getElementById('char-levels-cleared').innerText = levelsCompleted;
    document.getElementById('char-songs-sung').innerText = karaokeCompleted;

    const localAchievements = dbCache.unlockedAchievements || [];
    renderAchievementsGrid(localAchievements);
  }
}

// 11. Setup Sheet Music Practice Arena View controls
function setupSheetMusic() {
  const toggleBtn = document.getElementById('sheet-toggle-btn');
  const songSelect = document.getElementById('sheet-song-select');
  
  toggleBtn.addEventListener('click', async () => {
    if (toggleBtn.classList.contains('btn-accent')) {
      // Stop practice
      stopAllActiveLoops();
    } else {
      stopAllActiveLoops();
      try {
        await audio.init();
        const songKey = songSelect.value;
        sheetEngine.start(songKey);
      } catch(e) {
        alert("Microphone connection required for Sheet Music Practice Arena!");
      }
    }
  });
}

// 12. Vocal Diagnostic Profile Tab rendering and chart drawing
function renderKeyboardHeatmap(notePerformance, lowMidi, highMidi) {
  const profileKeyboard = document.getElementById('profile-keyboard');
  const hudEmptyState = document.getElementById('hud-empty-state');
  const hudNoteDetails = document.getElementById('hud-note-details');
  if (!profileKeyboard) return;

  const displayMinMidi = 40;
  const displayMaxMidi = 84;

  const minK = Math.max(36, lowMidi - 3);
  const maxK = Math.min(96, highMidi + 3);
  
  let keyboardHtml = '';
  const noteStrings = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
  
  for (let k = minK; k <= maxK; k++) {
    const pitchClass = k % 12;
    const isBlack = [1, 3, 6, 8, 10].includes(pitchClass);
    const noteName = noteStrings[pitchClass] + (Math.floor(k / 12) - 1);
    
    const perf = notePerformance.find(p => p.midi_note === k);
    let accuracyClass = '';
    let avgAcc = null;
    let peakSustain = null;
    
    if (perf) {
      avgAcc = Math.round(perf.avg_accuracy);
      peakSustain = Math.round(perf.max_sustain * 10) / 10;
      
      if (avgAcc >= 85) accuracyClass = 'tested-perfect';
      else if (avgAcc >= 65) accuracyClass = 'tested-good';
      else if (avgAcc >= 35) accuracyClass = 'tested-shaky';
      else accuracyClass = 'tested-strain';
    } else {
      accuracyClass = isBlack ? 'untested-black' : 'untested-white';
    }
    
    const keyClass = isBlack ? 'black-key' : 'white-key';
    const label = (pitchClass === 0) ? noteName : (isBlack ? '' : noteStrings[pitchClass]);
    
    keyboardHtml += `<div class="piano-key ${keyClass} ${accuracyClass}" 
                          data-midi="${k}" 
                          data-name="${noteName}" 
                          data-accuracy="${avgAcc !== null ? avgAcc : ''}" 
                          data-sustain="${peakSustain !== null ? peakSustain : ''}">
                      <div style="pointer-events:none;">${label}</div>
                    </div>`;
  }
  profileKeyboard.innerHTML = keyboardHtml;

  // Attach hover and click event listeners to keyboard keys
  const keys = profileKeyboard.querySelectorAll('.piano-key');
  keys.forEach(key => {
    const updateHUD = () => {
      hudEmptyState.style.display = 'none';
      hudNoteDetails.style.display = 'block';
      
      const midi = parseInt(key.getAttribute('data-midi'));
      const name = key.getAttribute('data-name');
      const accuracyVal = key.getAttribute('data-accuracy');
      const sustainVal = key.getAttribute('data-sustain');
      
      const freq = Math.round(440 * Math.pow(2, (midi - 69) / 12) * 10) / 10;
      
      document.getElementById('hud-note-name').innerText = name;
      document.getElementById('hud-note-freq').innerText = `${freq} Hz`;
      
      const accuracyEl = document.getElementById('hud-note-accuracy');
      const sustainEl = document.getElementById('hud-note-sustain');
      const diagnosisEl = document.getElementById('hud-note-diagnosis');
      
      if (accuracyVal !== '') {
        const acc = parseInt(accuracyVal);
        accuracyEl.innerText = `${acc}%`;
        sustainEl.innerText = `${sustainVal}s`;
        
        if (acc >= 85) {
          accuracyEl.style.color = 'var(--neon-green)';
          diagnosisEl.innerHTML = `<span style="color: var(--neon-green); font-weight:bold;">Perfect Tone / Core Sweetspot.</span> High stability, clean intonation, and strong breath control. Recommended for sustained melody lines.`;
        } else if (acc >= 65) {
          accuracyEl.style.color = 'var(--neon-cyan)';
          diagnosisEl.innerHTML = `<span style="color: var(--neon-cyan); font-weight:bold;">Good Tone.</span> Minor pitch fluctuations but stable. Practice scales to lock in centering.`;
        } else if (acc >= 35) {
          accuracyEl.style.color = 'var(--neon-orange)';
          diagnosisEl.innerHTML = `<span style="color: var(--neon-orange); font-weight:bold;">Shaky Pitch.</span> High jitter detected. Focus on core abdominal breathing to support note pressure.`;
        } else {
          accuracyEl.style.color = 'var(--neon-red)';
          diagnosisEl.innerHTML = `<span style="color: var(--neon-red); font-weight:bold;">Strain Zone.</span> High intonation error. You are straining to reach or sustain this note. Try soft humming warmups.`;
        }
      } else {
        accuracyEl.innerText = '--%';
        accuracyEl.style.color = 'var(--text-secondary)';
        sustainEl.innerText = '--s';
        diagnosisEl.innerText = 'Untested note. Complete a curriculum level or song containing this pitch to record diagnostics.';
      }
    };

    key.addEventListener('mouseenter', updateHUD);
    key.addEventListener('click', () => {
      updateHUD();
      audio.playGuideTone(parseInt(key.getAttribute('data-midi')), 0.8);
    });
  });
}

async function renderVocalProfile() {
  const profileVoiceType = document.getElementById('profile-voice-type');
  const profileRangeBounds = document.getElementById('profile-range-bounds');
  const profileRangeBar = document.getElementById('profile-range-bar');
  const profileGenreRecs = document.getElementById('profile-genre-recommendations');
  const profileKeyboard = document.getElementById('profile-keyboard');
  const hudEmptyState = document.getElementById('hud-empty-state');
  const hudNoteDetails = document.getElementById('hud-note-details');
  const timelineCanvas = document.getElementById('profile-timeline-chart');
  
  const chkAccuracy = document.getElementById('chk-pro-accuracy');
  const statAccuracy = document.getElementById('stat-pro-accuracy');
  const chkStability = document.getElementById('chk-pro-stability');
  const statStability = document.getElementById('stat-pro-stability');
  const chkAgility = document.getElementById('chk-pro-agility');
  const statAgility = document.getElementById('stat-pro-agility');
  const chkSpan = document.getElementById('chk-pro-span');
  const statSpan = document.getElementById('stat-pro-span');
  
  const coachStrengths = document.getElementById('profile-coaching-strengths');
  const coachWeaknesses = document.getElementById('profile-coaching-weaknesses');

  const updateChecklist = (chk, stat, isMet, label) => {
    if (isMet) {
      chk.innerHTML = '<i class="fa-solid fa-circle-check" style="color: var(--neon-green); text-shadow: var(--shadow-neon-green);"></i>';
      stat.style.color = "var(--neon-green)";
    } else {
      chk.innerHTML = '<i class="fa-regular fa-circle" style="color: var(--text-secondary);"></i>';
      stat.style.color = "var(--text-secondary)";
    }
    stat.innerText = label;
  };

  try {
    const res = await fetch('/api/vocal-profile', { headers: { 'X-User-Id': currentUser ? currentUser.id : '' } });
    if (!res.ok) throw new Error("Failed to load vocal profile data");
    const data = await res.json();
    if (data.queued || !data.user) throw new Error("Vocal profile offline or invalid");
    
    const user = data.user;
    const notePerformance = data.notePerformance || [];
    const timeline = data.timeline || [];
    
    // 1. Update Vocal Range & Classification
    profileVoiceType.innerText = user.voice_type || "Not Calibrated";
    
    const lowFreq = Math.round(user.min_pitch_hz);
    const highFreq = Math.round(user.max_pitch_hz);
    profileRangeBounds.innerText = `${lowFreq} Hz to ${highFreq} Hz`;
    
    // Map bounds to MIDI
    const lowMidi = Math.round(69 + 12 * Math.log2(user.min_pitch_hz / 440.0));
    const highMidi = Math.round(69 + 12 * Math.log2(user.max_pitch_hz / 440.0));
    const totalSemitones = highMidi - lowMidi;
    
    // Let's set range bar layout: min pitch is E2 (MIDI 40), max pitch is C6 (MIDI 84)
    const displayMinMidi = 40;
    const displayMaxMidi = 84;
    const displayRange = displayMaxMidi - displayMinMidi;
    
    const leftPercent = Math.max(0, Math.min(100, ((lowMidi - displayMinMidi) / displayRange) * 100));
    const rightPercent = Math.max(0, Math.min(100, ((displayMaxMidi - highMidi) / displayRange) * 100));
    profileRangeBar.style.left = `${leftPercent}%`;
    profileRangeBar.style.right = `${rightPercent}%`;

    // 2. Genre Recommendations based on low bound
    let genres = [];
    if (lowMidi < 52) {
      genres = ["Blues", "Jazz", "Folk", "Classical Opera (Bass)", "Country"];
    } else if (lowMidi < 55) {
      genres = ["Pop / Rock", "R&B / Soul", "Musical Theatre", "Classical Opera (Tenor)", "Alternative"];
    } else if (lowMidi < 60) {
      genres = ["Soul / Motown", "Jazz", "Indie / Folk", "Pop", "Choral Alto"];
    } else {
      genres = ["Classical Opera (Soprano)", "Pop / Dance", "Musical Theatre", "Choral Soprano", "Symphonic Rock"];
    }
    
    profileGenreRecs.innerHTML = genres.map(g => 
      `<span style="background: rgba(0, 242, 254, 0.1); border: 1px solid rgba(0, 242, 254, 0.2); padding: 0.25rem 0.6rem; border-radius: 6px; font-size: 0.8rem; color: var(--neon-cyan); font-weight: bold; box-shadow: 0 0 8px rgba(0, 242, 254, 0.15);">${g}</span>`
    ).join('');

    // 3. Pro Checklist & Stats Averages Calculation from timeline/character
    const statusRes = await fetch('/api/character-status', { headers: { 'X-User-Id': currentUser ? currentUser.id : '' } });
    if (!statusRes.ok) throw new Error("Failed to fetch character status");
    const statusData = await statusRes.json();
    if (statusData.queued || !statusData.stats) throw new Error("Character status offline or invalid");
    const characterStats = statusData.stats;
    
    let totalAccuracy = 0;
    let totalJitter = 0;
    let totalAgility = 0;
    
    if (timeline.length > 0) {
      timeline.forEach(t => {
        totalAccuracy += t.pitch_accuracy_pct || 0;
        totalJitter += t.stability_score || 0;
        totalAgility += t.agility_score || 0;
      });
      const count = timeline.length;
      totalAccuracy /= count;
      totalJitter /= count;
      totalAgility /= count;
    } else {
      totalAccuracy = characterStats.avg_accuracy || 0;
      totalJitter = characterStats.avg_stability || 0;
      totalAgility = characterStats.avg_agility || 0;
    }
    
    const centsError = Math.round(Math.max(5, 50 - (totalAccuracy * 0.45)));
    const jitterHz = Math.round(Math.max(0.5, 6.5 - (totalJitter * 0.06)) * 10) / 10;
    const transitionMs = Math.round(Math.max(50, 200 - (totalAgility * 1.5)));

    updateChecklist(chkAccuracy, statAccuracy, centsError < 15, `${centsError} cents`);
    updateChecklist(chkStability, statStability, jitterHz < 2.0, `${jitterHz} Hz`);
    updateChecklist(chkAgility, statAgility, transitionMs < 80, `${transitionMs} ms`);
    updateChecklist(chkSpan, statSpan, totalSemitones >= 24, `${totalSemitones} semitones`);

    // 4. Generate keyboard keys mapping the range Low to High
    renderKeyboardHeatmap(notePerformance, lowMidi, highMidi);

    // 5. Draw Vocal Progress History Chart
    drawProgressTimelineChart(timelineCanvas, timeline);

    // 6. Set Coaching Report advice
    const weakNotes = notePerformance.filter(p => p.avg_accuracy < 50).map(p => audio.midiToNoteName(p.midi_note));
    const strongNotes = notePerformance.filter(p => p.avg_accuracy >= 80).map(p => audio.midiToNoteName(p.midi_note));

    if (strongNotes.length > 0) {
      coachStrengths.innerText = `You have exceptional vocal compression and pitch accuracy around ${strongNotes.slice(0, 4).join(', ')}. These notes represent your natural resonant placement and sweetspot.`;
    } else {
      coachStrengths.innerText = "Complete level challenges in tune to record your vocal strengths!";
    }

    if (weakNotes.length > 0) {
      coachWeaknesses.innerText = `You experience tension or breath leakage around ${weakNotes.slice(0, 4).join(', ')}. Practice glides and soft sirens around these pitches to reduce neck strain.`;
    } else if (notePerformance.length > 0) {
      coachWeaknesses.innerText = "No severe strain notes detected! Keep expanding your range limits slowly and practicing stability.";
    } else {
      coachWeaknesses.innerText = "Complete curriculum levels to scan for strain notes and jitter hotspots.";
    }

    const warmupBtn = document.getElementById('profile-start-warmup-btn');
    if (warmupBtn) {
      warmupBtn.onclick = () => {
        switchView('warmups-view');
      };
    }

  } catch(err) {
    console.warn("Offline: loading vocal profile from local storage", err);
    
    const user = currentUser;
    profileVoiceType.innerText = user.voice_type || "Not Calibrated";
    
    const lowFreq = Math.round(user.min_pitch_hz);
    const highFreq = Math.round(user.max_pitch_hz);
    profileRangeBounds.innerText = `${lowFreq} Hz to ${highFreq} Hz`;
    
    const lowMidi = Math.round(69 + 12 * Math.log2(user.min_pitch_hz / 440.0));
    const highMidi = Math.round(69 + 12 * Math.log2(user.max_pitch_hz / 440.0));
    const totalSemitones = highMidi - lowMidi;
    
    const displayMinMidi = 40;
    const displayMaxMidi = 84;
    const displayRange = displayMaxMidi - displayMinMidi;
    
    const leftPercent = Math.max(0, Math.min(100, ((lowMidi - displayMinMidi) / displayRange) * 100));
    const rightPercent = Math.max(0, Math.min(100, ((displayMaxMidi - highMidi) / displayRange) * 100));
    profileRangeBar.style.left = `${leftPercent}%`;
    profileRangeBar.style.right = `${rightPercent}%`;

    let genres = [];
    if (lowMidi < 52) {
      genres = ["Blues", "Jazz", "Folk", "Classical Opera (Bass)", "Country"];
    } else if (lowMidi < 55) {
      genres = ["Pop / Rock", "R&B / Soul", "Musical Theatre", "Classical Opera (Tenor)", "Alternative"];
    } else if (lowMidi < 60) {
      genres = ["Soul / Motown", "Jazz", "Indie / Folk", "Pop", "Choral Alto"];
    } else {
      genres = ["Classical Opera (Soprano)", "Pop / Dance", "Musical Theatre", "Choral Soprano", "Symphonic Rock"];
    }
    
    profileGenreRecs.innerHTML = genres.map(g => 
      `<span style="background: rgba(0, 242, 254, 0.1); border: 1px solid rgba(0, 242, 254, 0.2); padding: 0.25rem 0.6rem; border-radius: 6px; font-size: 0.8rem; color: var(--neon-cyan); font-weight: bold; box-shadow: 0 0 8px rgba(0, 242, 254, 0.15);">${g}</span>`
    ).join('');

    const notePerformance = dbCache.localNotePerformance || [];
    const cachedStats = dbCache.cachedStats || [];
    const timeline = cachedStats.slice(0, 10).reverse();

    let totalAccuracy = 0;
    let totalJitter = 0;
    let totalAgility = 0;
    
    if (timeline.length > 0) {
      timeline.forEach(t => {
        totalAccuracy += t.pitch_accuracy_pct || 0;
        totalJitter += t.stability_score || 0;
        totalAgility += t.agility_score || 0;
      });
      const count = timeline.length;
      totalAccuracy /= count;
      totalJitter /= count;
      totalAgility /= count;
    } else {
      // Fallback defaults
      totalAccuracy = 75;
      totalJitter = 80;
      totalAgility = 70;
    }

    const centsError = Math.round(Math.max(5, 50 - (totalAccuracy * 0.45)));
    const jitterHz = Math.round(Math.max(0.5, 6.5 - (totalJitter * 0.06)) * 10) / 10;
    const transitionMs = Math.round(Math.max(50, 200 - (totalAgility * 1.5)));

    updateChecklist(chkAccuracy, statAccuracy, centsError < 15, `${centsError} cents`);
    updateChecklist(chkStability, statStability, jitterHz < 2.0, `${jitterHz} Hz`);
    updateChecklist(chkAgility, statAgility, transitionMs < 80, `${transitionMs} ms`);
    updateChecklist(chkSpan, statSpan, totalSemitones >= 24, `${totalSemitones} semitones`);

    renderKeyboardHeatmap(notePerformance, lowMidi, highMidi);
    drawProgressTimelineChart(timelineCanvas, timeline);

    const weakNotes = notePerformance.filter(p => p.avg_accuracy < 50).map(p => audio.midiToNoteName(p.midi_note));
    const strongNotes = notePerformance.filter(p => p.avg_accuracy >= 80).map(p => audio.midiToNoteName(p.midi_note));

    if (strongNotes.length > 0) {
      coachStrengths.innerText = `You have exceptional vocal compression and pitch accuracy around ${strongNotes.slice(0, 4).join(', ')}. These notes represent your natural resonant placement and sweetspot.`;
    } else {
      coachStrengths.innerText = "Complete level challenges in tune to record your vocal strengths!";
    }

    if (weakNotes.length > 0) {
      coachWeaknesses.innerText = `You experience tension or breath leakage around ${weakNotes.slice(0, 4).join(', ')}. Practice glides and soft sirens around these pitches to reduce neck strain.`;
    } else if (notePerformance.length > 0) {
      coachWeaknesses.innerText = "No severe strain notes detected! Keep expanding your range limits slowly and practicing stability.";
    } else {
      coachWeaknesses.innerText = "Complete curriculum levels to scan for strain notes and jitter hotspots.";
    }

    const warmupBtn = document.getElementById('profile-start-warmup-btn');
    if (warmupBtn) {
      warmupBtn.onclick = () => {
        switchView('warmups-view');
      };
    }
  }
}

function drawProgressTimelineChart(canvas, timeline) {
  const ctx = canvas.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  
  const rect = canvas.getBoundingClientRect();
  canvas.width = rect.width * dpr;
  canvas.height = rect.height * dpr;
  ctx.scale(dpr, dpr);
  
  const w = rect.width;
  const h = rect.height;

  // Clear background
  ctx.fillStyle = '#06060c';
  ctx.fillRect(0, 0, w, h);

  // Draw grid lines
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.05)';
  ctx.lineWidth = 1;
  
  const percentages = [0, 25, 50, 75, 100];
  percentages.forEach(p => {
    const y = h - 30 - (p / 100) * (h - 50);
    ctx.beginPath();
    ctx.moveTo(35, y);
    ctx.lineTo(w - 20, y);
    ctx.stroke();

    ctx.fillStyle = 'rgba(255, 255, 255, 0.3)';
    ctx.font = '9px Outfit';
    ctx.fillText(`${p}%`, 8, y + 3);
  });

  if (!timeline || timeline.length === 0) {
    ctx.fillStyle = 'rgba(255, 255, 255, 0.4)';
    ctx.font = '12px Outfit';
    ctx.textAlign = 'center';
    ctx.fillText("No history yet. Clear graded levels to populate history chart.", w / 2, h / 2);
    return;
  }

  const count = timeline.length;
  const getX = (i) => 35 + (i / Math.max(1, count - 1)) * (w - 70);
  
  for (let i = 0; i < count; i++) {
    const x = getX(i);
    ctx.beginPath();
    ctx.moveTo(x, h - 30);
    ctx.lineTo(x, 20);
    ctx.stroke();

    ctx.fillStyle = 'rgba(255, 255, 255, 0.3)';
    ctx.font = '9px Outfit';
    ctx.textAlign = 'center';
    ctx.fillText(`#${i + 1}`, x, h - 15);
  }

  const drawLine = (color, getValueKey) => {
    ctx.strokeStyle = color;
    ctx.lineWidth = 2.5;
    ctx.beginPath();
    for (let i = 0; i < count; i++) {
      const val = timeline[i][getValueKey] || 0;
      const x = getX(i);
      const y = h - 30 - (val / 100) * (h - 50);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();

    ctx.fillStyle = color;
    for (let i = 0; i < count; i++) {
      const val = timeline[i][getValueKey] || 0;
      const x = getX(i);
      const y = h - 30 - (val / 100) * (h - 50);
      ctx.beginPath();
      ctx.arc(x, y, 4, 0, Math.PI * 2);
      ctx.fill();
    }
  };

  drawLine('#00ff87', 'pitch_accuracy_pct');
  drawLine('#00f2fe', 'stability_score');
  drawLine('#b927fc', 'agility_score');

  ctx.textAlign = 'left';
  ctx.font = '9px Outfit';
  
  ctx.fillStyle = '#00ff87';
  ctx.fillText("● Accuracy", 40, 15);
  
  ctx.fillStyle = '#00f2fe';
  ctx.fillText("● Stability", 120, 15);
  
  ctx.fillStyle = '#b927fc';
  ctx.fillText("● Agility", 200, 15);
}

// --- 12. SETUP DAILY PRACTICE PLANNER VIEW ---
let plannerTimerIntervalId = null;
let plannerGoalSeconds = 120 * 60; // default 120 mins
let plannerElapsedSeconds = 0;
let plannerActiveSeconds = 0;
let plannerActiveSecondsUnsaved = 0;
let plannerElapsedSecondsUnsaved = 0;
let plannerIsRunning = false;
let plannerConsecutiveActiveSeconds = 0;
let plannerRestSecondsLeft = 300;
let plannerRestIntervalId = null;

function setupPlannerView() {
  const toggleBtn = document.getElementById('planner-timer-toggle-btn');
  const resetBtn = document.getElementById('planner-timer-reset-btn');
  const targetSelect = document.getElementById('planner-target-select');
  const timerDisplay = document.getElementById('planner-timer-display');
  const activeMinutesDisplay = document.getElementById('planner-active-minutes-val');
  const goalPctDisplay = document.getElementById('planner-goal-pct-val');
  const progressFill = document.getElementById('planner-progress-fill');
  const voiceDot = document.getElementById('planner-voice-dot');
  const voiceText = document.getElementById('activity-status-text');

  // Checklist elements
  const todoWarmups = document.getElementById('todo-warmups');
  const todoStudio = document.getElementById('todo-studio');
  const todoCurriculum = document.getElementById('todo-curriculum');
  const todoRepertoire = document.getElementById('todo-repertoire');

  if (!toggleBtn) return; // safety check

  // Load target select initial value
  targetSelect.addEventListener('change', () => {
    plannerGoalSeconds = parseInt(targetSelect.value) * 60;
    updatePlannerTimerUI();
  });

  // Checklist local persistence
  const todayKey = new Date().toISOString().slice(0, 10);
  const savedChecklist = JSON.parse(localStorage.getItem(`checklist_${todayKey}`) || '{}');
  todoWarmups.checked = !!savedChecklist.warmups;
  todoStudio.checked = !!savedChecklist.studio;
  todoCurriculum.checked = !!savedChecklist.curriculum;
  todoRepertoire.checked = !!savedChecklist.repertoire;

  const saveChecklistState = () => {
    localStorage.setItem(`checklist_${todayKey}`, JSON.stringify({
      warmups: todoWarmups.checked,
      studio: todoStudio.checked,
      curriculum: todoCurriculum.checked,
      repertoire: todoRepertoire.checked
    }));
  };

  todoWarmups.addEventListener('change', saveChecklistState);
  todoStudio.addEventListener('change', saveChecklistState);
  todoCurriculum.addEventListener('change', saveChecklistState);
  todoRepertoire.addEventListener('change', saveChecklistState);

  // Toggle button event
  toggleBtn.addEventListener('click', async () => {
    if (plannerIsRunning) {
      stopPlannerTimer();
    } else {
      try {
        await audio.init();
        startPlannerTimer();
      } catch (err) {
        alert("Microphone stream is required to track active singing time!");
      }
    }
  });

  // Reset button event
  resetBtn.addEventListener('click', () => {
    if (confirm("Are you sure you want to reset today's active practice session timer?")) {
      stopPlannerTimer();
      plannerElapsedSeconds = 0;
      plannerActiveSeconds = 0;
      plannerActiveSecondsUnsaved = 0;
      plannerElapsedSecondsUnsaved = 0;
      plannerConsecutiveActiveSeconds = 0;
      updatePlannerTimerUI();
    }
  });

  function startPlannerTimer() {
    plannerIsRunning = true;
    toggleBtn.innerHTML = '<i class="fa-solid fa-pause"></i> Pause Session';
    toggleBtn.classList.remove('btn-primary');
    toggleBtn.classList.add('btn-accent');

    plannerTimerIntervalId = setInterval(() => {
      // 1. Tick session time
      plannerElapsedSeconds++;
      plannerElapsedSecondsUnsaved++;

      // 2. Sample mic for active singing
      let isSinging = false;
      let volPct = 0;
      if (audio.isListening) {
        const pitch = audio.detectPitch();
        isSinging = (pitch.frequency > 0 && pitch.volumePct >= 8);
        volPct = pitch.volumePct || 0;
      }

      if (isSinging) {
        plannerActiveSeconds++;
        plannerActiveSecondsUnsaved++;
        plannerConsecutiveActiveSeconds++;
        
        voiceDot.style.background = 'var(--neon-green)';
        voiceDot.classList.add('active');
        voiceText.innerText = `Singing (${volPct}%)`;
        voiceText.style.color = 'var(--neon-green)';

        // 30-minute rest reminder check (30 minutes = 1800 active seconds)
        if (plannerConsecutiveActiveSeconds >= 1800) {
          triggerVocalRestReminder();
        }
      } else {
        voiceDot.style.background = '#555';
        voiceDot.classList.remove('active');
        voiceText.innerText = "Listening (Idle)";
        voiceText.style.color = 'var(--text-secondary)';
      }

      // 3. Update displays
      updatePlannerTimerUI();

      // 4. Periodically save practice logs (every 30 seconds of active singing)
      if (plannerActiveSecondsUnsaved > 0 && plannerActiveSecondsUnsaved % 30 === 0) {
        savePracticeLogProgress();
      }

    }, 1000);
  }

  function stopPlannerTimer() {
    plannerIsRunning = false;
    if (plannerTimerIntervalId) {
      clearInterval(plannerTimerIntervalId);
      plannerTimerIntervalId = null;
    }
    toggleBtn.innerHTML = '<i class="fa-solid fa-play"></i> Resume Session';
    toggleBtn.classList.remove('btn-accent');
    toggleBtn.classList.add('btn-primary');

    voiceDot.style.background = '#555';
    voiceDot.classList.remove('active');
    voiceText.innerText = "Mic Off";
    voiceText.style.color = 'var(--text-secondary)';

    savePracticeLogProgress();
  }

  function updatePlannerTimerUI() {
    const totalGoal = plannerGoalSeconds;
    const remaining = Math.max(0, totalGoal - plannerActiveSeconds);
    const mins = Math.floor(remaining / 60);
    const secs = remaining % 60;
    timerDisplay.innerText = `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;

    const actMins = (plannerActiveSeconds / 60).toFixed(1);
    activeMinutesDisplay.innerText = `${actMins} mins`;

    const pct = Math.min(100, Math.round((plannerActiveSeconds / totalGoal) * 100));
    goalPctDisplay.innerText = `${pct}%`;
    progressFill.style.width = `${pct}%`;
  }
  
  updatePlannerTimerUI();
}

async function savePracticeLogProgress() {
  if (plannerActiveSecondsUnsaved <= 0) return;
  
  const activeMins = plannerActiveSecondsUnsaved / 60;
  const elapsedMins = plannerElapsedSecondsUnsaved / 60;
  
  plannerActiveSecondsUnsaved = 0;
  plannerElapsedSecondsUnsaved = 0;

  try {
    const timestamp = new Date().toISOString();
    const res = await fetch('/api/practice-log', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-User-Id': currentUser ? currentUser.id : '' },
      body: JSON.stringify({
        duration_minutes: elapsedMins,
        active_minutes: activeMins,
        timestamp: timestamp
      })
    });
    if (!res.ok) throw new Error("HTTP error " + res.status);
    const result = await res.json();
    if (result.queued) throw new Error("Request queued offline");
    console.log("Practice log saved to database:", result);
  } catch (err) {
    console.warn("Offline: saving practice log to IndexedDB fallback", err);
    
    // Queue for offline sync via IndexedDB
    addToOfflineQueueLocal({
      type: 'practice_log',
      duration_minutes: elapsedMins,
      active_minutes: activeMins,
      user_id: currentUser ? currentUser.id : '',
      timestamp: new Date().toISOString()
    });
    
    // Update local practice logs in dbCache
    const localLogs = [...dbCache.offlinePracticeLogs];
    const todayKey = new Date().toISOString().slice(0, 10);
    const todayIndex = localLogs.findIndex(l => new Date(l.timestamp).toISOString().slice(0, 10) === todayKey);
    if (todayIndex !== -1) {
      localLogs[todayIndex].active_minutes += activeMins;
      localLogs[todayIndex].duration_minutes += elapsedMins;
    } else {
      localLogs.push({
        active_minutes: activeMins,
        duration_minutes: elapsedMins,
        timestamp: new Date().toISOString()
      });
    }
    saveOfflinePracticeLogsLocal(localLogs);
  }
  
  renderPracticeCalendar();
}

function triggerVocalRestReminder() {
  // Pause session timer
  if (plannerIsRunning) {
    stopPlannerTimer();
  }

  plannerConsecutiveActiveSeconds = 0;

  const overlay = document.getElementById('rest-reminder-overlay');
  const countdown = document.getElementById('rest-timer-countdown');
  const dismissBtn = document.getElementById('rest-dismiss-btn');

  if (!overlay) return;

  overlay.style.display = 'flex';
  plannerRestSecondsLeft = 300; // 5 mins
  dismissBtn.disabled = true;
  dismissBtn.innerText = "Resting (05:00)...";

  if (plannerRestIntervalId) clearInterval(plannerRestIntervalId);
  
  plannerRestIntervalId = setInterval(() => {
    plannerRestSecondsLeft--;
    
    const mins = Math.floor(plannerRestSecondsLeft / 60);
    const secs = plannerRestSecondsLeft % 60;
    countdown.innerText = `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
    dismissBtn.innerText = `Resting (${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')})...`;

    if (plannerRestSecondsLeft <= 0) {
      clearInterval(plannerRestIntervalId);
      plannerRestIntervalId = null;
      dismissBtn.disabled = false;
      dismissBtn.innerText = "Resume Training";
      dismissBtn.classList.remove('btn-secondary');
      dismissBtn.classList.add('btn-primary');
    }
  }, 1000);

  dismissBtn.onclick = () => {
    overlay.style.display = 'none';
  };
}

async function renderPracticeCalendar() {
  const mapContainer = document.getElementById('practice-contribution-map');
  if (!mapContainer) return;

  let logs = [];
  try {
    const res = await fetch('/api/practice-logs', { headers: { 'X-User-Id': currentUser ? currentUser.id : '' } });
    if (!res.ok) throw new Error("HTTP error " + res.status);
    logs = await res.json();
    if (logs.queued || !Array.isArray(logs)) throw new Error("Invalid practice logs response");
    await saveOfflinePracticeLogsLocal(logs);
  } catch (err) {
    console.warn("Offline: loading practice logs from cache");
    logs = dbCache.offlinePracticeLogs || [];
    
    const offlineLogs = dbCache.offlinePracticeLogs || [];
    offlineLogs.forEach(ol => {
      const existing = logs.find(l => new Date(l.timestamp).toISOString().slice(0, 10) === new Date(ol.timestamp).toISOString().slice(0, 10));
      if (existing) {
        existing.active_minutes += ol.active_minutes;
        existing.duration_minutes += ol.duration_minutes;
      } else {
        logs.push(ol);
      }
    });
  }

  const practiceMap = {};
  let totalActiveMinutes = 0;
  let goalMetDays = 0;
  
  logs.forEach(log => {
    const dateStr = new Date(log.timestamp).toDateString();
    practiceMap[dateStr] = (practiceMap[dateStr] || 0) + log.active_minutes;
    totalActiveMinutes += log.active_minutes;
  });

  // Calculate Streak
  let streak = 0;
  let tempDate = new Date();
  while (true) {
    const dateStr = tempDate.toDateString();
    if (practiceMap[dateStr] && practiceMap[dateStr] >= 1.0) {
      streak++;
      tempDate.setDate(tempDate.getDate() - 1);
    } else {
      if (streak === 0 && tempDate.toDateString() === new Date().toDateString()) {
        tempDate.setDate(tempDate.getDate() - 1);
        continue;
      }
      break;
    }
  }

  // Draw 26 weeks (182 days) ending today
  mapContainer.innerHTML = '';
  const startDate = new Date();
  startDate.setDate(startDate.getDate() - 181);
  
  const startDay = startDate.getDay();
  startDate.setDate(startDate.getDate() - startDay);

  for (let i = 0; i < 182; i++) {
    const cellDate = new Date(startDate);
    cellDate.setDate(cellDate.getDate() + i);
    const dateStr = cellDate.toDateString();
    const minutes = practiceMap[dateStr] || 0;

    let tier = 'tier-0';
    if (minutes >= 120) {
      tier = 'tier-4';
      goalMetDays++;
    } else if (minutes >= 60) {
      tier = 'tier-3';
    } else if (minutes >= 30) {
      tier = 'tier-2';
    } else if (minutes >= 1) {
      tier = 'tier-1';
    }

    const cell = document.createElement('div');
    cell.className = `practice-day-cell ${tier}`;
    cell.title = `${dateStr} | Practiced: ${minutes.toFixed(1)} mins`;
    mapContainer.appendChild(cell);
  }

  document.getElementById('planner-streak-count').innerHTML = `<i class="fa-solid fa-fire"></i> ${streak} Day Streak`;
  document.getElementById('planner-total-hours').innerText = (totalActiveMinutes / 60).toFixed(1) + " hrs";
  document.getElementById('planner-goal-days').innerText = `${goalMetDays} days`;

  renderRoadmapProgress(logs);
}

function renderRoadmapProgress(logs) {
  const req1 = document.getElementById('req-stage-1');
  const req2 = document.getElementById('req-stage-2');
  const req3 = document.getElementById('req-stage-3');

  const history = dbCache.cachedStats || [];
  
  const checkStars = (fromId, toId) => {
    for (let id = fromId; id <= toId; id++) {
      const record = history.find(h => h.level_id === id);
      if (!record || record.stars_earned < 3) return false;
    }
    return true;
  };

  const stage1Done = checkStars(1, 3);
  const stage2Done = checkStars(4, 6);
  const stage3Done = checkStars(7, 9);

  const stageCard1 = document.getElementById('roadmap-stage-1');
  const stageCard2 = document.getElementById('roadmap-stage-2');
  const stageCard3 = document.getElementById('roadmap-stage-3');

  if (stage1Done) {
    stageCard1.className = "roadmap-stage completed";
    req1.innerHTML = '<i class="fa-solid fa-circle-check" style="color: var(--neon-green);"></i> Completed!';
    req1.style.color = "var(--neon-green)";
    stageCard2.className = "roadmap-stage active";
    stageCard2.querySelector('.roadmap-dot').style.borderColor = "var(--neon-cyan)";
    stageCard2.querySelector('.roadmap-dot').style.color = "var(--neon-cyan)";
    stageCard2.querySelector('h3').style.color = "var(--neon-cyan)";
  } else {
    stageCard1.className = "roadmap-stage active";
    req1.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> In Progress (Complete Levels 1-3 with 3 stars)';
    req1.style.color = "var(--text-secondary)";
  }

  if (stage2Done) {
    stageCard2.className = "roadmap-stage completed";
    req2.innerHTML = '<i class="fa-solid fa-circle-check" style="color: var(--neon-green);"></i> Completed!';
    req2.style.color = "var(--neon-green)";
    stageCard3.className = "roadmap-stage active";
    stageCard3.querySelector('.roadmap-dot').style.borderColor = "var(--neon-cyan)";
    stageCard3.querySelector('.roadmap-dot').style.color = "var(--neon-cyan)";
    stageCard3.querySelector('h3').style.color = "var(--neon-cyan)";
  } else if (stage1Done) {
    req2.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> In Progress (Complete Levels 4-6 with 3 stars)';
    req2.style.color = "var(--text-secondary)";
  } else {
    req2.innerHTML = '<i class="fa-solid fa-lock"></i> Locked (Complete Stage 1 first)';
    req2.style.color = "var(--text-muted)";
  }

  if (stage3Done) {
    stageCard3.className = "roadmap-stage completed";
    req3.innerHTML = '<i class="fa-solid fa-circle-check" style="color: var(--neon-green);"></i> Completed!';
    req3.style.color = "var(--neon-green)";
  } else if (stage2Done) {
    req3.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> In Progress (Complete Levels 7-9 with 3 stars)';
    req3.style.color = "var(--text-secondary)";
  } else {
    req3.innerHTML = '<i class="fa-solid fa-lock"></i> Locked (Complete Stage 2 first)';
    req3.style.color = "var(--text-muted)";
  }

  let completedCount = 0;
  for (let id = 1; id <= 9; id++) {
    const record = history.find(h => h.level_id === id);
    if (record && record.stars_earned === 3) completedCount++;
  }
  const progressPct = Math.round((completedCount / 9) * 100);
  document.getElementById('planner-roadmap-completion').innerText = `${progressPct}%`;
}

// Background synchronization of offline stats to database
async function syncOfflineQueue() {
  if (isSyncingOfflineQueue) return;
  isSyncingOfflineQueue = true;
  
  try {
  
  try {
    // Sync any buffered error diagnostics first
    await syncErrorLogs();
  } catch(e) {
    console.warn("Error syncing diagnostics logs:", e);
  }
  
  // Attempt sync directly without checking navigator.onLine (more robust)
  console.log("Checking for offline progress to synchronize with cloud database...");
  
  // 0. Sync offline-created user profiles first
  if (currentUser && currentUser.isOfflineProfile) {
    try {
      console.log(`Syncing offline-created user profile: ${currentUser.username}`);
      const createRes = await fetch('/api/users/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: currentUser.username })
      });
      const newUser = await createRes.json();
      if (newUser && newUser.id && !newUser.error) {
        const oldId = currentUser.id;
        const newId = newUser.id;
        
        console.log(`Offline profile synced! Server assigned ID: ${newId}`);
        
        // Update user properties
        currentUser.id = newId;
        delete currentUser.isOfflineProfile;
        
        // Save to IndexedDB
        await saveCurrentUserLocal(currentUser);
        
        // Update allUsers database cache
        let allUsers = dbCache.allUsers;
        allUsers = allUsers.map(u => u.id === oldId ? currentUser : u);
        await saveAllUsersLocal(allUsers);
        
        // Update any progress records in local stats/queue to reference newId
        dbCache.cachedStats.forEach(s => {
          if (s.user_id === oldId) s.user_id = newId;
        });
        await saveCachedStatsLocal(dbCache.cachedStats);

        dbCache.offlineQueue.forEach(q => {
          if (q.user_id === oldId) q.user_id = newId;
        });
      }
    } catch (err) {
      console.warn("Failed to sync offline user profile to server, deferring progress sync:", err);
      updateSyncStatusBadge();
      return;
    }
  }

  // 1. Process unified IndexedDB offline queue
  const queue = [...dbCache.offlineQueue];
  if (queue.length > 0) {
    console.log(`Syncing ${queue.length} offline progress and log entries...`);
    const remaining = [];
    
    for (const item of queue) {
      try {
        let endpoint = '/api/progress';
        if (item.type === 'practice_log') {
          endpoint = '/api/practice-log';
        }
        
        const response = await fetch(endpoint, {
          method: 'POST',
          headers: { 
            'Content-Type': 'application/json',
            'X-User-Id': item.user_id || (currentUser ? currentUser.id : '')
          },
          body: JSON.stringify(item)
        });
        
        // Sync triggers (queued response means server is still offline, retry later)
        const resData = await response.json();
        if (response.ok && !resData.queued) {
          console.log(`Synced offline ${item.type} item successfully.`);
        } else {
          remaining.push(item);
        }
      } catch (err) {
        remaining.push(item);
      }
    }
    
    await saveOfflineQueueLocal(remaining);
  }
  updateSyncStatusBadge();
  } finally {
    isSyncingOfflineQueue = false;
  }
}

// Trigger offline sync when network status changes
window.addEventListener('online', syncOfflineQueue);

// PWA Custom Installation Setup
let deferredPrompt = null;
window.addEventListener('popstate', (event) => {
  if (event.state && event.state.viewId) {
    switchView(event.state.viewId, false);
  }
});

window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredPrompt = e;
  const banner = document.getElementById('pwa-install-banner');
  if (banner) banner.style.display = 'block';
});

document.addEventListener('DOMContentLoaded', () => {
  // Set up initial history state
  const initialView = sessionStorage.getItem('lastView') || 'welcome-view';
  if (!history.state) {
    try {
      history.replaceState({ viewId: initialView }, "", '#' + initialView);
    } catch (e) {
      console.warn("history.replaceState failed:", e);
    }
  }

  const installBtn = document.getElementById('pwa-install-btn');
  if (installBtn) {
    installBtn.addEventListener('click', async () => {
      if (deferredPrompt) {
        deferredPrompt.prompt();
        const { outcome } = await deferredPrompt.userChoice;
        console.log(`[PWA] Install prompt outcome: ${outcome}`);
        deferredPrompt = null;
        const banner = document.getElementById('pwa-install-banner');
        if (banner) banner.style.display = 'none';
      }
    });
  }
});

// Vocal Profile JSON Export and Import
document.addEventListener('DOMContentLoaded', () => {
  const exportBtn = document.getElementById('profile-export-btn');
  const importBtn = document.getElementById('profile-import-btn');
  const fileInput = document.getElementById('profile-import-file');
  const backupStatus = document.getElementById('backup-status');

  if (exportBtn) {
    exportBtn.addEventListener('click', () => {
      const backupData = {
        version: "1.0",
        timestamp: new Date().toISOString(),
        currentUser: dbCache.currentUser,
        allUsers: dbCache.allUsers,
        cachedStats: dbCache.cachedStats,
        localNotePerformance: dbCache.localNotePerformance,
        unlockedAchievements: dbCache.unlockedAchievements,
        offlinePracticeLogs: dbCache.offlinePracticeLogs
      };

      const jsonStr = JSON.stringify(backupData, null, 2);
      const blob = new Blob([jsonStr], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `PitchFlight_Backup_${currentUser.username}_${new Date().toISOString().slice(0, 10)}.json`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);

      if (backupStatus) {
        backupStatus.style.display = 'block';
        backupStatus.style.color = 'var(--neon-green)';
        backupStatus.innerText = "Backup downloaded successfully!";
        setTimeout(() => { backupStatus.style.display = 'none'; }, 3000);
      }
    });
  }

  if (importBtn && fileInput) {
    importBtn.addEventListener('click', () => {
      fileInput.click();
    });

    fileInput.addEventListener('change', (event) => {
      const file = event.target.files[0];
      if (!file) return;

      const reader = new FileReader();
      reader.onload = async (e) => {
        try {
          const imported = JSON.parse(e.target.result);
          if (!imported.currentUser || !imported.allUsers) {
            throw new Error("Invalid backup format: missing profile data.");
          }

          // Import into IndexedDB and cache
          await saveCurrentUserLocal(imported.currentUser);
          await saveAllUsersLocal(imported.allUsers);
          if (imported.cachedStats) await saveCachedStatsLocal(imported.cachedStats);
          if (imported.localNotePerformance) await saveNotePerformanceLocal(imported.localNotePerformance);
          if (imported.unlockedAchievements) await saveUnlockedAchievementsLocal(imported.unlockedAchievements);
          if (imported.offlinePracticeLogs) await saveOfflinePracticeLogsLocal(imported.offlinePracticeLogs);

          if (backupStatus) {
            backupStatus.style.display = 'block';
            backupStatus.style.color = 'var(--neon-green)';
            backupStatus.innerText = "Import successful! Reloading stats...";
            setTimeout(() => {
              window.location.reload();
            }, 1500);
          }
        } catch (err) {
          alert("Import failed: " + err.message);
          if (backupStatus) {
            backupStatus.style.display = 'block';
            backupStatus.style.color = 'var(--neon-orange)';
            backupStatus.innerText = "Import failed!";
          }
        }
      };
      reader.readAsText(file);
    });
  }
});

