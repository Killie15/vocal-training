// Sheet Music Practice Engine (Voice & Ukulele Dual Grading) - PitchFlight

class SheetMusicEngine {
  constructor(canvasId) {
    this.canvas = document.getElementById(canvasId);
    this.ctx = this.canvas.getContext('2d');
    this.isActive = false;
    this.time = 0;
    this.scrollSpeed = 80; // pixels per second
    this.playheadX = 200; // vertical playhead position
    
    // Treble Clef staff configuration
    this.staffCenterY = 160;
    this.space = 14; // space between staff lines
    
    // Active song definitions
    this.currentSong = null;
    this.notes = []; // [{ midi, time, duration, name, chord }]
    this.chords = []; // unique list of chord changes
    this.userPitchTrail = []; // trailing voice pitches [{ time, midi, y }]
    
    // Performance scoring
    this.voiceMatchFrames = 0;
    this.voiceTotalFrames = 0;
    this.voiceCentsDiffs = [];
    this.chordMatchBeats = 0;
    this.chordTotalBeats = 0;
    
    // Chord check timers (avoids checking every millisecond)
    this.lastChordCheckTime = 0;
    this.checkedBeats = new Set(); // set of beat seconds already scored
    
    // Voice feedback rate limiter (prevents hyper-reactivity)
    this.lastFeedbackUpdateTime = 0;
    this.feedbackUpdateInterval = 500; // ms (0.5 second smoothing window)
    this.cachedVoiceFeedback = "WAITING...";
    this.cachedVoiceColor = "var(--text-muted)";
    
    // Animation frame
    this.animationId = null;
    this.lastTimestamp = null;
    
    // Preset songs database
    this.songs = {
      "amazing-grace": {
        name: "Amazing Grace (G Major)",
        chordsUsed: ["G", "C", "D", "Em"],
        notes: [
          { midi: 62, time: 1.0, duration: 1.2, name: "D4", chord: "G" },
          { midi: 67, time: 2.5, duration: 2.0, name: "G4", chord: "G" },
          { midi: 71, time: 5.0, duration: 0.8, name: "B4", chord: "G" },
          { midi: 67, time: 6.0, duration: 1.2, name: "G4", chord: "G" },
          { midi: 71, time: 7.5, duration: 2.2, name: "B4", chord: "C" },
          { midi: 69, time: 10.0, duration: 1.2, name: "A4", chord: "G" },
          { midi: 67, time: 11.5, duration: 2.2, name: "G4", chord: "G" },
          { midi: 64, time: 14.0, duration: 1.2, name: "E4", chord: "C" },
          { midi: 62, time: 15.5, duration: 2.5, name: "D4", chord: "G" },
          
          { midi: 62, time: 18.5, duration: 1.2, name: "D4", chord: "G" },
          { midi: 67, time: 20.0, duration: 2.0, name: "G4", chord: "G" },
          { midi: 71, time: 22.5, duration: 0.8, name: "B4", chord: "G" },
          { midi: 67, time: 23.5, duration: 1.2, name: "G4", chord: "G" },
          { midi: 71, time: 25.0, duration: 2.0, name: "B4", chord: "C" },
          { midi: 69, time: 27.5, duration: 1.0, name: "A4", chord: "D" },
          { midi: 74, time: 29.0, duration: 2.5, name: "D5", chord: "D" },
          
          { midi: 71, time: 32.0, duration: 2.0, name: "B4", chord: "Em" },
          { midi: 74, time: 34.5, duration: 0.8, name: "D5", chord: "G" },
          { midi: 71, time: 35.5, duration: 1.2, name: "B4", chord: "G" },
          { midi: 67, time: 37.0, duration: 2.0, name: "G4", chord: "G" },
          { midi: 62, time: 39.5, duration: 1.0, name: "D4", chord: "D" },
          { midi: 64, time: 41.0, duration: 2.0, name: "E4", chord: "C" },
          { midi: 67, time: 43.5, duration: 0.8, name: "G4", chord: "G" },
          { midi: 67, time: 44.5, duration: 1.2, name: "G4", chord: "G" },
          { midi: 64, time: 46.0, duration: 1.2, name: "E4", chord: "C" },
          { midi: 62, time: 47.5, duration: 2.5, name: "D4", chord: "G" }
        ]
      },
      "happy-birthday": {
        name: "Happy Birthday (C Major)",
        chordsUsed: ["C", "G", "F"],
        notes: [
          { midi: 67, time: 1.0, duration: 0.5, name: "G4", chord: "C" },
          { midi: 67, time: 1.6, duration: 0.5, name: "G4", chord: "C" },
          { midi: 69, time: 2.2, duration: 1.0, name: "A4", chord: "C" },
          { midi: 67, time: 3.4, duration: 1.0, name: "G4", chord: "C" },
          { midi: 72, time: 4.6, duration: 1.0, name: "C5", chord: "G" },
          { midi: 71, time: 5.8, duration: 1.8, name: "B4", chord: "G" },
          
          { midi: 67, time: 8.0, duration: 0.5, name: "G4", chord: "G" },
          { midi: 67, time: 8.6, duration: 0.5, name: "G4", chord: "G" },
          { midi: 69, time: 9.2, duration: 1.0, name: "A4", chord: "G" },
          { midi: 67, time: 10.4, duration: 1.0, name: "G4", chord: "G" },
          { midi: 74, time: 11.6, duration: 1.0, name: "D5", chord: "C" },
          { midi: 72, time: 12.8, duration: 1.8, name: "C5", chord: "C" },
          
          { midi: 67, time: 15.0, duration: 0.5, name: "G4", chord: "C" },
          { midi: 67, time: 15.6, duration: 0.5, name: "G4", chord: "C" },
          { midi: 79, time: 16.2, duration: 1.0, name: "G5", chord: "F" },
          { midi: 76, time: 17.4, duration: 1.0, name: "E5", chord: "F" },
          { midi: 72, time: 18.6, duration: 1.0, name: "C5", chord: "F" },
          { midi: 71, time: 19.8, duration: 1.0, name: "B4", chord: "F" },
          { midi: 69, time: 21.0, duration: 1.5, name: "A4", chord: "F" },
          
          { midi: 77, time: 23.0, duration: 0.5, name: "F5", chord: "C" },
          { midi: 77, time: 23.6, duration: 0.5, name: "F5", chord: "C" },
          { midi: 76, time: 24.2, duration: 1.0, name: "E5", chord: "C" },
          { midi: 72, time: 25.4, duration: 1.0, name: "C5", chord: "G" },
          { midi: 74, time: 26.6, duration: 1.0, name: "D5", chord: "G" },
          { midi: 72, time: 27.8, duration: 2.0, name: "C5", chord: "C" }
        ]
      },
      "twinkle": {
        name: "Twinkle Twinkle Little Star (C Major)",
        chordsUsed: ["C", "F", "G"],
        notes: [
          { midi: 60, time: 1.0, duration: 0.8, name: "C4", chord: "C" },
          { midi: 60, time: 2.0, duration: 0.8, name: "C4", chord: "C" },
          { midi: 67, time: 3.0, duration: 0.8, name: "G4", chord: "C" },
          { midi: 67, time: 4.0, duration: 0.8, name: "G4", chord: "C" },
          { midi: 69, time: 5.0, duration: 0.8, name: "A4", chord: "F" },
          { midi: 69, time: 6.0, duration: 0.8, name: "A4", chord: "F" },
          { midi: 67, time: 7.0, duration: 1.6, name: "G4", chord: "C" },
          
          { midi: 65, time: 9.0, duration: 0.8, name: "F4", chord: "F" },
          { midi: 65, time: 10.0, duration: 0.8, name: "F4", chord: "F" },
          { midi: 64, time: 11.0, duration: 0.8, name: "E4", chord: "C" },
          { midi: 64, time: 12.0, duration: 0.8, name: "E4", chord: "C" },
          { midi: 62, time: 13.0, duration: 0.8, name: "D4", chord: "G" },
          { midi: 62, time: 14.0, duration: 0.8, name: "D4", chord: "G" },
          { midi: 60, time: 15.0, duration: 1.6, name: "C4", chord: "C" },
          
          { midi: 67, time: 17.0, duration: 0.8, name: "G4", chord: "G" },
          { midi: 67, time: 18.0, duration: 0.8, name: "G4", chord: "G" },
          { midi: 65, time: 19.0, duration: 0.8, name: "F4", chord: "C" },
          { midi: 65, time: 20.0, duration: 0.8, name: "F4", chord: "C" },
          { midi: 64, time: 21.0, duration: 0.8, name: "E4", chord: "G" },
          { midi: 64, time: 22.0, duration: 0.8, name: "E4", chord: "G" },
          { midi: 62, time: 23.0, duration: 1.6, name: "D4", chord: "C" }
        ]
      },
      "auld-lang-syne": {
        name: "Auld Lang Syne (C Major)",
        chordsUsed: ["F", "C", "G", "Am"],
        notes: [
          { midi: 67, time: 1.0, duration: 0.6, name: "G4", chord: "C" },
          { midi: 72, time: 1.8, duration: 1.0, name: "C5", chord: "C" },
          { midi: 72, time: 3.0, duration: 0.5, name: "C5", chord: "C" },
          { midi: 72, time: 3.6, duration: 0.8, name: "C5", chord: "C" },
          { midi: 76, time: 4.6, duration: 1.0, name: "E5", chord: "C" },
          { midi: 74, time: 5.8, duration: 1.0, name: "D5", chord: "G" },
          { midi: 72, time: 7.0, duration: 0.5, name: "C5", chord: "G" },
          { midi: 74, time: 7.6, duration: 0.8, name: "D5", chord: "G" },
          
          { midi: 76, time: 8.6, duration: 1.0, name: "E5", chord: "C" },
          { midi: 72, time: 9.8, duration: 0.8, name: "C5", chord: "C" },
          { midi: 72, time: 10.8, duration: 0.5, name: "C5", chord: "C" },
          { midi: 76, time: 11.4, duration: 0.8, name: "E5", chord: "C" },
          { midi: 79, time: 12.4, duration: 1.5, name: "G5", chord: "F" },
          { midi: 81, time: 14.0, duration: 2.0, name: "A5", chord: "F" },
          
          { midi: 81, time: 16.5, duration: 1.0, name: "A5", chord: "C" },
          { midi: 79, time: 17.7, duration: 1.0, name: "G5", chord: "C" },
          { midi: 76, time: 18.9, duration: 0.8, name: "E5", chord: "C" },
          { midi: 76, time: 19.8, duration: 0.5, name: "E5", chord: "C" },
          { midi: 72, time: 20.4, duration: 1.0, name: "C5", chord: "Am" },
          { midi: 74, time: 21.6, duration: 0.8, name: "D5", chord: "G" },
          { midi: 72, time: 22.6, duration: 0.5, name: "C5", chord: "G" },
          { midi: 74, time: 23.2, duration: 0.8, name: "D5", chord: "G" },
          
          { midi: 76, time: 24.2, duration: 1.0, name: "E5", chord: "Am" },
          { midi: 72, time: 25.4, duration: 0.8, name: "C5", chord: "Am" },
          { midi: 69, time: 26.4, duration: 0.5, name: "A4", chord: "F" },
          { midi: 69, time: 27.0, duration: 0.8, name: "A4", chord: "F" },
          { midi: 67, time: 28.0, duration: 1.5, name: "G4", chord: "C" },
          { midi: 72, time: 29.8, duration: 2.0, name: "C5", chord: "C" }
        ]
      }
    };
    
    this.canvas.addEventListener('click', (e) => this.handleCanvasClick(e));
  }

