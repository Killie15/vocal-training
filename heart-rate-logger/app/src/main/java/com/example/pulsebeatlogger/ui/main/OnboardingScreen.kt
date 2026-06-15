package com.example.pulsebeatlogger.ui.main

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pulsebeatlogger.HeartRateState

private data class OnboardingPage(
    val emoji: String,
    val title: String,
    val body: String
)

private val PAGES = listOf(
    OnboardingPage(
        emoji = "🧠",
        title = "Your AI Learning Coach",
        body  = "Type any skill — Piano, Spanish, Python, Running — and the app builds a personalized curriculum using spaced repetition so you never forget what you've learned."
    ),
    OnboardingPage(
        emoji = "📡",
        title = "Every Sensor, Every Session",
        body  = "Connect your heart rate monitor, GPS, mic, or Pixel Watch. Every data point is timestamped and saved so you can see exactly how your body responds while you learn."
    ),
    OnboardingPage(
        emoji = "🤖",
        title = "Connect Your AI Key",
        body  = "The app uses Google Gemini to generate your curriculum, give real-time feedback, and adapt difficulty. Paste your free Gemini API key below to unlock AI coaching.\n\nGet a free key at: aistudio.google.com"
    ),
    OnboardingPage(
        emoji = "🚀",
        title = "Ready to Start",
        body  = "Go to the Find Skill tab, type what you want to learn, and tap Generate. The app will build your first curriculum and you can start practicing immediately — even offline."
    )
)

/**
 * Full-screen onboarding overlay shown on first launch.
 * Dismissed by completing all pages or tapping "Skip".
 * Sets "onboarding_complete" in SharedPreferences so it never shows again.
 */
@Composable
fun OnboardingOverlay(
    prefs: SharedPreferences,
    onComplete: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    var apiKeyInput by remember { mutableStateOf(HeartRateState.geminiApiKey) }
    val isApiPage = page == 2

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Skip button
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                TextButton(onClick = { completeOnboarding(prefs, apiKeyInput, onComplete) }) {
                    Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Animated page content
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    } else {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    }
                },
                label = "onboarding_page"
            ) { pageIdx ->
                val p = PAGES[pageIdx]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(p.emoji, fontSize = 72.sp)
                    Text(
                        p.title,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        p.body,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )

                    // API key input on page 3
                    if (pageIdx == 2) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("Gemini API Key") },
                            placeholder = { Text("AIzaSy...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Bottom nav
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Page dots
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PAGES.indices.forEach { i ->
                        Box(
                            Modifier
                                .size(if (i == page) 10.dp else 7.dp)
                                .background(
                                    if (i == page) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                        )
                    }
                }

                // Next / Finish button
                Button(
                    onClick = {
                        if (page < PAGES.lastIndex) {
                            page++
                        } else {
                            completeOnboarding(prefs, apiKeyInput, onComplete)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        if (page == PAGES.lastIndex) "Let's Go! 🚀" else "Next →",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (page > 0) {
                    TextButton(onClick = { page-- }) {
                        Text("← Back", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun completeOnboarding(
    prefs: SharedPreferences,
    apiKey: String,
    onComplete: () -> Unit
) {
    if (apiKey.isNotBlank()) {
        HeartRateState.geminiApiKey = apiKey
        prefs.edit().putString("geminiApiKey", apiKey).apply()
    }
    prefs.edit().putBoolean("onboarding_complete", true).apply()
    onComplete()
}
