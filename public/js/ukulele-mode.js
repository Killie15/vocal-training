// Ukulele Mode Integration - Add to app.js

// Load ukulele curriculum on init
let ukuleleCurriculum = [];

async function loadUkuleleCurriculum() {
  try {
    const response = await fetch('/js/ukulele-curriculum.json');
    ukuleleCurriculum = await response.json();
    console.log(`[Ukulele] Loaded ${ukuleleCurriculum.length} ukulele exercises`);
  } catch (err) {
    console.warn('[Ukulele] Failed to load curriculum:', err);
  }
}

// Initialize ukulele on app start
async function initUkuleleMode() {
  await loadUkuleleCurriculum();
  setupUkuleleUI();
}

function setupUkuleleUI() {
  const ukuleleBtn = document.getElementById('ukulele-nav-btn');
  if (ukuleleBtn) {
    ukuleleBtn.addEventListener('click', () => {
      switchView('ukulele-view');
    });
  }
  
  // Setup ukulele practice buttons
  const ukuleleCanvas = document.getElementById('ukulele-canvas');
  if (ukuleleCanvas) {
    ukuleleCanvas.style.display = 'none';
  }

  const ukuleleLessonsContainer = document.getElementById('ukulele-lessons-grid');
  if (ukuleleLessonsContainer) {
    renderUkuleleLessons();
  }
  
  // Setup song upload
  const uploadBtn = document.getElementById('ukulele-upload-btn');
  if (uploadBtn) {
    uploadBtn.addEventListener('click', () => {
      document.getElementById('ukulele-file-input').click();
    });
  }
  
  const fileInput = document.getElementById('ukulele-file-input');
  if (fileInput) {
    fileInput.addEventListener('change', handleUkuleleSongUpload);
  }

  const formatHelp = document.getElementById('ukulele-format-help');
  if (formatHelp) {
    formatHelp.addEventListener('click', () => {
      alert('Ukulele song upload uses JSON with title, artist, tempo, and chords. Each chord needs name, startBeat, and duration. See UKULELE_GUIDE.md for examples.');
    });
  }
}

function renderUkuleleLessons() {
  const container = document.getElementById('ukulele-lessons-grid');
  if (!container) return;
  
  container.innerHTML = '';
  
  ukuleleCurriculum.forEach(lesson => {
    const card = document.createElement('div');
    card.className = 'lesson-card';
    card.style.cssText = `
      background: rgba(0, 242, 254, 0.05);
      border: 1px solid rgba(0, 242, 254, 0.2);
      border-radius: 12px;
      padding: 1.2rem;
      cursor: pointer;
      transition: all 0.3s ease;
      margin-bottom: 1rem;
    `;
    
    card.innerHTML = `
      <div style="font-weight: bold; color: var(--neon-cyan); margin-bottom: 0.5rem;">
        <i class="fa-solid fa-guitar"></i> ${lesson.name}
      </div>
      <div style="font-size: 0.85rem; color: var(--text-secondary); margin-bottom: 0.8rem;">
        ${lesson.description}
      </div>
      <div style="display: flex; gap: 0.5rem; font-size: 0.75rem;">
        <span style="background: rgba(0, 242, 254, 0.2); padding: 0.25rem 0.6rem; border-radius: 4px; color: var(--neon-cyan);">
          ${lesson.skill}
        </span>
        <span style="background: rgba(100, 200, 100, 0.2); padding: 0.25rem 0.6rem; border-radius: 4px; color: #6cf06c;">
          ${lesson.difficulty}
        </span>
      </div>
    `;
    
    card.addEventListener('click', () => launchUkuleleLesson(lesson));
    card.addEventListener('mouseenter', () => {
      card.style.background = 'rgba(0, 242, 254, 0.1)';
      card.style.borderColor = 'rgba(0, 242, 254, 0.5)';
    });
    card.addEventListener('mouseleave', () => {
      card.style.background = 'rgba(0, 242, 254, 0.05)';
      card.style.borderColor = 'rgba(0, 242, 254, 0.2)';
    });
    
    container.appendChild(card);
  });
}

async function launchUkuleleLesson(lesson) {
  if (!ukuleleEngine) {
    alert('Ukulele engine not initialized');
    return;
  }
  
  // Show ukulele canvas and hide generic game canvas
  const ukuleleCanvas = document.getElementById('ukulele-canvas');
  if (ukuleleCanvas) {
    ukuleleCanvas.style.display = 'block';
  }
  const gameCanvas = document.getElementById('game-canvas');
  if (gameCanvas) {
    gameCanvas.style.display = 'none';
  }
  
  // Initialize audio
  try {
    if (audio && typeof audio.init === 'function') {
      await audio.init();
    }
  } catch (err) {
    alert('Microphone access required: ' + err.message);
    return;
  }
  
  // Start ukulele lesson
  switchView('ukulele-view');
  
  setTimeout(() => {
    ukuleleEngine.start({
      type: lesson.type,
      targetChord: lesson.targetChord,
      targetString: lesson.targetString,
      targetFret: lesson.targetFret,
      lesson: lesson
    });
  }, 300);
}

// Handle song upload and chord detection
async function handleUkuleleSongUpload(event) {
  const file = event.target.files[0];
  if (!file) return;
  
  const fileName = file.name;
  const fileType = file.type;
  
  console.log(`[Ukulele] Uploading: ${fileName} (${fileType})`);
  
  // For MVP: Accept .json files with chord data
  if (fileType === 'application/json' || fileName.endsWith('.json')) {
    handleJsonChordFile(file);
  } 
  // Could expand to: MP3, WAV, ABC notation, etc.
  else {
    alert('Supported formats: JSON chord files (.json)');
  }
}

