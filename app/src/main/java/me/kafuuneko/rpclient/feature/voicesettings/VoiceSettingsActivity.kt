package me.kafuuneko.rpclient.feature.voicesettings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.kafuuneko.rpclient.libs.core.CoreActivity
import me.kafuuneko.rpclient.libs.voice.VoiceManager

/** Voice settings: system TTS engine, voice, rate, pitch, test, auto-read and auto-send. */
class VoiceSettingsActivity : CoreActivity() {

    @Composable
    override fun ViewContent() {
        VoiceManager.init(this)
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            VoiceSettingsScreen(
                onBack = { finish() },
                openSystemTts = {
                    runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                },
            )
        }
    }
}

@Composable
private fun VoiceSettingsScreen(onBack: () -> Unit, openSystemTts: () -> Unit) {
    val ready by VoiceManager.ttsReady.collectAsState()
    val speaking by VoiceManager.speaking.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    var engine by remember { mutableStateOf(VoiceManager.currentEngine()) }
    var voice by remember { mutableStateOf(VoiceManager.getString(VoiceManager.KEY_VOICE)) }
    var rate by remember { mutableFloatStateOf(VoiceManager.getFloat(VoiceManager.KEY_RATE, 1f)) }
    var pitch by remember { mutableFloatStateOf(VoiceManager.getFloat(VoiceManager.KEY_PITCH, 1f)) }
    var autoRead by remember { mutableStateOf(VoiceManager.autoRead) }
    var autoSend by remember { mutableStateOf(VoiceManager.autoSend) }
    var skipActions by remember { mutableStateOf(VoiceManager.getBool(VoiceManager.KEY_SKIP_ACTIONS, false)) }
    var testText by remember { mutableStateOf("Hi Paul. This is how I sound with these settings.") }
    LaunchedEffect(ready) { if (ready) { engine = VoiceManager.currentEngine(); refresh++ } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Voice", style = MaterialTheme.typography.titleLarge)
        }

        // Engine picker
        val engines = remember(ready, refresh) { VoiceManager.engines() }
        Picker(
            label = "Speech engine",
            current = engines.firstOrNull { it.name == engine }?.label ?: engine ?: "System default",
            options = engines.map { it.label to it.name },
            onPick = { name ->
                engine = name; voice = null
                VoiceManager.setEngine(name)
            },
        )

        val voices = remember(ready, refresh, engine) { VoiceManager.voices() }
        Picker(
            label = "Voice",
            current = voice ?: "Engine default",
            options = listOf("Engine default" to "") + voices.map { v ->
                val net = if (v.isNetworkConnectionRequired) " (online)" else ""
                "${v.name} • ${v.locale.displayName}$net" to v.name
            },
            onPick = { name ->
                voice = name.ifBlank { null }
                VoiceManager.setVoice(voice)
            },
        )
        if (!ready) Text("Loading engine…", style = MaterialTheme.typography.bodySmall)

        Text("Speed: ${"%.2f".format(rate)}x")
        Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2.5f,
            onValueChangeFinished = { VoiceManager.putFloat(VoiceManager.KEY_RATE, rate) })
        Text("Pitch: ${"%.2f".format(pitch)}")
        Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2.0f,
            onValueChangeFinished = { VoiceManager.putFloat(VoiceManager.KEY_PITCH, pitch) })

        OutlinedTextField(value = testText, onValueChange = { testText = it },
            modifier = Modifier.fillMaxWidth(), label = { Text("Test sentence") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                VoiceManager.putFloat(VoiceManager.KEY_RATE, rate)
                VoiceManager.putFloat(VoiceManager.KEY_PITCH, pitch)
                VoiceManager.speakNow(testText)
            }) { Text("Test voice") }
            OutlinedButton(onClick = { VoiceManager.stop() }, enabled = speaking) { Text("Stop") }
        }

        Toggle("Read new replies aloud", "Speaks each sentence as the reply comes in.", autoRead) {
            autoRead = it; VoiceManager.putBool(VoiceManager.KEY_AUTO_READ, it)
        }
        Toggle("Send when I stop talking", "After the mic hears a pause, the message sends by itself.", autoSend) {
            autoSend = it; VoiceManager.putBool(VoiceManager.KEY_AUTO_SEND, it)
        }
        Toggle("Skip *actions*", "Don't read text between asterisks.", skipActions) {
            skipActions = it; VoiceManager.putBool(VoiceManager.KEY_SKIP_ACTIONS, it)
        }

        OutlinedButton(onClick = openSystemTts) { Text("Open Android speech settings") }
        Text(
            "Tip: tap the speaker on any reply to hear it. Tap anywhere in the chat list, or start the mic, to stop speaking.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Picker(label: String, current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}
