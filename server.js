const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');
const db = require('./db');

const app = express();
const PORT = process.env.PORT || 3000;

// Restrict CORS in production; allow permissive local development.
const corsOrigin = process.env.CORS_ORIGIN;
const corsOptions = {
  origin: corsOrigin || (process.env.NODE_ENV === 'production' ? false : '*')
};
app.use(cors(corsOptions));
app.use(express.json());

// Zero-dependency memory-based rate limiting middleware
const ipRequestCounts = new Map();
const requestCounterGc = setInterval(() => ipRequestCounts.clear(), 60000); // Clear requests counter every 60s
if (typeof requestCounterGc.unref === 'function') {
  requestCounterGc.unref();
}

function rateLimiter(maxRequests) {
  return (req, res, next) => {
    const ip = req.ip || req.headers['x-forwarded-for'] || 'unknown';
    const key = `${req.path}:${ip}`;
    const count = ipRequestCounts.get(key) || 0;
    
    if (count >= maxRequests) {
      return res.status(429).json({ error: 'Too many requests. Please try again later.' });
    }
    
    ipRequestCounts.set(key, count + 1);
    next();
  };
}

// Normalize path for Netlify Functions (strips /.netlify/functions/api prefix)
app.use((req, res, next) => {
  const prefix = '/.netlify/functions/api';
  if (req.url.startsWith(prefix)) {
    req.url = req.url.slice(prefix.length);
  }
  next();
});

// Serve static files from the 'public' directory
app.use(express.static(path.join(__dirname, 'public')));

// Initialize Database Tables
initializeDatabase();

function initializeDatabase() {
  db.serialize(() => {
    // Create Users Table
    db.run(`CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT,
      voice_type TEXT,
      min_pitch_hz REAL,
      max_pitch_hz REAL,
      current_level INTEGER DEFAULT 1,
      total_score INTEGER DEFAULT 0
    )`);

    // Create Progress Table (updated with queue_id TEXT UNIQUE for client sync deduplication)
    db.run(`CREATE TABLE IF NOT EXISTS progress (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER,
      level_id INTEGER,
      stars_earned INTEGER,
      score INTEGER,
      completed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      queue_id TEXT UNIQUE
    )`);

    // Migration step: Add queue_id column to progress table if it doesn't exist
    db.run(`ALTER TABLE progress ADD COLUMN queue_id TEXT`, (err) => {
      if (!err) {
        db.run(`CREATE UNIQUE INDEX IF NOT EXISTS idx_progress_queue_id ON progress (queue_id)`);
      }
    });

    // Create Feedback Table
    db.run(`CREATE TABLE IF NOT EXISTS feedback (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER,
      level_id INTEGER,
      pitch_accuracy_pct REAL,
      stability_score REAL,
      agility_score REAL,
      feedback_text TEXT,
      timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
    )`);

    // Create Achievements Table
    db.run(`CREATE TABLE IF NOT EXISTS achievements (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER,
      achievement_key TEXT,
      unlocked_at DATETIME DEFAULT CURRENT_TIMESTAMP,
      UNIQUE(user_id, achievement_key)
    )`);

    // Create Note Performance Table
    db.run(`CREATE TABLE IF NOT EXISTS note_performance (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER,
      midi_note INTEGER,
      accuracy_pct REAL,
      max_sustain_sec REAL,
      timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
    )`);

    // Create Practice Logs Table
    db.run(`CREATE TABLE IF NOT EXISTS practice_logs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER,
      duration_minutes REAL,
      active_minutes REAL,
      timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
    )`);

    // Create default user if not exists
    db.get('SELECT id FROM users LIMIT 1', (err, row) => {
      if (err) {
        console.error('Error checking user:', err);
      } else if (!row) {
        db.run('INSERT INTO users (username, voice_type, min_pitch_hz, max_pitch_hz, current_level, total_score) VALUES (?, ?, ?, ?, ?, ?)',
          ['Singer', 'Not Calibrated', 80.0, 400.0, 1, 0], (insertErr) => {
            if (insertErr) console.error('Error inserting default user:', insertErr);
            else console.log('Created default user "Singer"');
          });
      }
    });
  });
}

// Load Curriculum levels from JSON file (Single source of truth)
let curriculumLevels = [];
try {
  curriculumLevels = require('./public/js/curriculum.json');
  console.log(`Curriculum: Loaded ${curriculumLevels.length} levels from JSON`);
} catch (err) {
  console.error("Curriculum: Failed to read curriculum.json. Using empty array.", err);
}


