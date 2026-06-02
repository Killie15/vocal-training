// Web Audio API Pitch Tracking Engine - PitchFlight

class PitchAudioEngine {
  constructor() {
    this.audioContext = null;
    this.analyser = null;
    this.micStream = null;
    this.compressor = null;
    this.fftSize = 2048;
    this.buffer = new Float32Array(this.fftSize);
    this.sampleRate = 44100;
    this.isListening = false;
    this.initPromise = null; // Lock to prevent microphone access race conditions
    
    // Dynamic noise gate threshold (RMS amplitude)
    // ~ -45dB relative to full-scale 1.0 peak
    this.noiseGateThreshold = 0.006; 
    
    // Notes names standard
    this.noteStrings = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"];
  }

  // Requests microphone and initializes audio context graph with concurrency lock
  async init() {
    if (this.isListening) return true;
    if (this.initPromise) return this.initPromise;

    this.initPromise = (async () => {
      try {
        this.audioContext = new (window.AudioContext || window.webkitAudioContext)();
        if (this.audioContext.state === 'suspended') {
          await this.audioContext.resume();
        }
        this.sampleRate = this.audioContext.sampleRate;
        let constraints = {
          audio: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true
          }
        };

        try {
          this.micStream = await navigator.mediaDevices.getUserMedia(constraints);
        } catch (constraintsError) {
          console.warn('Strict audio constraints failed, retrying with simple constraints:', constraintsError);
          this.micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
        }
        
        // Listen to mic track termination to detect physical cable unplugging/hardware swaps
        this.micStream.getAudioTracks().forEach(track => {
          track.onended = () => {
            console.warn('[Audio Engine] Microphone hardware track ended unexpectedly');
            this.handleMicDisconnected();
          };
        });
        
        // Setup Web Audio Node graph
        const source = this.audioContext.createMediaStreamSource(this.micStream);
        
        // 1. DynamicsCompressorNode - Normalizes volume peaks across different mics
        this.compressor = this.audioContext.createDynamicsCompressor();
        this.compressor.threshold.setValueAtTime(-25, this.audioContext.currentTime);
        this.compressor.knee.setValueAtTime(10, this.audioContext.currentTime);
        this.compressor.ratio.setValueAtTime(4, this.audioContext.currentTime);
        this.compressor.attack.setValueAtTime(0.05, this.audioContext.currentTime);
        this.compressor.release.setValueAtTime(0.25, this.audioContext.currentTime);
        
        // 2. AnalyserNode for pitch correlation and waveform displays
        this.analyser = this.audioContext.createAnalyser();
        this.analyser.fftSize = this.fftSize;

        // Connect nodes
        source.connect(this.compressor);
        this.compressor.connect(this.analyser);

        this.isListening = true;
        console.log('Audio Engine initialized at sample rate:', this.sampleRate);
        return true;
      } catch (err) {
        console.error('Error accessing microphone:', err);
        this.isListening = false;
        this.initPromise = null; // Clear lock on error to allow retry
        throw err;
      }
    })();