  // Set sizing parameters for high-DPI screens
  resize() {
    const rect = this.canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = rect.width * dpr;
    this.canvas.height = rect.height * dpr;
    this.ctx.scale(dpr, dpr);
  }

  start(songKey) {
    this.isActive = true;
    this.time = 0;
    this.currentSong = this.songs[songKey];
    this.notes = JSON.parse(JSON.stringify(this.currentSong.notes));
    this.userPitchTrail = [];
    
    // Guide tone properties
    this.playReferenceTone = document.getElementById('sheet-guide-checkbox')?.checked || false;
    this.activeGuideTone = null;
    this.lastPlayingNoteId = null;
    
    // Reset stats
    this.voiceMatchFrames = 0;
    this.voiceTotalFrames = 0;
    this.voiceCentsDiffs = [];
    this.chordMatchBeats = 0;
    this.chordTotalBeats = 0;
    this.checkedBeats.clear();
    this.lastChordCheckTime = 0;
    
    this.resize();
    
    // Hide results initially
    document.getElementById('sheet-report-card').style.display = 'none';
    
    // Toggle button visual state
    const btn = document.getElementById('sheet-toggle-btn');
    btn.innerHTML = '<i class="fa-solid fa-square"></i> Stop Practice';
    btn.className = 'btn btn-accent';
    
    // Reset rate-limiter caches
    this.lastFeedbackUpdateTime = 0;
    this.cachedVoiceFeedback = "SING THE NOTES!";
    this.cachedVoiceColor = 'var(--neon-cyan)';
    
    // Update live HUD displays initially
    document.getElementById('sheet-live-note').innerText = "--";
    document.getElementById('sheet-live-chord').innerText = "--";
    document.getElementById('sheet-live-cents').innerText = "-- cents";
    document.getElementById('sheet-voice-feedback').innerText = "SING THE NOTES!";
    document.getElementById('sheet-voice-feedback').style.color = 'var(--neon-cyan)';
    document.getElementById('sheet-chord-feedback').innerText = "STRUM CHORDS!";
    document.getElementById('sheet-chord-feedback').style.color = 'var(--neon-purple)';
    
    this.lastTimestamp = performance.now();
    this.animationId = requestAnimationFrame((t) => this.loop(t));
  }

