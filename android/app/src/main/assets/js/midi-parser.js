// Simple Standard MIDI File (SMF) Binary Parser for Karaoke Mode - PitchFlight

class MidiParser {
  static parse(arrayBuffer) {
    const data = new DataView(arrayBuffer);
    let offset = 0;

    // Helper to read string
    function readString(length) {
      let str = "";
      for (let i = 0; i < length; i++) {
        str += String.fromCharCode(data.getUint8(offset + i));
      }
      offset += length;
      return str;
    }

    // Helper to read variable length quantity (VLQ)
    function readVarLength() {
      let value = 0;
      let count = 0;
      while (true) {
        let byte = data.getUint8(offset);
        offset++;
        count++;
        value = (value << 7) + (byte & 0x7f);
        if (!(byte & 0x80)) break;
        if (count > 4) throw new Error("Invalid MIDI variable-length value");
      }
      return value;
    }

    // Check Header Chunk
    const headerType = readString(4);
    if (headerType !== "MThd") {
      throw new Error("Invalid MIDI file: MThd header chunk not found");
    }

    const headerLength = data.getUint32(offset);
    offset += 4;
    
    const format = data.getUint16(offset);
    offset += 2;
    const numTracks = data.getUint16(offset);
    offset += 2;
    const ticksPerBeat = data.getUint16(offset);
    offset += 2;

    // Skip any extra header data if headerLength > 6
    if (headerLength > 6) {
      offset += (headerLength - 6);
    }

    console.log(`MIDI Format: ${format}, Tracks: ${numTracks}, TicksPerBeat: ${ticksPerBeat}`);

    let notes = [];
    let currentTempo = 500000; // microseconds per beat (default 120 BPM)

    // Process Tracks
    for (let trackIdx = 0; trackIdx < numTracks; trackIdx++) {
      if (offset >= data.byteLength) break;
      
      const trackHeader = readString(4);
      if (trackHeader !== "MTrk") {
        console.warn(`Warning: Expected MTrk header, found ${trackHeader}. Skipping.`);
        // Try to scan for next track header or abort
        break;
      }

      const trackLength = data.getUint32(offset);
      offset += 4;

      const trackEndOffset = offset + trackLength;
      
      let tickTime = 0;
      let secTime = 0;
      let lastStatus = 0;
      let activeNotes = {}; // Stores { noteNumber: { startMidi, startSec } }

      while (offset < trackEndOffset && offset < data.byteLength) {
        // Delta time
        const deltaTime = readVarLength();
        tickTime += deltaTime;
        
        // Convert ticks to seconds using current tempo
        const secondsPerTick = (currentTempo / 1000000) / ticksPerBeat;
        secTime += deltaTime * secondsPerTick;

        let status = data.getUint8(offset);
        
        // Running status check
        if (status < 0x80) {
          status = lastStatus;
        } else {
          offset++;
        }
        lastStatus = status;

        const eventType = status & 0xf0;
        const channel = status & 0x0f;

        if (eventType === 0x90) { // Note On
          const noteNumber = data.getUint8(offset);
          offset++;
          const velocity = data.getUint8(offset);
          offset++;

          if (velocity > 0) {
            // Note Start
            activeNotes[noteNumber] = secTime;
          } else {
            // Note End (velocity 0 is Note Off)
            if (activeNotes[noteNumber] !== undefined) {
              const startTime = activeNotes[noteNumber];
              const duration = secTime - startTime;
              if (duration > 0.05) { // Skip tiny glitch notes
                notes.push({
                  midi: noteNumber,
                  time: startTime,
                  duration: duration
                });
              }
              delete activeNotes[noteNumber];
            }
          }
        } 
        else if (eventType === 0x80) { // Note Off
          const noteNumber = data.getUint8(offset);
          offset++;
          const velocity = data.getUint8(offset); // ignore velocity
          offset++;

          if (activeNotes[noteNumber] !== undefined) {
            const startTime = activeNotes[noteNumber];
            const duration = secTime - startTime;
            if (duration > 0.05) {
              notes.push({
                midi: noteNumber,
                time: startTime,
                duration: duration
              });
            }
            delete activeNotes[noteNumber];
          }
        }
        else if (status === 0xff) { // Meta Event
          const metaType = data.getUint8(offset);
          offset++;
          const metaLength = readVarLength();

          if (metaType === 0x51) { // Tempo Change
            // 3 bytes representing microseconds per quarter note
            const t1 = data.getUint8(offset);
            const t2 = data.getUint8(offset + 1);
            const t3 = data.getUint8(offset + 2);
            currentTempo = (t1 << 16) + (t2 << 8) + t3;
            const bpm = Math.round(60000000 / currentTempo);
            console.log(`Tempo changed to: ${bpm} BPM`);
          }

          offset += metaLength;
        }
        // System Exclusive events
        else if (status === 0xf0 || status === 0xf7) {
          const sysexLength = readVarLength();
          offset += sysexLength;
        }
        // Standard voice events with 2 data bytes
        else if (eventType === 0xa0 || eventType === 0xb0 || eventType === 0xe0) {
          offset += 2;
        }
        // Standard voice events with 1 data byte
        else if (eventType === 0xc0 || eventType === 0xd0) {
          offset += 1;
        }
      }

      // Fast forward offset to track end in case of parsing discrepancies
      offset = trackEndOffset;
    }

    // Sort notes by onset start time
    notes.sort((a, b) => a.time - b.time);
    
    if (notes.length === 0) {
      throw new Error("No MIDI Note On/Off events found in melody channels.");
    }

    return notes;
  }
}

window.MidiParser = MidiParser;
