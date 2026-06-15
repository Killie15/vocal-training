// Ukulele Training Engine - Extends PitchFlightGame for string instruments
// Handles chord detection, fret position tracking, and strumming patterns

class UkuleleTrainingEngine {
  constructor(canvasId) {
    this.canvas = document.getElementById(canvasId);
    this.ctx = this.canvas.getContext('2d');
    
    // Ukulele tuning (standard): G4, C4, E4, A4
    this.standardTuning = [67, 60, 64, 69]; // MIDI notes
    this.strings = [
      { name: 'G', midi: 67, frequency: 392.0 },
      { name: 'C', midi: 60, frequency: 261.6 },
      { name: 'E', midi: 64, frequency: 329.6 },
      { name: 'A', midi: 69, frequency: 440.0 }
    ];
    
    // Game state
    this.isActive = false;
    this.gameTime = 0;
    this.score = 0;
    this.lives = 3;
    this.starsEarned = 0;
    
    // Current exercise
    this.currentExerciseType = null; // "string_match", "chord_hold", "chord_progression", "strumming_pattern"
    this.currentChord = null;
    this.targetChord = null;
    this.detectedStrings = [];
    this.detectedChord = null;
    
    // Audio analysis
    this.micFrequencies = [];
    this.lastDetectedStrings = [];
    this.stringDetectionThreshold = 0.6; // Confidence level for string detection
    
    // UI elements
    this.fretboardX = 100;
    this.fretboardY = 150;
    this.fretboardWidth = 400;
    this.fretboardHeight = 100;
    this.frets = 12; // Visual frets to show
    
    // Scoring
    this.accuracy = 0;
    this.totalFrames = 0;
    this.correctFrames = 0;
  }

  // Render ukulele fretboard with current chord
  drawFretboard() {
    const ctx = this.ctx;
    const x = this.fretboardX;
    const y = this.fretboardY;
    const w = this.fretboardWidth;
    const h = this.fretboardHeight;
    const fretWidth = w / this.frets;
    const stringHeight = h / 4;
    
    // Draw frets (vertical lines)
    ctx.strokeStyle = 'rgba(100, 200, 255, 0.3)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= this.frets; i++) {
      const fretX = x + (i * fretWidth);
      ctx.beginPath();
      ctx.moveTo(fretX, y);
      ctx.lineTo(fretX, y + h);
      ctx.stroke();
    }
    
    // Draw strings (horizontal lines)
    ctx.strokeStyle = 'rgba(200, 150, 100, 0.8)';
    ctx.lineWidth = 3;
    for (let string = 0; string < 4; string++) {
      const stringY = y + (string * stringHeight) + stringHeight / 2;
      ctx.beginPath();
      ctx.moveTo(x, stringY);
      ctx.lineTo(x + w, stringY);
      ctx.stroke();
      
      // Label string notes
      ctx.fillStyle = 'var(--neon-cyan)';
      ctx.font = 'bold 12px Arial';
      ctx.fillText(this.strings[string].name, x - 30, stringY + 4);
    }
    
    // Draw target fingering positions (if chord_hold or chord_progression)
    if (this.targetChord && this.currentExerciseType.includes('chord')) {
      this.drawChordFingering(this.targetChord);
    }
    