  stop() {
    this.isActive = false;
    if (this.animationId) {
      cancelAnimationFrame(this.animationId);
      this.animationId = null;
    }
    
    // Stop guide tones if active
    if (this.activeGuideTone) {
      try { this.activeGuideTone.osc.stop(); } catch(e) {}
      this.activeGuideTone = null;
    }
    this.lastPlayingNoteId = null;
    
    const btn = document.getElementById('sheet-toggle-btn');
    if (btn) {
      btn.innerHTML = '<i class="fa-solid fa-microphone"></i> Start Practice';
      btn.className = 'btn btn-primary';
    }
  }

  loop(timestamp) {
    if (!this.isActive) return;

    const dt = (timestamp - this.lastTimestamp) / 1000;
    this.lastTimestamp = timestamp;

    this.update(dt);
    this.draw();

    // Check if the exercise is complete (1.5 seconds past the last note)
    const lastNote = this.notes[this.notes.length - 1];
    const endTime = lastNote ? (lastNote.time + lastNote.duration + 1.5) : 10;

    if (this.time >= endTime) {
      this.completeSession();
    } else {
      this.animationId = requestAnimationFrame((t) => this.loop(t));
    }
  }

  update(dt) {
    this.time += dt;
    
    // 1. Fetch Pitch & Chord from window audio engine
    const pitchData = window.audioEngine.detectPitch();
    const chordData = window.audioEngine.getDetectedChord();
    
    // Find if a note is active under playhead (at this.time)
    const activeNote = this.findNoteAtTime(this.time);
    
    // HUD Live Update notes and chords
    document.getElementById('sheet-live-note').innerText = pitchData.note;
    document.getElementById('sheet-live-chord').innerText = chordData.chordName;
    
    // 2. Assess Vocal Pitch (Melody)
    let cents = pitchData.cents;
    let voiceText = "LISTENING...";
    let voiceColor = 'var(--text-muted)';
    
    if (activeNote) {
      this.voiceTotalFrames++;
      
      if (pitchData.frequency > 0) {
        cents = window.audioEngine.getCentsDeviation(pitchData.frequency, activeNote.midi);
        const centsAbs = Math.abs(cents);
        this.voiceCentsDiffs.push(centsAbs);
        
        document.getElementById('sheet-live-cents').innerText = `${cents > 0 ? '+' : ''}${cents} cents`;
        
        if (centsAbs <= 15) {
          this.voiceMatchFrames++;
          voiceText = "IN TUNE! 🌟";
          voiceColor = 'var(--neon-green)';
        } else if (cents < -15) {
          if (centsAbs <= 40) this.voiceMatchFrames++;
          voiceText = "TOO FLAT! ⬇️";
          voiceColor = 'var(--neon-orange)';
        } else {
          if (centsAbs <= 40) this.voiceMatchFrames++;
          voiceText = "TOO SHARP! ⬆️";
          voiceColor = 'var(--neon-purple)';
        }
      } else {
        document.getElementById('sheet-live-cents').innerText = "-- cents";
        voiceText = `SING: ${activeNote.name}`;
        voiceColor = 'var(--neon-cyan)';
      }
    } else {
      document.getElementById('sheet-live-cents').innerText = "-- cents";
      voiceText = "BREATH";
      voiceColor = 'var(--text-muted)';
    }

    // Rate-limit DOM voice feedback updates (makes it less reactive / hyperactive)
    const now = performance.now();
    if (now - this.lastFeedbackUpdateTime > this.feedbackUpdateInterval || voiceText.startsWith("SING:")) {
      this.cachedVoiceFeedback = voiceText;
      this.cachedVoiceColor = voiceColor;
      this.lastFeedbackUpdateTime = now;
      
      const voiceEl = document.getElementById('sheet-voice-feedback');
      if (voiceEl) {
        voiceEl.innerText = this.cachedVoiceFeedback;
        voiceEl.style.color = this.cachedVoiceColor;
      }
    }

    // 3. Assess Ukulele Strum Chords
    let chordText = "WAITING...";
    let chordColor = 'var(--text-muted)';

    if (activeNote && activeNote.chord) {
      // Score beat once per block
      const currentBeatId = Math.floor(this.time * 2);
      const isMatch = this.checkChordMatch(activeNote.chord, chordData.chordName);
      
      if (!this.checkedBeats.has(currentBeatId)) {
        this.chordTotalBeats++;
        if (isMatch) this.chordMatchBeats++;
        this.checkedBeats.add(currentBeatId);
      }

      if (isMatch) {
        chordText = `MATCHED ${activeNote.chord}! 🎸`;
        chordColor = 'var(--neon-green)';
      } else {
        chordText = `STRUM: ${activeNote.chord}`;
        chordColor = 'var(--neon-purple)';
      }
    } else {
      // Cue upcoming chord
      const nextNote = this.notes.find(n => n.time > this.time);
      if (nextNote && nextNote.chord) {
        chordText = `NEXT: Strum ${nextNote.chord}`;
        chordColor = 'rgba(255, 255, 255, 0.4)';
      }
    }

    const chordEl = document.getElementById('sheet-chord-feedback');
    if (chordEl) {
      chordEl.innerText = chordText;
      chordEl.style.color = chordColor;
    }
    
    // 3.5 Live Reference Guide Tone synthesis
    if (this.playReferenceTone && activeNote) {
      if (this.lastPlayingNoteId !== activeNote.time) {
        if (this.activeGuideTone) {
          try { this.activeGuideTone.osc.stop(); } catch(e) {}
          this.activeGuideTone = null;
        }
        // Play guide tone for the exact note duration
        this.activeGuideTone = window.audioEngine.playGuideTone(activeNote.midi, activeNote.duration);
        this.lastPlayingNoteId = activeNote.time;
      }
    } else if (!activeNote && this.activeGuideTone) {
      try { this.activeGuideTone.osc.stop(); } catch(e) {}
      this.activeGuideTone = null;
      this.lastPlayingNoteId = null;
    }

    // 4. Append User pitch points to trail history
    const trailY = (pitchData.frequency > 0) ? this.getNoteYCoordinate(pitchData.midi) : null;
    this.userPitchTrail.push({
      time: this.time,
      y: trailY,
      midi: pitchData.frequency > 0 ? pitchData.midi : null
    });
    
    // Limit trail history length (keep last 300 frames, ~5 seconds)
    if (this.userPitchTrail.length > 300) {
      this.userPitchTrail.shift();
    }
  }

