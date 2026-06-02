# Deployment Guide for PitchFlight Vocal Trainer

To access the vocal trainer from other devices (like your mobile phone) anywhere in the world, you can host the application on a cloud platform. Below are step-by-step guides for the two most popular free/low-cost platforms: **Railway** and **Render**.

---

## 🛠️ Step 1: Create a GitHub Repository & Push the Code

Both platforms link directly to GitHub to deploy your code automatically whenever you make changes.

1. **Log in to GitHub** (or create a free account at [github.com](https://github.com)).
2. **Create a new repository**:
   * Click **New** repository.
   * Name it `vocal-trainer`.
   * Keep it **Public** or **Private** (both work).
   * Do **NOT** initialize with a README, `.gitignore`, or license (we already have them).
   * Click **Create repository**.
3. **Push your local code to GitHub**:
   Open your terminal (PowerShell/CMD) inside `C:\Users\caram\.gemini\antigravity\scratch\vocal-trainer` and run:
   ```bash
   git branch -M main
   git remote add origin https://github.com/YOUR_GITHUB_USERNAME/vocal-trainer.git
   git push -u origin main
   ```
   *(Replace `YOUR_GITHUB_USERNAME` with your actual GitHub username).*

---

## 🚂 Option A: Deploy to Railway (Recommended - Supports Persistent SQLite)

Railway is excellent because it allows you to mount a persistent volume (virtual disk) to keep your SQLite database (`vocal_trainer.db`) from resetting when the server restarts.

1. Go to [railway.app](https://railway.app/) and sign up with your GitHub account.
2. Click **New Project** -> **Deploy from GitHub repo**.
3. Select your `vocal-trainer` repository.
4. Click **Deploy Now**.
5. **Set up Persistent Storage (Crucial for SQLite)**:
   * Once the project dashboard loads, click on your service card.
   * Go to the **Settings** tab.
   * Scroll down to the **Volumes** section and click **Add Volume**.
   * Name the volume (e.g., `db_volume`) and set the mount path to `/app/data` or mount it to `/app` (since the root path is `/app` or `/workspace` depending on the builder).
   * To ensure the server uses this persistent path, go to the **Variables** tab and add:
     * `DATABASE_URL` or a custom env variable if desired, but since `server.js` uses `vocal_trainer.db` in the current working directory, you can also mount the volume at `/app` or update the database path to look inside a specific folder like `data/`.
     *(Note: If you mount the volume at `/app/data`, you would need to adjust the database initialization path in `server.js` to look inside `data/vocal_trainer.db` if that directory exists. Otherwise, if you deploy on Render/Railway without a volume, it will fall back to using LocalStorage for offline saving when the database is reset).*
6. **Generate a Domain**:
   * Go to the **Settings** tab of your service card.
   * In the **Networking** section, click **Generate Domain**.
   * Copy the public URL (e.g. `https://vocal-trainer-production.up.railway.app`). You can now open this link on your phone!

---

## ☁️ Option B: Deploy to Render (100% Free Tier Web Service)

Render is completely free but resets SQLite database records on service restarts/deploys. However, **PitchFlight has built-in offline synchronization fallbacks**, meaning your phone will save logs in its browser LocalStorage cache and sync them up when online!

1. Go to [render.com](https://render.com/) and sign up using your GitHub account.
2. Click **New** -> **Web Service**.
3. Link your GitHub account and select the `vocal-trainer` repository.
4. Set the following build settings:
   * **Name**: `vocal-trainer`
   * **Region**: Select the one closest to you.
   * **Branch**: `main`
   * **Runtime**: `Node`
   * **Build Command**: `npm install`
   * **Start Command**: `node server.js`
   * **Instance Type**: **Free**
5. Click **Deploy Web Service**.
6. Render will build and expose a public URL at the top of the dashboard page (e.g. `https://vocal-trainer.onrender.com`).
7. Open this URL on your phone's browser, tap the browser menu, and select **Add to Home Screen** to install the PWA app shortcut!

---

## 📱 Installing on Your Mobile Phone (Progressive Web App)

Since PitchFlight is fully configured as a Progressive Web App (PWA):
* **On iOS (Safari)**: Open the deployed URL in Safari, click the **Share** button (box with an arrow pointing up), and select **Add to Home Screen**.
* **On Android (Chrome)**: Open the deployed URL in Chrome, click the three vertical dots menu, and select **Add to Home Screen** or **Install App**.
* You can now run the app full-screen from your app launcher, and it will remain usable even if you lose internet connection or go on a flight!
