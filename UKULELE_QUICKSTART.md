# Ukulele Mode - Quick Start Guide

## What's New

PitchFlight now includes **Ukulele Training Mode** - a complete instrument learning system with real-time audio feedback, preset lessons, and custom song uploads.

## Getting Started (2 Minutes)

### Step 1: Open Ukulele Mode
- In the app navigation, click **Ukulele** (between Lessons and Stats)
- You'll see curriculum lessons and upload options

### Step 2: Choose a Lesson or Song
- **Learn preset lessons**: Click any of the 10 lessons to start
  - Lessons 1-4: Learn to identify each string by ear
  - Lessons 5-8: Master C, G, F chords
  - Lessons 9-10: Chord transitions and strumming
- **Or upload your own**: Click "Choose Song File" to upload a custom song

### Step 3: Start Practicing
- Click the lesson card to begin
- A fretboard visualization appears
- Follow the on-screen instructions
- Play your ukulele into the microphone
- The app detects which strings you're playing and scores your accuracy in real-time

## Features

✅ **Preset Curriculum** - 10 structured lessons (beginner → intermediate)
✅ **Custom Songs** - Upload any chord progression in JSON format
✅ **Real-Time Feedback** - Microphone detects strings and validates accuracy
✅ **Offline First** - All custom songs saved locally, works without internet
✅ **Gamified** - Earn stars based on accuracy, daily streaks, achievements
✅ **Visual Learning** - Fretboard diagrams show exactly where to place your fingers

## Uploading a Custom Song

### Format (JSON)
```json
{
  "title": "Song Name",
  "artist": "Artist",
  "tempo": 120,
  "chords": [
    { "name": "C", "startBeat": 0, "duration": 4 },
    { "name": "G", "startBeat": 4, "duration": 4 }
  ],
  "notes": "Optional tips",
  "difficulty": "Beginner"
}
```

### Supported Chords
- C, G, F (major chords - most common)
- Am, Dm, Em (minor chords)

### Tips for Creating Songs
- Use **startBeat** to define when each chord comes in
- Use **duration** to say how many beats to hold each chord
- Check the example songs for templates

### How It Works
1. Upload a JSON file
2. The app automatically converts your chord progression into a playable practice lesson
3. Practice the exact song you want to learn
4. Progress saves to your device

## Tips for Best Results

### Audio Setup
- **Microphone placement**: 6-12 inches from your ukulele
- **Quiet room**: Reduces background noise interference
- **Clean playing**: Pluck strings without muting adjacent ones
- **Full duration**: Hold each chord for the full time indicated

### Learning Path
1. Start with **String Recognition** (Lessons 1-4)
   - Develop ear for each string's pitch
   
2. Move to **Chord Foundations** (Lessons 5-8)
   - Learn finger positions for C, G, F
   
3. Practice **Transitions** (Lesson 9)
   - Smooth switching between chords
   
4. Develop **Rhythm** (Lesson 10)
   - Consistent, clean strumming

5. Play **Your Songs**
   - Upload songs and practice real music

## Troubleshooting

### "String isn't detecting"
- Ensure your ukulele is in standard tuning
- Check the Tuner view to verify pitch
- Play more forcefully and cleanly
- Reduce background noise

### "Can't upload song"
- Use JSON format (plain text file)
- Name supported chords: C, G, F, Am, Dm, Em
- Check example songs for correct structure
- If unsure, start with example songs

### "Audio seems delayed"
- This is normal (30-100ms latency)
- The app compensates automatically
- If severe, try adjusting Audio Settings

## Example Songs

Two example songs are provided to get you started:
- **Somewhere Over The Rainbow** (rainbow.json)
- **Let It Be** (let-it-be.json)

Use these as templates to create your own songs.

## Support & Documentation

For detailed information:
- **User Guide**: See [UKULELE_GUIDE.md](UKULELE_GUIDE.md)
- **Technical Details**: See [UKULELE_IMPLEMENTATION.md](UKULELE_IMPLEMENTATION.md)
- **Browser Console**: Check for any errors if features aren't working

## What's Next?

### Planned Features
- Backing track support (chord strumming accompaniment)
- Tempo control (practice at slower speeds)
- More advanced chords (7ths, sus, dim, barre variations)
- MIDI file import (auto-detect chords)
- Low-G tuning support (for low-G ukuleles)
- Capo mode (transpose chords to different keys)

---

**Happy practicing!** 🎸

Start with the preset lessons to build fundamentals, then upload your favorite songs to apply what you've learned.