async function handleJsonChordFile(file) {
  try {
    const text = await file.text();
    const data = JSON.parse(text);
    
    // Expected format: { title, chords: [{ name, startBeat, duration }, ...] }
    if (!data.chords || !Array.isArray(data.chords)) {
      alert('Invalid format. Expected: { title, chords: [...] }');
      return;
    }
    
    // Generate practice level from chords
    const generatedLesson = generateLessonFromChords(data);
    
    console.log('[Ukulele] Generated lesson:', generatedLesson);
    
    // Save to local storage
    let savedSongs = JSON.parse(localStorage.getItem('ukuleleSongs') || '[]');
    savedSongs.push(generatedLesson);
    localStorage.setItem('ukuleleSongs', JSON.stringify(savedSongs));
    
    // Show success and refresh
    alert(`✅ Loaded: ${data.title}\n\nChord progression ready to practice!`);
    renderUkuleleLessons();
    renderSavedUkuleleSongs();
    
  } catch (err) {
    alert('Error reading file: ' + err.message);
  }
}

function generateLessonFromChords(songData) {
  // Convert chord progression to lesson format
  const chords = songData.chords;
  const tempo = songData.tempo || 120;
  const beatDuration = (60 / tempo) * 1000; // ms per beat
  
  // Create chord progression lesson
  const lesson = {
    id: 'custom_' + Date.now(),
    instrument: 'ukulele',
    name: songData.title || 'Custom Song',
    type: 'chord_progression',
    chordSequence: chords.map(c => c.name),
    chordDurations: chords.map(c => (c.duration || 4) * beatDuration),
    skill: 'Custom Progression',
    difficulty: 'Custom',
    description: `Practice "${songData.title}" by following the chord progression.`,
    ukuleleData: {
      strings: ['G4', 'C4', 'E4', 'A4'],
      tempo: tempo,
      songTitle: songData.title
    }
  };
  
  return lesson;
}

function renderSavedUkuleleSongs() {
  const container = document.getElementById('saved-ukulele-songs');
  if (!container) return;
  
  const savedSongs = JSON.parse(localStorage.getItem('ukuleleSongs') || '[]');
  
  if (savedSongs.length === 0) {
    container.innerHTML = '<p style="color: var(--text-muted);">No custom songs uploaded yet.</p>';
    return;
  }
  
  container.innerHTML = '';
  
  savedSongs.forEach(song => {
    const card = document.createElement('div');
    card.className = 'saved-song-card';
    card.style.cssText = `
      background: rgba(200, 100, 255, 0.05);
      border: 1px solid rgba(200, 100, 255, 0.2);
      border-radius: 12px;
      padding: 1rem;
      margin-bottom: 0.8rem;
      cursor: pointer;
    `;
    
    const titleEl = document.createElement('div');
    titleEl.style.cssText = 'font-weight: bold; color: var(--neon-purple); display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.5rem;';
    titleEl.innerHTML = `<i class="fa-solid fa-music"></i> ${song.name}`;

    const detailsEl = document.createElement('div');
    detailsEl.style.cssText = 'font-size: 0.8rem; color: var(--text-secondary); margin-bottom: 0.8rem;';
    detailsEl.textContent = `${song.chordSequence?.length || 0} chords • Tempo: ${song.ukuleleData?.tempo || 120} BPM`;

    const practiceBtn = document.createElement('button');
    practiceBtn.className = 'btn btn-primary btn-sm';
    practiceBtn.style.marginRight = '0.5rem';
    practiceBtn.textContent = 'Practice';
    practiceBtn.addEventListener('click', () => launchUkuleleLesson(song));

    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'btn btn-danger btn-sm';
    deleteBtn.textContent = 'Delete';
    deleteBtn.addEventListener('click', () => deleteUkuleleSong(song.id));

    const actionRow = document.createElement('div');
    actionRow.style.display = 'flex';
    actionRow.style.gap = '0.5rem';
    actionRow.appendChild(practiceBtn);
    actionRow.appendChild(deleteBtn);

    card.appendChild(titleEl);
    card.appendChild(detailsEl);
    card.appendChild(actionRow);
    container.appendChild(card);
  });
}

function deleteUkuleleSong(songId) {
  if (confirm('Delete this song?')) {
    let savedSongs = JSON.parse(localStorage.getItem('ukuleleSongs') || '[]');
    savedSongs = savedSongs.filter(s => s.id !== songId);
    localStorage.setItem('ukuleleSongs', JSON.stringify(savedSongs));
    renderSavedUkuleleSongs();
  }
}

// Main setup function for integration with app.js
async function setupUkuleleMode() {
  try {
    await loadUkuleleCurriculum();
    setupUkuleleUI();
    renderUkuleleLessons();
    renderSavedUkuleleSongs();
    console.log('[Ukulele] Mode initialized successfully');
  } catch (err) {
    console.error('[Ukulele] Setup failed:', err);
  }
}

// Initialize on app load
window.addEventListener('DOMContentLoaded', () => {
  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    if (typeof setupUkuleleMode === 'undefined') {
      console.warn('[Ukulele] setupUkuleleMode not yet available');
    }
  }
});
