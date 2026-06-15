# PitchFlight Project Pre-Flight Checklist (All-Team Edition)

This document is the official blueprint for the entire team—Developers, Quality Assurance, UX Designers, Product Managers, and Executive Leadership. It outlines the standards required to make PitchFlight a safe, sustainable, accessible, and high-performance product.

---

## 1. Engineering Checklist (Developers)
*Ensures clean data-flow, performance optimization, and robust offline operations.*

- [ ] **Data-Flow Trace**: Check that user-configured settings (Gain, Noise Gate, Clarity, Latency) flow directly into calculations. Verify that no view-level script uses hardcoded overrides.
- [ ] **CPU/Energy Profiling**: Real-time autocorrelation (FFT) and 60fps rendering are CPU-intensive. Verify that the audio stream is suspended when the game is paused, finished, or hidden in a background tab to prevent battery drain.
- [ ] **PWA & Offline Resilience**: Verify Service Worker caches all core files and assets. Ensure that database queue items are stored in IndexedDB and synchronize gracefully once connection is restored.
- [ ] **Local Data Privacy**: Confirm that raw microphone PCM buffers are processed entirely locally on the client and are never sent to external servers.

---

## 2. Quality Assurance Checklist (QA & Testers)
*Ensures real-world stability across hardware and user profiles.*

- [ ] **Acoustic Stress Testing**: Verify pitch tracking under diverse vocal conditions:
  - High-pitch soprano registers vs. low-pitch bass registers.
  - Quiet humming vs. belted singing.
  - Noisy rooms (fans, echo, room acoustics).
- [ ] **Cross-Platform Compatibility**: Test Web Audio API lifecycle compliance:
  - **iOS Safari**: Ensure context resumes correctly after user interaction (Safari auto-mutes untrusted audio contexts).
  - **Android WebView / Chrome**: Ensure microphone permissions are handled gracefully.
- [ ] **Hardware Synchronization**: Verify that latency offsets successfully calibrate speaker/headphone lag (0ms to 300ms) on Bluetooth and wired outputs.

---

## 3. Product & Project Management Checklist (PMs)
*Ensures game progression, player retention, and user satisfaction.*

- [ ] **Difficulty Tuning & Pacing**: Check that early levels feature relaxed accuracy windows (e.g., larger semitone tunnels) and tutorial warnings to prevent beginners from getting discouraged.
- [ ] **Lives/Heart Balance**: Verify that the damage timer has a grace period on startup, and that missing pitches doesn't result in instant, frustrating "Game Over" states.
- [ ] **Gamification Feedback Loop**: Confirm that hits trigger satisfying continuous hit particles (visual reward) and accurate floating intonation feedback ("Pro Lock", "Good", "Flat", "Sharp").

---

## 4. UI/UX Design Checklist (Designers)
*Ensures high visual quality, accessibility, and clean screen fit.*

- [ ] **Color Contrast & Accessibility**: Ensure all neon active lines, staff lanes, and text elements satisfy WCAG AA contrast ratios against the dark background.
- [ ] **Dynamic Layout Resizing**: Verify that the game canvas resizes cleanly across desktop viewports, mobile screens, and tablet orientations without squishing the note tunnels.
- [ ] **Strum Guide Visibility**: Verify that strum chord badges are prominent, legible, and do not overlap notes.

---

## 5. Executive & Strategic Checklist (CEO / Sustainability)
*Ensures environmental impact alignment, ethics, and strategic direction.*

- [ ] **Ecological Footprint (Green Software Principles)**: 
  - Real-time client-side analysis is energy-intensive. By optimizing the pitch detection algorithm (restricting FFT calculations to the human vocal range of 40Hz - 2000Hz), we reduce unnecessary CPU operations by **over 50%**.
  - Make sure client-side code loops do not poll or block, keeping mobile device battery cycles low and minimizing the carbon footprint of daily use.
- [ ] **Privacy Ethics & Trust**: Ensure the app maintains a zero-cloud biometric footprint. Pitch profiles are stored in the user's local database, ensuring vocal identities remain completely under their control.
