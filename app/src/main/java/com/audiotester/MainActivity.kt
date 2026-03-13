package com.audiotester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.audiotester.ui.theme.AudiotesterTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AudiotesterTheme {
                AudiotesterApp()
            }
        }
    }
}

@Composable
private fun AudiotesterApp() {
    val engine = remember { AudioEngine() }

    var mode by remember { mutableStateOf(SignalMode.PINK_NOISE) }
    var band by remember { mutableStateOf(NoiseBand.LowBass) }
    var levelDb by remember { mutableFloatStateOf(-12f) }
    var frequencyHz by remember { mutableFloatStateOf(1000f) }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(mode, band, levelDb, frequencyHz) {
        engine.updateConfig(
            AudioConfig(
                mode = mode,
                band = band,
                frequencyHz = frequencyHz,
                levelDb = levelDb,
            ),
        )
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            engine.start()
        } else {
            engine.stop()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engine.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Audiotester",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            ControlCard(title = "Signal type") {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SignalMode.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = mode == item,
                            onClick = { mode = item },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = SignalMode.entries.size,
                            ),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                inactiveContainerColor = MaterialTheme.colorScheme.surface,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            icon = {},
                            label = {
                                Text(if (item == SignalMode.PINK_NOISE) "Pink noise" else "Tone")
                            },
                        )
                    }
                }
            }

            if (mode == SignalMode.PINK_NOISE) {
                ControlCard(title = "Noise band") {
                    OutlinedButton(
                        onClick = { band = NoiseBand.Full },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            containerColor = if (band == NoiseBand.Full) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            contentColor = if (band == NoiseBand.Full) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        ),
                    ) {
                        Text("full")
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val partialBands = NoiseBand.entries.filter { it != NoiseBand.Full }
                        partialBands.forEachIndexed { index, item ->
                            SegmentedButton(
                                selected = band == item,
                                onClick = { band = item },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = partialBands.size,
                                ),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primary,
                                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                    inactiveContainerColor = MaterialTheme.colorScheme.surface,
                                    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                                icon = {},
                                label = { Text(item.label) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "${band.lowHz.roundToInt()} Hz - ${band.highHz.roundToInt()} Hz",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ControlCard(title = "Tone frequency") {
                    Slider(
                        value = frequencyToSlider(frequencyHz),
                        onValueChange = { frequencyHz = sliderToFrequency(it) },
                        valueRange = 0f..1f,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${frequencyHz.roundToInt()} Hz",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row {
                            StepButton("-100") {
                                frequencyHz = (frequencyHz - 100f).coerceIn(20f, 20_000f)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            StepButton("+100") {
                                frequencyHz = (frequencyHz + 100f).coerceIn(20f, 20_000f)
                            }
                        }
                    }
                }
            }

            ControlCard(title = "Output level") {
                Slider(
                    value = levelDb,
                    onValueChange = { levelDb = it },
                    valueRange = -36f..0f,
                )
                Text(
                    text = "${levelDb.roundToInt()} dBFS",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            ControlCard {
                Button(
                    onClick = { isPlaying = !isPlaying },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause output" else "Start output",
                        modifier = Modifier.size(46.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label)
    }
}

@Composable
private fun ControlCard(
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}