    // Draw detected strings (user's playing)
    this.drawDetectedStrings();
  }

  drawChordFingering(chordName) {
    const chordDefinitions = {
      'C': { frets: [0, 0, 0, 3], fingers: ['open', 'open', 'open', 'index'] },
      'G': { frets: [0, 2, 3, 2], fingers: ['open', 'index', 'middle', 'ring'] },
      'F': { frets: [2, 0, 1, 0], fingers: ['index', 'open', 'middle', 'open'] },
      'Am': { frets: [0, 0, 0, 0], fingers: ['open', 'open', 'open', 'open'] }
    };
    
    const chord = chordDefinitions[chordName];
    if (!chord) return;
    
    const ctx = this.ctx;
    const x = this.fretboardX;
    const y = this.fretboardY;
    const w = this.fretboardWidth;
    const h = this.fretboardHeight;
    const fretWidth = w / this.frets;
    const stringHeight = h / 4;
    
    // Draw dots for fingering positions
    chord.frets.forEach((fret, stringIdx) => {
      if (fret > 0) {
        const dotX = x + (fret * fretWidth) - (fretWidth / 2);
        const dotY = y + (stringIdx * stringHeight) + stringHeight / 2;
        
        ctx.fillStyle = 'rgba(0, 242, 254, 0.7)';
        ctx.beginPath();
        ctx.arc(dotX, dotY, 8, 0, Math.PI * 2);
        ctx.fill();
        
        // Finger label
        ctx.fillStyle = '#000';
        ctx.font = 'bold 10px Arial';
        ctx.textAlign = 'center';
        ctx.fillText(chord.fingers[stringIdx][0].toUpperCase(), dotX, dotY + 3);
      } else {
        // Open string - draw circle outline
        const dotX = x + (fretWidth * 0.5);
        const dotY = y + (stringIdx * stringHeight) + stringHeight / 2;
        
        ctx.strokeStyle = 'rgba(0, 242, 254, 0.5)';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(dotX, dotY, 6, 0, Math.PI * 2);
        ctx.stroke();
      }
    });
  }

  drawDetectedStrings() {
    const ctx = this.ctx;
    const x = this.fretboardX;
    const y = this.fretboardY;
    const h = this.fretboardHeight;
    const stringHeight = h / 4;
    
    // Show which strings user is playing
    this.detectedStrings.forEach((stringIdx, idx) => {
      const stringY = y + (stringIdx * stringHeight) + stringHeight / 2;
      
      ctx.fillStyle = 'rgba(0, 255, 100, 0.8)';
      ctx.beginPath();
      ctx.arc(x + 50, stringY, 6, 0, Math.PI * 2);
      ctx.fill();
    });
  }

  // Detect which string is being played based on frequency
  detectPlayedString(frequency) {
    if (frequency <= 0) return -1;
    
    let closestString = -1;
    let minDiff = Infinity;
    
    this.strings.forEach((string, idx) => {
      const diff = Math.abs(frequency - string.frequency);
      // Allow ±5% frequency tolerance
      if (diff < string.frequency * 0.05 && diff < minDiff) {
        minDiff = diff;
        closestString = idx;
      }
    });
    
    return closestString;
  }

  // Detect which chord is being played based on active strings
  detectCurrentChord() {
    if (this.detectedStrings.length < 2) return null;
    
    // Simplified chord detection - matches detected strings to known chords
    const detected = this.detectedStrings.sort((a, b) => a - b).join(',');
    
    const chordPatterns = {
      'C': '0,1,2,3',      // All strings
      'G': '0,1,2,3',      // Specific pattern
      'F': '0,1,2,3',      // Specific pattern
      'Am': '0,1,2,3'      // All open strings
    };
    
    for (const [chord, pattern] of Object.entries(chordPatterns)) {
      if (detected === pattern) return chord;
    }
    
    return null;
  }

  // Main game loop
  loop(timestamp) {
    if (!this.isActive) return;
    
    const dt = timestamp * 0.001; // Convert to seconds
    this.gameTime += dt;
    
    // Detect audio input
    if (window.audioEngine) {
      const pitch = window.audioEngine.detectPitch({ minClarity: 0.5, noiseGate: 0.002 });
      
      if (pitch.frequency > 0) {
        const playedString = this.detectPlayedString(pitch.frequency);
        if (playedString >= 0) {
          this.detectedStrings = [playedString];
          this.totalFrames++;
          
          // Check if correct
          if (this.currentExerciseType === 'string_match' && playedString === this.targetString) {
            this.correctFrames++;
            this.score += 10;
          }
        }
      }
    }
    
    // Update accuracy
    this.accuracy = this.totalFrames > 0 
      ? (this.correctFrames / this.totalFrames) * 100 
      : 0;
    
    // Draw
    this.draw();
    
    // Continue loop
    this.animationFrameId = requestAnimationFrame((t) => this.loop(t));
  }

  // Render frame
  draw() {
    const rect = this.canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;

    if (!this.ctx) return;

    // Resize canvas to match display size on high-DPI screens
    if (this.canvas.width !== Math.floor(rect.width * dpr) || this.canvas.height !== Math.floor(rect.height * dpr)) {
      this.canvas.width = Math.floor(rect.width * dpr);
      this.canvas.height = Math.floor(rect.height * dpr);
      this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }

    this.ctx.fillStyle = '#020206';
    this.ctx.fillRect(0, 0, rect.width, rect.height);

    // Draw title
    this.ctx.fillStyle = 'var(--neon-cyan)';
    this.ctx.font = 'bold 20px Arial';
    this.ctx.fillText('Ukulele Training: ' + (this.targetChord || this.targetString), 30, 40);
    
    // Draw fretboard
    this.drawFretboard();
    
    // Draw HUD
    this.ctx.fillStyle = 'var(--text-secondary)';
    this.ctx.font = 'bold 14px Arial';
    this.ctx.fillText('Accuracy: ' + Math.round(this.accuracy) + '%', 30, rect.height - 30);
    this.ctx.fillText('Score: ' + this.score, 30, rect.height - 10);
  }

  start(exerciseData) {
    this.isActive = true;
    this.currentExerciseType = exerciseData.type;
    this.targetChord = exerciseData.targetChord || null;
    this.targetString = exerciseData.targetString || null;
    this.targetFret = exerciseData.targetFret || 0;
    this.score = 0;
    this.lives = 3;
    this.accuracy = 0;
    this.totalFrames = 0;
    this.correctFrames = 0;
    
    // Request animation
    this.animationFrameId = requestAnimationFrame((t) => this.loop(t));
  }

  stop() {
    this.isActive = false;
    if (this.animationFrameId) {
      cancelAnimationFrame(this.animationFrameId);
      this.animationFrameId = null;
    }
    return {
      score: this.score,
      accuracy: Math.round(this.accuracy),
      lives: this.lives,
      starsEarned: this.accuracy >= 80 ? 3 : this.accuracy >= 60 ? 2 : this.accuracy >= 40 ? 1 : 0
    };
  }
}

