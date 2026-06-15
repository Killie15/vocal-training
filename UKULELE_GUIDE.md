# Ukulele Mode Guide - PitchFlight

## Overview

Ukulele Mode extends PitchFlight to teach string instrument playing with the same gamified, real-time feedback approach as the vocal trainer. It includes:

- **Preset curriculum** - 10 levels from string recognition to complex chord progressions
- **Custom song upload** - Upload songs in JSON format to generate practice sessions
- **Real-time feedback** - Microphone detects which strings you're playing and validates accuracy
- **Gamified progression** - Star ratings, accuracy tracking, and milestone achievements

---

## Getting Started

### Ukulele Tuning (Standard)

The app uses standard ukulele tuning:
- **String 1 (G)**: High G (392 Hz / MIDI 67)
- **String 2 (C)**: C (261.6 Hz / MIDI 60)
- **String 3 (E)**: E (329.6 Hz / MIDI 64)
- **String 4 (A)**: A (440 Hz / MIDI 69)

*Note: If you have a low-G ukulele, you may need to adjust audio detection thresholds.*

---

## Preset Curriculum (10 Levels)

### Level 1-4: String Recognition
Learn to identify each open string by its pitch:
- Open A string
- Open C string
- Open E string
- Open G string

**Goal**: Develop ear training to recognize each string's unique pitch.

### Level 5: String Roulette
Random string plucking - the app calls out strings to play in random order.

**Goal**: Quick string identification under pressure.

### Level 6-8: Chord Formation
Learn three essential ukulele chords:
- **C Major** (1 finger on 3rd fret, A string)
- **G Major** (2-finger stretch across frets 2-3)
- **F Major** (Barré chord - more advanced)

**Goal**: Build finger strength and chord memory.

### Level 9: Chord Progression
Practice switching between C, G, and F chords smoothly.

**Goal**: Develop hand positioning and transition speed.

### Level 10: Strumming Patterns
Learn the classic "down-down-up" strumming pattern over a C chord.

**Goal**: Develop rhythm and consistent strumming technique.

---

## Custom Song Upload

### How It Works

1. **Prepare your song** in JSON format (see format below)
2. **Click "Upload Song"** in the ukulele section
3. **Practice the chord progression** with real-time feedback
4. **App detects**: Which strings you play, accuracy, timing

### JSON Song Format

```json
{
  "title": "Song Title",
  "artist": "Artist Name",
  "tempo": 120,
  "chords": [
    { "name": "C", "startBeat": 0, "duration": 4 },
    { "name": "G", "startBeat": 4, "duration": 4 },
    { "name": "Am", "startBeat": 8, "duration": 4 }
  ],
  "notes": "Optional practice tips",
  "difficulty": "Beginner"
}
```