  draw() {
    const canvasWidth = this.canvas.width / (window.devicePixelRatio || 1);
    const canvasHeight = this.canvas.height / (window.devicePixelRatio || 1);
    
    // Clear
    this.ctx.fillStyle = '#05050b';
    this.ctx.fillRect(0, 0, canvasWidth, canvasHeight);

    // 1. Draw Clef Staff Lines (5 horizontal lines centered vertically)
    this.ctx.strokeStyle = 'rgba(255, 255, 255, 0.1)';
    this.ctx.lineWidth = 1.5;
    
    for (let i = -2; i <= 2; i++) {
      const yVal = this.staffCenterY + i * this.space;
      this.ctx.beginPath();
      this.ctx.moveTo(40, yVal);
      this.ctx.lineTo(canvasWidth - 40, yVal);
      this.ctx.stroke();
    }
    
    // 2. Draw Treble Clef unicode symbol
    this.ctx.save();
    this.ctx.fillStyle = 'var(--neon-cyan)';
    this.ctx.font = '68px Outfit';
    this.ctx.shadowBlur = 10;
    this.ctx.shadowColor = 'rgba(0, 242, 254, 0.4)';
    this.ctx.fillText('𝄞', 50, this.staffCenterY + 22);
    this.ctx.restore();
    
    // 3. Draw Scrolling Noteheads and stems
    this.notes.forEach(note => {
      // Coordinate horizontal layout: offset relative to playheadX based on time
      const x = this.playheadX + (note.time - this.time) * this.scrollSpeed;
      const step = this.getMidiStep(note.midi);
      const y = this.getNoteYCoordinate(note.midi);
      
      const width = note.duration * this.scrollSpeed;
      
      // Draw target note if on screen
      if (x + width > 40 && x < canvasWidth - 40) {
        const isCurrent = this.time >= note.time && this.time <= (note.time + note.duration);
        
        this.ctx.save();
        
        // Highlight active notes
        if (isCurrent) {
          this.ctx.fillStyle = '#00ff87';
          this.ctx.shadowBlur = 12;
          this.ctx.shadowColor = 'rgba(0, 255, 135, 0.5)';
        } else if (note.time < this.time) {
          // Played notes
          this.ctx.fillStyle = 'rgba(255, 255, 255, 0.15)';
        } else {
          // Future notes
          this.ctx.fillStyle = 'var(--text-primary)';
        }
        
        // Draw ledger lines if below staff (step <= -6, C4 is -6)
        if (step <= -6) {
          this.ctx.strokeStyle = isCurrent ? '#00ff87' : 'rgba(255, 255, 255, 0.4)';
          this.ctx.lineWidth = 1.5;
          // Draw ledger line segment
          this.ctx.beginPath();
          this.ctx.moveTo(x - 14, this.staffCenterY + 3 * this.space);
          this.ctx.lineTo(x + 14, this.staffCenterY + 3 * this.space);
          this.ctx.stroke();
        }
        
        // Draw notehead (ellipse rotated slightly)
        const rx = 9;
        const ry = 6;
        
        if (note.isClicked) {
          this.ctx.save();
          this.ctx.fillStyle = '#00f2fe';
          this.ctx.shadowBlur = 20;
          this.ctx.shadowColor = '#00f2fe';
        }
        
        this.ctx.beginPath();
        this.ctx.ellipse(x, y, rx, ry, -20 * Math.PI / 180, 0, Math.PI * 2);
        this.ctx.fill();
        
        if (note.isClicked) {
          this.ctx.restore();
        }
        
        // Draw vertical note stem
        this.ctx.strokeStyle = this.ctx.fillStyle;
        this.ctx.lineWidth = 1.5;
        this.ctx.beginPath();
        if (step >= 0) { // stem down on left
          this.ctx.moveTo(x - rx + 1.5, y);
          this.ctx.lineTo(x - rx + 1.5, y + 36);
        } else { // stem up on right
          this.ctx.moveTo(x + rx - 1.5, y);
          this.ctx.lineTo(x + rx - 1.5, y - 36);
        }
        this.ctx.stroke();
        
        // Draw Chord indicator above note (in light-purple)
        if (note.chord) {
          this.ctx.fillStyle = isCurrent ? 'var(--neon-purple)' : 'rgba(185, 39, 252, 0.45)';
          this.ctx.font = 'bold 13px Outfit';
          this.ctx.textAlign = 'center';
          this.ctx.fillText(note.chord, x, this.staffCenterY - 3.8 * this.space);
        }
        
        this.ctx.restore();
      }
    });
    
    // 4. Draw User Sung Pitch real-time trail directly on staff
    if (this.userPitchTrail.length > 1) {
      this.ctx.save();
      this.ctx.lineWidth = 3.5;
      
      // Use glowing purple/pink trail for pitch curves
      this.ctx.strokeStyle = '#b927fc';
      this.ctx.shadowBlur = 12;
      this.ctx.shadowColor = 'rgba(185, 39, 252, 0.5)';
      this.ctx.beginPath();
      
      let drawing = false;
      for (let i = 0; i < this.userPitchTrail.length; i++) {
        const pt = this.userPitchTrail[i];
        
        // Map trail point coordinate: scrolling to left
        const xVal = this.playheadX + (pt.time - this.time) * this.scrollSpeed;
        
        if (pt.y === null || xVal < 40 || xVal > canvasWidth) {
          if (drawing) {
            this.ctx.stroke();
            drawing = false;
          }
          continue;
        }
        
        if (!drawing) {
          this.ctx.beginPath();
          this.ctx.moveTo(xVal, pt.y);
          drawing = true;
        } else {
          this.ctx.lineTo(xVal, pt.y);
        }
      }
      
      if (drawing) {
        this.ctx.stroke();
      }
      this.ctx.restore();
    }
    
    // 5. Draw vertical target playhead line in cyan
    this.ctx.save();
    this.ctx.strokeStyle = 'rgba(0, 242, 254, 0.4)';
    this.ctx.lineWidth = 2.5;
    this.ctx.setLineDash([4, 4]);
    this.ctx.beginPath();
    this.ctx.moveTo(this.playheadX, 30);
    this.ctx.lineTo(this.playheadX, canvasHeight - 30);
    this.ctx.stroke();
    
    // Small triangle playhead marker at top
    this.ctx.fillStyle = 'var(--neon-cyan)';
    this.ctx.beginPath();
    this.ctx.moveTo(this.playheadX - 6, 22);
    this.ctx.lineTo(this.playheadX + 6, 22);
    this.ctx.lineTo(this.playheadX, 30);
    this.ctx.closePath();
    this.ctx.fill();
    this.ctx.restore();
  }

