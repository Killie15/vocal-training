const { Pool } = require('pg');
const path = require('path');
const fs = require('fs');

const isPostgres = !!process.env.DATABASE_URL;
let pgPool = null;
let sqliteDb = null;

if (isPostgres) {
  pgPool = new Pool({
    connectionString: process.env.DATABASE_URL,
    ssl: { rejectUnauthorized: true } // Secure SSL certification check
  });
  console.log("Database: Connecting to PostgreSQL via DATABASE_URL");
} else {
  // Only require sqlite3 locally (prevents Netlify lambda compilation failures)
  let sqlite3;
  try {
    sqlite3 = require('sqlite3').verbose();
  } catch (err) {
    console.error("Database: failed to load sqlite3 package:", err.message);
  }

  if (sqlite3) {
    const dbPath = process.env.DATABASE_PATH || path.join(__dirname, 'vocal_trainer.db');
    const dbDir = path.dirname(dbPath);
    if (!fs.existsSync(dbDir)) {
      fs.mkdirSync(dbDir, { recursive: true });
    }
    sqliteDb = new sqlite3.Database(dbPath, (err) => {
      if (err) {
        console.error('Error connecting to SQLite database:', err);
      } else {
        console.log('Connected to SQLite database vocal_trainer.db');
      }
    });
  }
}

function translateSql(sql) {
  let pgSql = sql
    .replace(/INTEGER PRIMARY KEY AUTOINCREMENT/gi, 'SERIAL PRIMARY KEY')
    .replace(/INSERT OR IGNORE/gi, 'INSERT')
    .replace(/DATETIME/gi, 'TIMESTAMP');

  if (sql.toUpperCase().includes('INSERT OR IGNORE')) {
    if (sql.toLowerCase().includes('achievements')) {
      pgSql += ' ON CONFLICT (user_id, achievement_key) DO NOTHING';
    } else if (sql.toLowerCase().includes('progress')) {
      pgSql += ' ON CONFLICT (queue_id) DO NOTHING';
    } else {
      pgSql += ' ON CONFLICT DO NOTHING';
    }
  }

  // Replace ? placeholders with $1, $2, $3...
  let index = 1;
  pgSql = pgSql.replace(/\?/g, () => `$${index++}`);
  
  return pgSql;
}

// Promise-based and Callback-compatible Database Methods
function all(sql, params = [], callback) {
  if (typeof params === 'function') {
    callback = params;
    params = [];
  }

  const execute = (resolve, reject) => {
    if (isPostgres) {
      const pgSql = translateSql(sql);
      pgPool.query(pgSql, params)
        .then(res => resolve(res.rows))
        .catch(err => {
          console.error("Postgres All Error:", err.message, "SQL:", pgSql);
          reject(err);
        });
    } else {
      if (!sqliteDb) return reject(new Error("SQLite is not initialized"));
      sqliteDb.all(sql, params, (err, rows) => {
        if (err) reject(err);
        else resolve(rows);
      });
    }
  };

  if (callback) {
    execute(
      (rows) => callback(null, rows),
      (err) => callback(err, null)
    );
  } else {
    return new Promise(execute);
  }
}

function get(sql, params = [], callback) {
  if (typeof params === 'function') {
    callback = params;
    params = [];
  }

  const execute = (resolve, reject) => {
    if (isPostgres) {
      const pgSql = translateSql(sql);
      pgPool.query(pgSql, params)
        .then(res => resolve(res.rows[0] || null))
        .catch(err => {
          console.error("Postgres Get Error:", err.message, "SQL:", pgSql);
          reject(err);
        });
    } else {
      if (!sqliteDb) return reject(new Error("SQLite is not initialized"));
      sqliteDb.get(sql, params, (err, row) => {
        if (err) reject(err);
        else resolve(row || null);
      });
    }
  };

  if (callback) {
    execute(
      (row) => callback(null, row),
      (err) => callback(err, null)
    );
  } else {
    return new Promise(execute);
  }
}

function run(sql, params = [], callback) {
  if (typeof params === 'function') {
    callback = params;
    params = [];
  }

  const execute = (resolve, reject) => {
    if (isPostgres) {
      let pgSql = translateSql(sql);
      const isInsert = pgSql.toUpperCase().startsWith('INSERT');
      if (isInsert && !pgSql.toUpperCase().includes('RETURNING')) {
        pgSql += ' RETURNING id';
      }
      pgPool.query(pgSql, params)
        .then(res => {
          const context = {
            lastID: res.rows && res.rows[0] ? res.rows[0].id : null,
            changes: res.rowCount
          };
          resolve(context);
        })
        .catch(err => {
          console.error("Postgres Run Error:", err.message, "SQL:", pgSql);
          reject(err);
        });
    } else {
      if (!sqliteDb) return reject(new Error("SQLite is not initialized"));
      sqliteDb.run(sql, params, function(err) {
        if (err) {
          reject(err);
        } else {
          const context = { lastID: this.lastID, changes: this.changes };
          resolve(context);
        }
      });
    }
  };

  if (callback) {
    execute(
      (context) => callback.call(context, null),
      (err) => callback.call(null, err)
    );
  } else {
    return new Promise(execute);
  }
}

function serialize(callback) {
  if (isPostgres) {
    callback();
  } else {
    if (sqliteDb) {
      sqliteDb.serialize(callback);
    } else {
      callback();
    }
  }
}

module.exports = {
  all,
  get,
  run,
  serialize,
  isPostgres
};
