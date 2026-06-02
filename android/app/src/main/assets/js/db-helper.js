// Promise-based IndexedDB Wrapper for PitchFlight - Version 2
class VocalTrainerDB {
  constructor() {
    this.dbName = 'PitchFlightDB';
    this.version = 2; // Incremented database version to trigger schema migration
    this.db = null;
  }

  init() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(this.dbName, this.version);

      request.onupgradeneeded = (event) => {
        const db = event.target.result;

        // Settings store (key-value store for single configurations like current active user)
        if (!db.objectStoreNames.contains('settings')) {
          db.createObjectStore('settings');
        }

        // Users store (vocal ranges, stats, profile records)
        if (!db.objectStoreNames.contains('users')) {
          db.createObjectStore('users', { keyPath: 'id' });
        }

        // Progress/Stats log records
        if (!db.objectStoreNames.contains('progress')) {
          db.createObjectStore('progress', { keyPath: 'id' });
        }

        // Unsynced level completion sync queue
        if (!db.objectStoreNames.contains('offlineQueue')) {
          db.createObjectStore('offlineQueue', { keyPath: 'queueId' });
        }

        // Pitch diagnostics per-note telemetry
        if (!db.objectStoreNames.contains('notePerformance')) {
          db.createObjectStore('notePerformance', { keyPath: 'midi_note' });
        }

        // Achievements unlocked locally
        if (!db.objectStoreNames.contains('achievements')) {
          db.createObjectStore('achievements', { keyPath: 'achievement_key' });
        }

        // New Stores in version 2
        // Client-side local error logs (offline diagnostics buffer)
        if (!db.objectStoreNames.contains('errorLogs')) {
          db.createObjectStore('errorLogs', { keyPath: 'queueId' });
        }

        // Practice logs timeline history (local copies)
        if (!db.objectStoreNames.contains('practiceLogs')) {
          db.createObjectStore('practiceLogs', { keyPath: 'id', autoIncrement: true });
        }
      };

      request.onsuccess = (event) => {
        this.db = event.target.result;
        resolve(this);
      };

      request.onerror = (event) => {
        console.error("[IDB] Failed to open database:", event.target.error);
        reject(event.target.error);
      };
    });
  }

  // Retrieve single key from a specific store
  get(storeName, key) {
    return new Promise((resolve, reject) => {
      if (!this.db) return resolve(null);
      const transaction = this.db.transaction(storeName, 'readonly');
      const store = transaction.objectStore(storeName);
      const request = store.get(key);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }

  // Insert or update an entry in a store
  put(storeName, value, key) {
    return new Promise((resolve, reject) => {
      if (!this.db) return resolve();
      const transaction = this.db.transaction(storeName, 'readwrite');
      const store = transaction.objectStore(storeName);
      // Suppress the key parameter if the object store has a keyPath defined
      const request = store.keyPath ? store.put(value) : store.put(value, key);
      request.onsuccess = () => resolve();
      request.onerror = () => {
        const err = request.error || new Error("Unknown database write error");
        const dbWarnEvent = new CustomEvent('db-write-error', { detail: { error: err } });
        window.dispatchEvent(dbWarnEvent);
        reject(err);
      };
    });
  }

  // Get all items in a store
  getAll(storeName) {
    return new Promise((resolve, reject) => {
      if (!this.db) return resolve([]);
      const transaction = this.db.transaction(storeName, 'readonly');
      const store = transaction.objectStore(storeName);
      const request = store.getAll();
      request.onsuccess = () => resolve(request.result || []);
      request.onerror = () => reject(request.error);
    });
  }

  // Remove single key
  delete(storeName, key) {
    return new Promise((resolve, reject) => {
      if (!this.db) return resolve();
      const transaction = this.db.transaction(storeName, 'readwrite');
      const store = transaction.objectStore(storeName);
      const request = store.delete(key);
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  }

  // Clear entire store
  clear(storeName) {
    return new Promise((resolve, reject) => {
      if (!this.db) return resolve();
      const transaction = this.db.transaction(storeName, 'readwrite');
      const store = transaction.objectStore(storeName);
      const request = store.clear();
      request.onsuccess = () => resolve();
      request.onerror = () => reject(request.error);
    });
  }
}
// Export class globally
window.VocalTrainerDB = VocalTrainerDB;