  // Complete session & calculate final grade card
  completeSession() {
    this.stop();
    
    // 1. Voice accuracy
    const voiceAcc = this.voiceTotalFrames > 0 
      ? Math.round((this.voiceMatchFrames / this.voiceTotalFrames) * 100)
      : 0;
      
    // Average cents deviation
    let avgCents = 0;
    if (this.voiceCentsDiffs.length > 0) {
      const sum = this.voiceCentsDiffs.reduce((a,b)=>a+b, 0);
      avgCents = Math.round(sum / this.voiceCentsDiffs.length);
    } else {
      avgCents = 50;
    }
    
    // 2. Chord accuracy
    const chordAcc = this.chordTotalBeats > 0 
      ? Math.round((this.chordMatchBeats / this.chordTotalBeats) * 100)
      : 0;
      
    // 3. Combined grade
    const totalScore = (voiceAcc * 0.5) + (chordAcc * 0.5);
    let grade = "F";
    if (totalScore >= 95) grade = "A+";
    else if (totalScore >= 88) grade = "A";
    else if (totalScore >= 80) grade = "B";
    else if (totalScore >= 70) grade = "C";
    else if (totalScore >= 55) grade = "D";
    
    // 4. Generate coaching feedback
    let feedback = "";
    if (totalScore < 40) {
      feedback = "Keep practicing! Singing and playing simultaneously is a high-level skill that requires muscle memory. Try humming the melody first before adding ukulele chords.";
    } else if (totalScore < 70) {
      feedback = `Good progress! Your voice accuracy was decent (${voiceAcc}%), but your ukulele chord alignment was flat in a few transitions. Strum the chords precisely as they cross the playhead line.`;
    } else if (totalScore < 88) {
      feedback = `Great coordination! You maintained a steady tempo and played ${this.chordMatchBeats} of ${this.chordTotalBeats} chords correctly. Focus on smoothing out high notes (${avgCents} cents deviation) for an A grade.`;
    } else {
      feedback = `Sensational coordination! You are humming/singing and strumming in perfect synchronization like a seasoned performer. Intonation error is minimal (${avgCents} cents) and ukulele chords are flawless!`;
    }
    
    // 5. Update Report Card UI
    document.getElementById('sheet-grade-val').innerText = grade;
    document.getElementById('sheet-res-voice-acc').innerText = `${voiceAcc}%`;
    document.getElementById('sheet-res-voice-cents').innerText = `Avg Deviation: ${avgCents} cents`;
    document.getElementById('sheet-res-chord-acc').innerText = `${chordAcc}%`;
    document.getElementById('sheet-res-chord-count').innerText = `${this.chordMatchBeats} of ${this.chordTotalBeats} beats matched`;
    document.getElementById('sheet-res-feedback-text').innerText = feedback;
    
    // Display Report Card
    document.getElementById('sheet-report-card').style.display = 'flex';
  }