### Field Descriptions

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `title` | Yes | String | Song name |
| `artist` | No | String | Artist/composer |
| `tempo` | Yes | Number | Beats per minute (60-180) |
| `chords` | Yes | Array | Chord progression data |
| `chords[].name` | Yes | String | Chord name (C, G, F, Am, etc.) |
| `chords[].startBeat` | Yes | Number | When chord starts (beat # in song) |
| `chords[].duration` | Yes | Number | How long to hold chord (beats) |
| `notes` | No | String | Practice tips for learners |
| `difficulty` | No | String | Beginner / Intermediate / Advanced |

### Supported Chords

The app currently recognizes these chords by fret position:
- **C**: [0, 0, 0, 3]
- **G**: [0, 2, 3, 2]
- **F**: [2, 0, 1, 0]
- **Am**: [0, 0, 0, 0]
- **Dm**: [2, 2, 1, 0]
- **Em**: [0, 2, 2, 0]

*Can be extended to include more chords by editing ukulele-curriculum.json*

### Example: Create Your Own Song

```json
{
  "title": "Riptide",
  "artist": "Vance Joy",
  "tempo": 95,
  "chords": [
    { "name": "Am", "startBeat": 0, "duration": 4 },
    { "name": "F", "startBeat": 4, "duration": 4 },
    { "name": "C", "startBeat": 8, "duration": 4 },
    { "name": "G", "startBeat": 12, "duration": 4 }
  ],
  "notes": "Classic fingerpicking song. Focus on clean chord transitions.",
  "difficulty": "Beginner"
}
```

---

## How Audio Detection Works

### Real-Time String Detection

When you pluck a string, the app:

1. **Captures audio** from your microphone (real-time)
2. **Detects pitch** using autocorrelation (same as vocal trainer)
3. **Maps to string** by comparing detected frequency to string frequencies (±5% tolerance)
4. **Validates** against target chord or exercise

### Accuracy Scoring

- **25%+ accuracy** = ⭐ 1 Star
- **60%+ accuracy** = ⭐⭐ 2 Stars
- **85%+ accuracy** = ⭐⭐⭐ 3 Stars

*Scoring is based on correctly playing target strings at the right time.*

---

## Tips for Best Results

### Microphone Setup
- Place mic 6-12 inches from ukulele
- In quiet environment (fans, background noise reduce accuracy)
- Avoid reflective surfaces (echo degrades pitch detection)

### Playing Technique
- Pluck strings cleanly (avoid muting adjacent strings)
- Hold each chord for the full duration
- Mute strings before moving to the next chord

### Finger Exercises
- Before practice: warm up with open string exercises
- Build calluses gradually (don't practice until fingers hurt)
- Practice transitions slowly, then increase tempo

---

## Saved Songs

All uploaded songs are stored locally on your device:

- **No internet required** to play saved songs
- **Persist between sessions** (saved to browser's LocalStorage)
- **Delete anytime** if you want to remove them

---

## Future Enhancements (Roadmap)

### Planned Features
- [ ] **Fingerpicking patterns** - Arpeggio training
- [ ] **Strumming variations** - Different rhythmic patterns
- [ ] **Capo mode** - Transpose to different keys
- [ ] **Tuning guide** - Help calibrate ukulele tuning
- [ ] **Duet mode** - Play with backing tracks
- [ ] **More chords** - 7th, sus, barre variations

### Extensibility
- Support for ABC notation files
- MIDI file import (detect chords programmatically)
- Tablature (TAB) notation display
- Social sharing of custom songs

---

## Troubleshooting

### "Microphone access required"
- Allow microphone permission when browser asks
- Check browser settings: Settings → Site Settings → Microphone
- Try a different browser if Chrome/Safari fails

### "String not detected"
- Ensure your ukulele is in-tune with standard tuning
- Pluck the string more cleanly (avoid muting)
- Get microphone closer to ukulele
- Reduce background noise

### "Chord progression doesn't match"
- Verify JSON format using a JSON validator
- Check that chord names exactly match supported chords (C, G, F, Am, etc.)
- Ensure beat counts are consistent with tempo

### Audio seems delayed
- This is **normal latency** (30-100ms typical)
- For gameplay purposes, we've added automatic latency compensation
- If severe, check microphone driver and system performance

---

## Creating Your Own Curriculum

Want to add more exercises or chord progressions?

1. Edit `public/js/ukulele-curriculum.json`
2. Add your custom lesson with this format:

```json
{
  "id": 111,
  "instrument": "ukulele",
  "name": "Your Exercise",
  "type": "string_match | chord_hold | chord_progression | strumming_pattern",
  "targetChord": "C",
  "difficulty": "Beginner | Easy | Medium | Hard | Expert",
  "description": "What this exercise teaches",
  "ukuleleData": {
    "strings": ["G4", "C4", "E4", "A4"]
  }
}
```

3. Refresh the app - your new lesson appears in the curriculum

---

## Instrument Notes

### Ukulele Variants

The app is tuned for **Standard Soprano Ukulele**:
- 21 inches long
- Nylon strings
- Geared friction tuners
- Standard tuning: G-C-E-A

**For other variants:**
- **Concert Ukulele**: Same tuning, slightly larger sound
- **Tenor Ukulele**: Usually the same tuning (some use G-C-E-A low-G)
- **Baritone Ukulele**: Different tuning (D-G-B-E) - would need custom setup

### Tuning Your Ukulele

Before practice, ensure your ukulele is in tune:
- Use the **Tuner view** in PitchFlight
- Or tune manually: G ≈ 392 Hz, C ≈ 262 Hz, E ≈ 330 Hz, A ≈ 440 Hz

---

## License & Attribution

Ukulele training curriculum designed for PitchFlight vocal/instrument trainer app.
Chord progressions and song examples are educational fair use.

For licensed songs, verify you have rights to practice/perform them before uploading.

---

**Need help?** Check the main PitchFlight user guide or contact support.