// Helper to get active user ID from request headers (Stateless Multi-User Isolation)
function getRequestUserId(req) {
  const raw = parseInt(req.headers['x-user-id'] || req.query.userId || 1, 10);
  if (!Number.isInteger(raw) || raw <= 0) return 1;
  return raw;
}

function sanitizeUsername(input) {
  return String(input || '')
    .replace(/[^\w .'-]/g, '')
    .trim()
    .slice(0, 50);
}

// Get Active User Profile (Dynamic Header)
app.get('/api/user', (req, res) => {
  const userId = getRequestUserId(req);
  db.get('SELECT * FROM users WHERE id = ?', [userId], (err, row) => {
    if (err) {
      res.status(500).json({ error: err.message });
    } else if (!row) {
      db.get('SELECT * FROM users ORDER BY id ASC LIMIT 1', (fallbackErr, fallbackRow) => {
        if (fallbackRow) {
          res.json(fallbackRow);
        } else {
          res.status(404).json({ error: 'No user profile found' });
        }
      });
    } else {
      res.json(row);
    }
  });
});

// Update User Profile / Calibration
app.post('/api/user', (req, res) => {
  const { voice_type, min_pitch_hz, max_pitch_hz, total_score } = req.body;
  const userId = getRequestUserId(req);
  
  db.run(
    `UPDATE users 
     SET voice_type = COALESCE(?, voice_type), 
         min_pitch_hz = COALESCE(?, min_pitch_hz), 
         max_pitch_hz = COALESCE(?, max_pitch_hz),
         total_score = COALESCE(?, total_score)
     WHERE id = ?`,
    [voice_type, min_pitch_hz, max_pitch_hz, total_score, userId],
    function(updateErr) {
      if (updateErr) {
        return res.status(500).json({ error: updateErr.message });
      }
      
      // Unlock pitch_explorer achievement if they did custom calibration
      if (voice_type && voice_type.includes('semitones')) {
        db.run(
          `INSERT OR IGNORE INTO achievements (user_id, achievement_key) VALUES (?, 'pitch_explorer')`,
          [userId]
        );
      }
      
      db.get('SELECT * FROM users WHERE id = ?', [userId], (err, row) => {
        res.json({ message: 'User updated successfully', user: row });
      });
    }
  );
});

// Get All Users (for selector dropdown)
app.get('/api/users', (req, res) => {
  db.all('SELECT id, username, voice_type, min_pitch_hz, max_pitch_hz, total_score, current_level FROM users ORDER BY id ASC', (err, rows) => {
    if (err) {
      res.status(500).json({ error: err.message });
    } else {
      res.json(rows);
    }
  });
});

// Create New User Profile (Validation added)
app.post('/api/users/create', rateLimiter(10), (req, res) => {
  const username = sanitizeUsername(req.body && req.body.username);
  if (!username) {
    return res.status(400).json({ error: 'Username is required' });
  }

  db.run(
    'INSERT INTO users (username, voice_type, min_pitch_hz, max_pitch_hz, current_level, total_score) VALUES (?, ?, ?, ?, ?, ?)',
    [username, 'Not Calibrated', 80.0, 400.0, 1, 0],
    function(err) {
      if (err) {
        res.status(500).json({ error: err.message });
      } else {
        const newId = this.lastID;
        res.json({ id: newId, username, voice_type: 'Not Calibrated', current_level: 1, total_score: 0 });
      }
    }
  );
});

// Select User Profile
app.post('/api/users/select', (req, res) => {
  const { id } = req.body;
  if (!id) {
    return res.status(400).json({ error: 'User ID is required' });
  }

  db.get('SELECT * FROM users WHERE id = ?', [id], (err, row) => {
    if (err) {
      res.status(500).json({ error: err.message });
    } else if (!row) {
      res.status(404).json({ error: 'User profile not found' });
    } else {
      res.json(row);
    }
  });
});

// Identify User by Hummed Frequency (vocal range midpoint matching with 6 semitone threshold)
app.post('/api/users/identify', (req, res) => {
  const { frequency } = req.body;
  if (!frequency || frequency <= 0) {
    return res.status(400).json({ error: 'Valid hummed frequency required' });
  }

  // Convert hummed frequency to MIDI note number
  const freqToMidi = (f) => {
    return 69 + 12 * Math.log2(f / 440.0);
  };
  const humMidi = freqToMidi(frequency);

  db.all('SELECT * FROM users', (err, rows) => {
    if (err) {
      return res.status(500).json({ error: err.message });
    }

    if (rows.length === 0) {
      return res.status(404).json({ error: 'No profiles found in database' });
    }

    // Filter out users who haven't calibrated yet
    const calibratedUsers = rows.filter(u => u.voice_type && u.voice_type !== 'Not Calibrated');
    if (calibratedUsers.length === 0) {
      return res.status(400).json({ error: 'No calibrated profiles found to match against. Please calibrate a profile first.' });
    }

    let closestUser = null;
    let minDistance = Infinity;

    calibratedUsers.forEach(user => {
      // Convert user's min and max bounds to MIDI values
      const minMidi = freqToMidi(user.min_pitch_hz);
      const maxMidi = freqToMidi(user.max_pitch_hz);
      const centerMidi = (minMidi + maxMidi) / 2;

      // Distance from hummed pitch to center pitch of vocal range
      const distance = Math.abs(humMidi - centerMidi);
      if (distance < minDistance) {
        minDistance = distance;
        closestUser = user;
      }
    });

    if (closestUser && minDistance <= 6.0) { // Limit match to within 6 semitones (1/2 octave)
      res.json({
        matched: true,
        user: closestUser,
        humMidi: Math.round(humMidi),
        distance: Math.round(minDistance * 10) / 10
      });
    } else {
      res.status(404).json({ error: 'Could not match any vocal range profile within 6 semitones' });
    }
  });
});

// Get Levels
app.get('/api/levels', (req, res) => {
  res.json(curriculumLevels);
});

// Save Level Progress & Feedback (Refactored to async/await and rate-limited)
app.post('/api/progress', rateLimiter(30), async (req, res) => {
  const { level_id, stars_earned, score, pitch_accuracy_pct, stability_score, agility_score, feedback_text, notePerformanceData, queue_id, completed_at } = req.body;
  const userId = getRequestUserId(req);
  const completedAt = completed_at || new Date().toISOString();

  try {
    // 1. Insert progress record with unique queue_id for client sync deduplication
    const progressResult = await db.run(
      `INSERT OR IGNORE INTO progress (user_id, level_id, stars_earned, score, queue_id, completed_at) VALUES (?, ?, ?, ?, ?, ?)`,
      [userId, level_id, stars_earned, score, queue_id || null, completedAt]
    );

    // If no row was inserted (meaning a duplicate key collision occurred), return early successfully
    if (progressResult.changes === 0) {
      console.log(`Progress duplicate ignored for queue_id: ${queue_id}`);
      return res.json({ status: 'success', message: 'Duplicate progress ignored' });
    }

    // 2. Insert feedback/diagnostic details
    await db.run(
      `INSERT INTO feedback (user_id, level_id, pitch_accuracy_pct, stability_score, agility_score, feedback_text, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)`,
      [userId, level_id, pitch_accuracy_pct, stability_score, agility_score, feedback_text, completedAt]
    );

    // 3. Check and unlock achievements
    const triggerAchievements = [];
    if (level_id === 1) triggerAchievements.push('first_flight');
    if (stars_earned === 3) triggerAchievements.push('triple_star');
    if (pitch_accuracy_pct >= 95) triggerAchievements.push('pitch_perfection');
    // Iron Lung stability check (Milestone 10 or 65)
    if ((level_id === 10 || level_id === 65) && stability_score >= 90) triggerAchievements.push('iron_lung');
    // Agility Runner check (Milestone 50)
    if (level_id === 50 && agility_score >= 80) triggerAchievements.push('agility_runner');
    // Vibrato Wizard check (Milestone 80)
    if (level_id === 80 && pitch_accuracy_pct >= 80) triggerAchievements.push('vibrato_wizard');
    // Progress checks
    if (level_id >= 50) triggerAchievements.push('halfway_there');
    if (level_id === 100) triggerAchievements.push('virtuoso');
    if (level_id === -100) triggerAchievements.push('karaoke_star');

    for (const key of triggerAchievements) {
      await db.run(`INSERT OR IGNORE INTO achievements (user_id, achievement_key) VALUES (?, ?)`, [userId, key]);
    }

    // 4. Insert note performance telemetry
    if (notePerformanceData && Array.isArray(notePerformanceData)) {
      for (const item of notePerformanceData) {
        await db.run(
          `INSERT INTO note_performance (user_id, midi_note, accuracy_pct, max_sustain_sec) VALUES (?, ?, ?, ?)`,
          [userId, item.midi, item.accuracy, item.sustain]
        );
      }
    }

    // 5. Update user aggregate score and level progression
    const sumRow = await db.get(`SELECT SUM(score) as totalScore FROM progress WHERE user_id = ?`, [userId]);
    const totalScore = sumRow ? sumRow.totalScore : 0;

    const userRow = await db.get('SELECT current_level FROM users WHERE id = ?', [userId]);
    let currentLevel = userRow ? userRow.current_level : 1;

    if (level_id >= currentLevel && level_id < curriculumLevels.length) {
      currentLevel = level_id + 1;
    }

    await db.run(
      `UPDATE users SET total_score = ?, current_level = ? WHERE id = ?`,
      [totalScore, currentLevel, userId]
    );

    res.json({
      status: 'success',
      message: 'Progress recorded',
      totalScore,
      unlockedLevel: currentLevel
    });
  } catch (err) {
    console.error("Error saving level progress:", err);
    res.status(500).json({ error: err.message });
  }
});

// Get User stats and performance diagnostics history (Dynamic stats enrichments with pagination)
app.get('/api/stats', (req, res) => {
  const userId = getRequestUserId(req);
  const limit = parseInt(req.query.limit || 50, 10);
  const offset = parseInt(req.query.offset || 0, 10);
  
  db.all(
    `SELECT f.*, p.score, p.stars_earned
     FROM feedback f
     JOIN progress p ON f.level_id = p.level_id AND f.timestamp = p.completed_at
     WHERE f.user_id = ?
     ORDER BY f.timestamp DESC
     LIMIT ? OFFSET ?`,
    [userId, limit, offset],
    (err, rows) => {
      if (err) {
        return res.status(500).json({ error: err.message });
      }
      
      // Map names and skills dynamically from loaded curriculumLevels single source of truth
      const enrichedRows = (rows || []).map(row => {
        const lvl = curriculumLevels.find(l => l.id === row.level_id);
        return {
          ...row,
          level_name: lvl ? lvl.name : `Level ${row.level_id}`,
          skill: lvl ? lvl.skill : 'Vocal Skill'
        };
      });
      
      res.json(enrichedRows);
    }
  );
});

// Get Unlocked Achievements
app.get('/api/achievements', (req, res) => {
  const userId = getRequestUserId(req);
  db.all('SELECT * FROM achievements WHERE user_id = ?', [userId], (err, rows) => {
    if (err) {
      res.status(500).json({ error: err.message });
    } else {
      res.json(rows);
    }
  });
});

// Unlock Achievement Directly
app.post('/api/achievements/unlock', (req, res) => {
  const userId = getRequestUserId(req);
  const { achievement_key } = req.body;
  
  if (!achievement_key) {
    return res.status(400).json({ error: 'achievement_key required' });
  }

  db.run(
    `INSERT OR IGNORE INTO achievements (user_id, achievement_key) VALUES (?, ?)`,
    [userId, achievement_key],
    function(err) {
      if (err) {
        res.status(500).json({ error: err.message });
      } else {
        res.json({ status: 'success', unlocked: this.changes > 0 });
      }
    }
  );
});

// Get Combined Character Status (User stats, averages, and achievements)
app.get('/api/character-status', (req, res) => {
  const userId = getRequestUserId(req);
  
  db.get('SELECT * FROM users WHERE id = ?', [userId], (err, user) => {
    if (err) {
      return res.status(500).json({ error: err.message });
    }
    
    if (!user) {
      return res.status(404).json({ error: 'Active user profile not found' });
    }
    
    // Get average feedback scores
    db.get(
      `SELECT 
         AVG(pitch_accuracy_pct) as avg_accuracy, 
         AVG(stability_score) as avg_stability, 
         AVG(agility_score) as avg_agility,
         COUNT(id) as feedback_count
       FROM feedback 
       WHERE user_id = ? AND level_id > 0`,
      [userId],
      (err2, stats) => {
        if (err2) return res.status(500).json({ error: err2.message });
        
        // Get custom songs count (level_id = -100)
        db.get(
          `SELECT COUNT(id) as karaoke_count FROM progress WHERE user_id = ? AND level_id = -100`,
          [userId],
          (err3, karaoke) => {
            if (err3) return res.status(500).json({ error: err3.message });
            
            // Get achievements
            db.all(
              `SELECT achievement_key, unlocked_at FROM achievements WHERE user_id = ?`,
              [userId],
              (err4, achievements) => {
                if (err4) return res.status(500).json({ error: err4.message });
                
                res.json({
                  user,
                  stats: {
                    avg_accuracy: stats.avg_accuracy || 0,
                    avg_stability: stats.avg_stability || 0,
                    avg_agility: stats.avg_agility || 0,
                    levels_completed: stats.feedback_count || 0,
                    karaoke_completed: karaoke.karaoke_count || 0
                  },
                  achievements: achievements || []
                });
              }
            );
          }
        );
      }
    );
  });
});

// Get Vocal Diagnostic Profile
app.get('/api/vocal-profile', (req, res) => {
  const userId = getRequestUserId(req);

  db.get('SELECT * FROM users WHERE id = ?', [userId], (err, user) => {
    if (err) {
      return res.status(500).json({ error: err.message });
    }
    if (!user) {
      return res.status(404).json({ error: 'Active user not found' });
    }

    // Query per-note aggregated performance
    db.all(
      `SELECT 
         midi_note, 
         AVG(accuracy_pct) as avg_accuracy, 
         MAX(max_sustain_sec) as max_sustain
       FROM note_performance
       WHERE user_id = ?
       GROUP BY midi_note`,
      [userId],
      (err2, notePerformance) => {
        if (err2) {
          return res.status(500).json({ error: err2.message });
        }

        // Query last 10 session scores for progress tracking
        db.all(
          `SELECT 
             timestamp,
             pitch_accuracy_pct,
             stability_score,
             agility_score
           FROM feedback
           WHERE user_id = ? AND level_id > 0
           ORDER BY timestamp ASC
           LIMIT 10`,
          [userId],
          (err3, timeline) => {
            if (err3) {
              return res.status(500).json({ error: err3.message });
            }

            res.json({
              user,
              notePerformance: notePerformance || [],
              timeline: timeline || []
            });
          }
        );
      }
    );
  });
});

// Live Diagnostics endpoint - streams client logs directly to server output for AI to debug
app.post('/api/diagnostics/log', rateLimiter(5), (req, res) => {
  const { type, message, stack, timestamp, userAgent } = req.body;
  const colorReset = "\x1b[0m";
  const colorRed = "\x1b[31m";
  const colorYellow = "\x1b[33m";
  const colorCyan = "\x1b[36m";
  
  console.log(`\n${colorCyan}=== [CLIENT DIAGNOSTIC LOG] ===${colorReset}`);
  console.log(`Time: ${timestamp}`);
  console.log(`Device: ${userAgent}`);
  
  if (type === 'error' || type === 'unhandledrejection') {
    console.error(`${colorRed}ERROR TRIGGERED:${colorReset} ${message}`);
    if (stack) {
      console.error(`${colorRed}Stack Trace:${colorReset}\n${stack}`);
    }
  } else {
    console.log(`${colorYellow}LOG:${colorReset} ${message}`);
  }
  console.log(`${colorCyan}================================${colorReset}\n`);
  
  res.json({ status: 'received' });
});

// Save Daily Practice Log
app.post('/api/practice-log', (req, res) => {
  const userId = getRequestUserId(req);
  const { duration_minutes, active_minutes, timestamp } = req.body;
  
  if (duration_minutes === undefined || active_minutes === undefined) {
    return res.status(400).json({ error: 'duration_minutes and active_minutes are required' });
  }

  const logTimestamp = timestamp || new Date().toISOString();

  db.run(
    `INSERT INTO practice_logs (user_id, duration_minutes, active_minutes, timestamp) VALUES (?, ?, ?, ?)`,
    [userId, parseFloat(duration_minutes), parseFloat(active_minutes), logTimestamp],
    function(err) {
      if (err) {
        res.status(500).json({ error: err.message });
      } else {
        res.json({ status: 'success', logId: this.lastID });
      }
    }
  );
});

// Get Practice Logs history for calendar plotting
app.get('/api/practice-logs', (req, res) => {
  const userId = getRequestUserId(req);
  
  db.all(
    `SELECT duration_minutes, active_minutes, timestamp 
     FROM practice_logs 
     WHERE user_id = ? 
     ORDER BY timestamp ASC`,
    [userId],
    (err, rows) => {
      if (err) {
        res.status(500).json({ error: err.message });
      } else {
        res.json(rows);
      }
    }
  );
});

// Export app for Netlify Functions serverless integration
module.exports = app;

// Start Express App locally if run directly
if (require.main === module) {
  app.listen(PORT, () => {
    console.log(`\n\x1b[32mPitchFlight server running at http://localhost:${PORT}\x1b[0m`);
    console.log(`Serving static files from: ${path.join(__dirname, 'public')}`);
    console.log(`To capture client diagnostics, errors are posted to: http://localhost:${PORT}/api/diagnostics/log\n`);
  });
}