  // MIDI Note to Treble staff line steps (C4 is -6, B4 is 0, G5 is 5)
  getMidiStep(midi) {
    const stepsMap = {
      60: -6, // C4 (Ledger line)
      61: -6, // C#4
      62: -5, // D4
      63: -5, // D#4
      64: -4, // E4 (Line 1)
      65: -3, // F4
      66: -3, // F#4
      67: -2, // G4 (Line 2)
      68: -2, // G#4
      69: -1, // A4
      70: -1, // A#4
      71: 0,  // B4 (Line 3)
      72: 1,  // C5
      73: 1,  // C#5
      74: 2,  // D5 (Line 4)
      75: 2,  // D#5
      76: 3,  // E5
      77: 4,  // F5 (Line 5)
      78: 4,  // F#5
      79: 5   // G5
    };
    return stepsMap[midi] !== undefined ? stepsMap[midi] : 0;
  }

  getNoteYCoordinate(midi) {
    const step = this.getMidiStep(midi);
    return this.staffCenterY - (step * (this.space / 2));
  }

  findNoteAtTime(time) {
    return this.notes.find(note => time >= note.time && time <= (note.time + note.duration));
  }

  checkChordMatch(sheetChord, detectedChordName) {
    if (!sheetChord || !detectedChordName) return false;
    if (detectedChordName === "--" || detectedChordName === "Unknown Chord") return false;
    
    const cleanSheet = sheetChord.trim().toLowerCase();
    const cleanDetected = detectedChordName.trim().toLowerCase();
    
    if (cleanDetected.startsWith(cleanSheet)) return true;
    
    // Special Ukulele Chord maps (G major often triggers G7 or Gmaj7)
    if (cleanSheet === 'g' && cleanDetected.startsWith('g')) return true;
    if (cleanSheet === 'c' && cleanDetected.startsWith('c')) return true;
    if (cleanSheet === 'f' && cleanDetected.startsWith('f')) return true;
    if (cleanSheet === 'em' && cleanDetected.startsWith('em')) return true;
    if (cleanSheet === 'am' && cleanDetected.startsWith('am')) return true;
    
    return false;
  }

  handleCanvasClick(e) {
    if (!this.notes || this.notes.length === 0) return;
    
    const rect = this.canvas.getBoundingClientRect();
    const scaleX = this.canvas.width / (window.devicePixelRatio || 1) / rect.width;
    const scaleY = this.canvas.height / (window.devicePixelRatio || 1) / rect.height;
    
    const clickX = (e.clientX - rect.left) * scaleX;
    const clickY = (e.clientY - rect.top) * scaleY;
    
    for (let i = 0; i < this.notes.length; i++) {
      const note = this.notes[i];
      const x = this.playheadX + (note.time - this.time) * this.scrollSpeed;
      const y = this.getNoteYCoordinate(note.midi);
      
      const dist = Math.hypot(x - clickX, y - clickY);
      if (dist <= 22) {
        window.audioEngine.playGuideTone(note.midi, 0.8);
        
        note.isClicked = true;
        setTimeout(() => {
          note.isClicked = false;
        }, 800);
        break;
      }
    }
  }
}

window.SheetMusicEngine = SheetMusicEngine;