    return this.initPromise;
  }

  stop() {
    this.initPromise = null; // Reset initialization lock
    if (this.micStream) {
      this.micStream.getTracks().forEach(track => track.stop());
    }
    if (this.audioContext && this.audioContext.state !== 'closed') {
      try {
        this.audioContext.close();
      } catch (err) {
        console.warn("Failed to close AudioContext:", err);
      }
    }
    this.isListening = false;
    this.analyser = null;
    this.micStream = null;
    this.compressor = null;
    console.log('Audio Engine stopped.');
  }

  handleMicDisconnected() {
    this.stop();
    const event = new CustomEvent('mic-disconnected', { 
      detail: { message: "Microphone connection lost. Please check your physical audio connections." } 
    });
    window.dispatchEvent(event);
  }

  // Returns current waveform data for live canvas drawings
  getWaveform() {
    if (!this.analyser) return null;
    const timeData = new Uint8Array(this.analyser.frequencyBinCount);
    this.analyser.getByteTimeDomainData(timeData);
    return timeData;
  }

  // Runs Pitch Detection using Normalized Autocorrelation with clarity/confidence check
  detectPitch() {
    if (!this.analyser) return { frequency: -1, note: "--", midi: -1, cents: 0, rms: 0, volumePct: 0, clarity: 0 };

    this.analyser.getFloatTimeDomainData(this.buffer);

    // 1. Calculate Signal Volume (Root Mean Square)
    let sum = 0;
    for (let i = 0; i < this.fftSize; i++) {
      sum += this.buffer[i] * this.buffer[i];
    }
    const rms = Math.sqrt(sum / this.fftSize);
    const volumePct = Math.round(rms * 100);

    // If signal is too quiet, it's silence (Noise Gate)
    if (rms < this.noiseGateThreshold) {
      return { frequency: -1, note: "--", midi: -1, cents: 0, rms, volumePct: 0, clarity: 0 };
    }

    // 2. Buffer Normalization (eliminates signal drop influence)
    let maxVal = -1;
    let minVal = 1;
    for (let i = 0; i < this.fftSize; i++) {
      if (this.buffer[i] > maxVal) maxVal = this.buffer[i];
      if (this.buffer[i] < minVal) minVal = this.buffer[i];
    }
    const amplitude = (maxVal - minVal) / 2;
    if (amplitude < 0.001) {
      return { frequency: -1, note: "--", midi: -1, cents: 0, rms, volumePct: 0, clarity: 0 };
    }
    
    const normalizedBuffer = new Float32Array(this.fftSize);
    let r0 = 0.0001; // Avoid divide by zero
    for (let i = 0; i < this.fftSize; i++) {
      normalizedBuffer[i] = this.buffer[i] / amplitude;
      r0 += normalizedBuffer[i] * normalizedBuffer[i];
    }

    // 3. Autocorrelation Algorithm (restricted to vocal range 40Hz to 2000Hz to double performance)
    const minLag = Math.floor(this.sampleRate / 2000);
    const maxLag = Math.ceil(this.sampleRate / 40);
    const r = new Float32Array(this.fftSize);
    for (let lag = minLag; lag < Math.min(maxLag, this.fftSize); lag++) {
      let tempSum = 0;
      for (let i = 0; i < this.fftSize - lag; i++) {
        tempSum += normalizedBuffer[i] * normalizedBuffer[i + lag];
      }
      r[lag] = tempSum;
    }

    // Find the zero crossing to skip the central correlation peak
    let zeroCrossingIndex = 0;
    for (let i = 0; i < this.fftSize - 1; i++) {
      if (r[i] > 0 && r[i + 1] < 0) {
        zeroCrossingIndex = i;
        break;
      }
    }

    // Fallback: if no zero crossing is found (common in quiet/harmonic environments), find first local minimum
    if (zeroCrossingIndex === 0) {
      for (let i = 1; i < this.fftSize - 1; i++) {
        if (r[i] < r[i - 1] && r[i] < r[i + 1]) {
          zeroCrossingIndex = i;
          break;
        }
      }
    }

    // If still no valid index is found, autocorrelation failed (too chaotic)
    if (zeroCrossingIndex === 0) {
      return { frequency: -1, note: "--", midi: -1, cents: 0, rms, volumePct, clarity: 0 };
    }

    // Find the next maximum peak after the zero crossing / valley
    let peakValue = -1;
    let peakIndex = -1;
    for (let i = zeroCrossingIndex; i < this.fftSize; i++) {
      if (r[i] > peakValue) {
        peakValue = r[i];
        peakIndex = i;
      }
    }

    if (peakIndex === -1) {
      return { frequency: -1, note: "--", midi: -1, cents: 0, rms, volumePct, clarity: 0 };
    }

    // Calculate autocorrelation clarity/confidence score
    const clarity = peakValue / r0;
    // Reject pitches with low clarity to filter out noisy ambient environments (e.g. breath/hum noise)
    if (clarity < 0.82) {
      return { frequency: -1, note: "--", midi: -1, cents: 0, rms, volumePct: 0, clarity };
    }

    // Refine peak index using parabolic interpolation for higher precision
    let refinedIndex = peakIndex;
    if (peakIndex > 0 && peakIndex < this.fftSize - 1) {
      const alpha = r[peakIndex - 1];
      const beta = r[peakIndex];
      const gamma = r[peakIndex + 1];
      const p = 0.5 * (alpha - gamma) / (alpha - 2 * beta + gamma);
      refinedIndex = peakIndex + p;
    }

    // Calculate frequency based on peak lag period
    const frequency = this.sampleRate / refinedIndex;

    // Filter results outside normal human vocal frequency bounds (~40Hz to 2000Hz)
    if (frequency < 40 || frequency > 2000) {
      return { frequency: -1, note: "--", midi: -1, cents: 0, rms, volumePct, clarity };
    }

    // 4. Convert Frequency to Musical Note & Cent Offset
    const midiFloat = this.freqToMidi(frequency);
    const midi = Math.round(midiFloat);
    const cents = Math.round((midiFloat - midi) * 100);
    const noteName = this.midiToNoteName(midi);

    return { frequency, note: noteName, midi, cents, rms, volumePct, clarity };
  }

  // Hz -> Floating MIDI
  freqToMidi(f) {
    return 69 + 12 * Math.log2(f / 440);
  }

  // Integer MIDI -> Hz
  midiToFreq(midi) {
    return 440 * Math.pow(2, (midi - 69) / 12);
  }

  // Integer MIDI -> Scientific Note Name (e.g. 60 -> C4)
  midiToNoteName(midi) {
    const noteIndex = midi % 12;
    const octave = Math.floor(midi / 12) - 1;
    return this.noteStrings[noteIndex] + octave;
  }

  // Calculate cent deviation from a target MIDI note
  getCentsDeviation(frequency, targetMidi) {
    const targetFreq = this.midiToFreq(targetMidi);
    return Math.round(1200 * Math.log2(frequency / targetFreq));
  }

  // Polyphonic peak analysis to detect standard chords played on instruments
  getDetectedChord() {
    if (!this.analyser) return { chordName: "--", notes: [] };

    // Use larger FFT size for chord detection resolution
    if (this.analyser.fftSize !== 4096) {
      this.analyser.fftSize = 4096;
      this.buffer = new Float32Array(4096);
    }

    const bufferLength = this.analyser.frequencyBinCount;
    const dataArray = new Uint8Array(bufferLength);
    this.analyser.getByteFrequencyData(dataArray);

    const sampleRate = this.sampleRate;
    const binWidth = sampleRate / 4096;

    // 1. Find peaks in frequency data
    const peaks = [];
    const minThreshold = 45; // filter out quiet noise

    for (let i = 4; i < bufferLength - 4; i++) {
      const val = dataArray[i];
      if (val > minThreshold) {
        if (val > dataArray[i - 1] && val > dataArray[i - 2] &&
            val > dataArray[i + 1] && val > dataArray[i + 2]) {
          const freq = i * binWidth;
          // fundamental frequency limits for instruments (e.g. 60Hz to 1200Hz)
          if (freq >= 60 && freq <= 1200) {
            peaks.push({ freq, val });
          }
        }
      }
    }

    if (peaks.length === 0) return { chordName: "--", notes: [] };

    // Sort by amplitude desc and take top 6 peaks
    peaks.sort((a, b) => b.val - a.val);
    const topPeaks = peaks.slice(0, 6);

    const noteWeights = new Float32Array(12);
    const detectedMidiNotes = new Set();

    topPeaks.forEach(peak => {
      const midiFloat = 69 + 12 * Math.log2(peak.freq / 440);
      const midi = Math.round(midiFloat);
      const pitchClass = midi % 12;
      noteWeights[pitchClass] += peak.val;
      detectedMidiNotes.add(midi);
    });

    let maxWeight = 0;
    for (let i = 0; i < 12; i++) {
      if (noteWeights[i] > maxWeight) maxWeight = noteWeights[i];
    }

    if (maxWeight === 0) return { chordName: "--", notes: [] };

    const activeNotes = [];
    for (let i = 0; i < 12; i++) {
      if (noteWeights[i] > maxWeight * 0.22) { // 22% threshold helps ignore harmonics
        activeNotes.push(i);
      }
    }

    if (activeNotes.length < 2) {
      return { 
        chordName: activeNotes.length === 1 ? this.noteStrings[activeNotes[0]] : "--", 
        notes: activeNotes.map(n => this.noteStrings[n]) 
      };
    }

    activeNotes.sort((a, b) => a - b);

    // Standard Chord interval formulas
    const chordTypes = [
      { name: "", intervals: [0, 4, 7] },         // Major triad
      { name: "m", intervals: [0, 3, 7] },        // Minor triad
      { name: "sus4", intervals: [0, 5, 7] },     // Suspended 4th
      { name: "sus2", intervals: [0, 2, 7] },     // Suspended 2nd
      { name: "dim", intervals: [0, 3, 6] },      // Diminished
      { name: "aug", intervals: [0, 4, 8] },      // Augmented
      { name: "7", intervals: [0, 4, 7, 10] },    // Dominant 7th
      { name: "maj7", intervals: [0, 4, 7, 11] }, // Major 7th
      { name: "m7", intervals: [0, 3, 7, 10] }     // Minor 7th
    ];

    let bestMatch = null;

    for (let r = 0; r < activeNotes.length; r++) {
      const root = activeNotes[r];
      const relativeNotes = activeNotes.map(n => (n - root + 12) % 12);
      const relativeSet = new Set(relativeNotes);

      for (let t = 0; t < chordTypes.length; t++) {
        const type = chordTypes[t];
        const matchAll = type.intervals.every(interval => relativeSet.has(interval));
        if (matchAll) {
          bestMatch = {
            root: this.noteStrings[root],
            suffix: type.name
          };
          break;
        }
      }
      if (bestMatch) break;
    }

    const noteNames = activeNotes.map(n => this.noteStrings[n]);

    if (bestMatch) {
      return {
        chordName: `${bestMatch.root}${bestMatch.suffix}`,
        notes: noteNames
      };
    }

    return {
      chordName: "Unknown Chord",
      notes: noteNames
    };
  }

  // Synthesizes a clean reference guide tone for a given MIDI note duration
  playGuideTone(midiNote, durationSec = 0.8) {
    // Lazy initialize/resume context to comply with browser user gesture policies
    if (!this.audioContext) {
      this.audioContext = new (window.AudioContext || window.webkitAudioContext)();
    }
    
    if (this.audioContext.state === 'suspended') {
      this.audioContext.resume();
    }
    
    const osc = this.audioContext.createOscillator();
    const gainNode = this.audioContext.createGain();
    
    osc.type = 'triangle'; // triangle is a soft, vocal-like tone
    osc.frequency.setValueAtTime(this.midiToFreq(midiNote), this.audioContext.currentTime);
    
    // Smooth ADSR shaping (eliminates pop/click transitions)
    gainNode.gain.setValueAtTime(0.0, this.audioContext.currentTime);
    gainNode.gain.linearRampToValueAtTime(0.18, this.audioContext.currentTime + 0.04);
    gainNode.gain.setValueAtTime(0.18, this.audioContext.currentTime + durationSec - 0.08);
    gainNode.gain.exponentialRampToValueAtTime(0.0001, this.audioContext.currentTime + durationSec);
    
    osc.connect(gainNode);
    gainNode.connect(this.audioContext.destination);
    
    osc.start();
    osc.stop(this.audioContext.currentTime + durationSec);
    
    return { osc, gainNode };
  }
}

// Attach to window for global access
window.audioEngine = new PitchAudioEngine();
