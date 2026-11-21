package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.game.CarromMemoryData
import com.example.game.CarromTurnHudState
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CrimsonStop
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.CyberTealDark
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextSecondary
import java.util.Locale

/**
 * Carrom HUD / trainer UI, extracted from TapPilot's
 * app/src/main/java/com/example/ui/AgentComponents.kt (2921 lines) at commit
 * ea02d43:
 * - the carrom telemetry pill (~lines 537-590) as [CarromTelemetryPill]
 * - CarromWatchTrainCard (~line 1153+) as [CarromWatchTrainCard], including
 *   its totalAttempts / totalPots / blockedFouls summary line
 *
 * Unlike the original, these composables read NO view-model statics: they
 * take explicit parameters — [CarromMemoryData], the live-turn
 * [CarromTurnHudState] and plain callbacks — so any host can drive them.
 * [CarromHudState] bundles what the card needs into one value.
 *
 * HOW TAP-PILOT WIRED THESE (so a host can re-wire them):
 *
 * DashboardScreen.kt (~lines 75-180):
 * - `val carromMemory by viewModel.carromMemory.collectAsStateWithLifecycle()`
 *   and `val carromTurnHud by viewModel.carromTurnHud.collectAsStateWithLifecycle()`
 * - export/import launchers: `carromExportLauncher` =
 *   ActivityResultContracts.CreateDocument("application/json") ->
 *   viewModel.exportCarromTrainingToUri(uri); `carromImportLauncher` =
 *   ActivityResultContracts.OpenDocument() ->
 *   viewModel.importCarromTrainingFromUri(uri)
 * - training start goes through `proceedWithCarromTrainOrProjection()`:
 *   if the live screen-capture stream is not active yet, it first launches
 *   the MediaProjection permission intent and only then calls
 *   viewModel.startCarromWatchTraining() (pendingCarromTrainLaunch flag).
 *
 * AgentViewModel.kt:
 * - lines 45-46: `carromMemory: StateFlow<CarromMemoryData>` and
 *   `carromTurnHud: StateFlow<CarromTurnHudState?>` are exposed straight
 *   from AgentCoordinator.carromMemory / AgentCoordinator.carromTurnHud
 * - lines 74-85: `startCarromWatchTraining()` starts the coordinator task
 *   with CarromCoachHelper.CARROM_WATCH_TRAIN_GOAL and resets the goal
 *   input to CARROM_AUTO_PLAY_GOAL so Launch afterwards plays autonomously
 * - lines 147-153: `carromTransferStatus: StateFlow<String?>` holds the
 *   export/import result message (tap the banner to dismiss it)
 * - lines 214-243: `exportCarromTrainingToUri(uri)` writes
 *   AgentCoordinator.exportCarromTrainingJson() to the Uri and reports
 *   "Exported N shots & M blocked fouls (GitHub-ready JSON)!";
 *   `importCarromTrainingFromUri(uri)` reads the Uri text and feeds it to
 *   AgentCoordinator.importCarromTrainingJson(raw); failures set
 *   "Export failed: …" / "Import failed: …" on the same status flow.
 *
 * Strings used here all live in res/values/carrom_strings.xml
 * (carrom_train_title/desc, action_start/stop_carrom_train,
 * action_export/import_carrom_brain, cd_export/import_carrom_brain,
 * action_open_calibration).
 */
data class CarromHudState(
    val isServiceActive: Boolean = false,
    val isRunningThisMode: Boolean = false,
    val isOtherTaskBusy: Boolean = false,
    val isLiveStreamActive: Boolean = false,
    val carromMemory: CarromMemoryData = CarromMemoryData(),
    val turnHud: CarromTurnHudState? = null,
    val transferStatus: String? = null
)

/**
 * Telemetry pill from TapPilot's dashboard (~lines 537-590): shows the live
 * match calibration the watch-trainer learned — slider gain, shot-power
 * gain and friction scale. Renders nothing until at least one shot, slider
 * sample or scratch-zone risk has been recorded.
 */
@Composable
fun CarromTelemetryPill(
    carromMemory: CarromMemoryData,
    modifier: Modifier = Modifier
) {
    val cal = carromMemory.matchCalibration
    if (cal.shotsThisMatch > 0 || cal.sliderSamples > 0 || carromMemory.scratchHeatmap.zoneRiskByKey.isNotEmpty()) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(SlateSurfaceElevated)
                .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .testTag("carrom_telemetry_pill")
        ) {
            Text(
                text = "Slider %.2fx • Power %.2fx • Friction %.2fx".format(
                    cal.sliderGain,
                    cal.shotPowerGain,
                    cal.frictionScale
                ),
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan
            )
        }
    }
}

/**
 * Watch & Learn training card (TapPilot's CarromWatchTrainCard, ~line 1153+).
 * Start/stop the silent watch-trainer, see the learned-shot summary
 * (totalAttempts / totalPots / blockedFouls / patterns / collision risks /
 * power), export/import the learned brain as JSON, and open board
 * calibration. When a turn is live, [CarromHudState.turnHud] adds a small
 * live-turn line (probe stage + shot-clock progress).
 */
@Composable
fun CarromWatchTrainCard(
    state: CarromHudState,
    onStartTraining: () -> Unit,
    onStopTraining: () -> Unit,
    onExportTraining: () -> Unit = {},
    onImportTraining: () -> Unit = {},
    onOpenCalibration: () -> Unit = {},
    onDismissTransferStatus: () -> Unit = {}
) {
    val carromMemory = state.carromMemory
    val isRunningThisMode = state.isRunningThisMode
    val isLiveStreamActive = state.isLiveStreamActive
    val transferStatus = state.transferStatus
    val activeBorderColor by animateColorAsState(
        targetValue = if (isRunningThisMode) ElectricCyan else SlateBorder,
        animationSpec = tween(260),
        label = "carromWatchTrainBorder"
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunningThisMode) CyberTealDark.copy(alpha = 0.42f) else SlateSurface
        ),
        border = BorderStroke(1.dp, activeBorderColor),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.School,
                        contentDescription = null,
                        tint = if (isRunningThisMode) ElectricCyan else CyberTeal,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.carrom_train_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (isRunningThisMode) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberTealDark)
                            .border(1.dp, ElectricCyan, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isLiveStreamActive) "WATCH LIVE • 10FPS" else "WATCHING",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.carrom_train_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            val turnHud = state.turnHud
            if (turnHud != null && turnHud.isActive) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateSurfaceElevated)
                        .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Turn: ${turnHud.stage.badge} • %.1fs / %.0fs".format(
                            turnHud.elapsedSec,
                            turnHud.maxSec
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                }
            }

            val totalAttempts = carromMemory.statsByBucket.values.sumOf { it.attempts }
            val totalPots = carromMemory.statsByBucket.values.sumOf { it.successes }
            val blockedFouls = carromMemory.blockedFoulCount()
            val cleanPatternsCount = carromMemory.cleanShotPatterns.size
            val collisionRiskCount = carromMemory.collisionRisks.count { it.riskScore() > 0.1f }
            if (totalAttempts > 0 || carromMemory.matchCalibration.shotsThisMatch > 0 || blockedFouls > 0 || cleanPatternsCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateSurfaceElevated)
                        .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Learned: %d shots (%d pots) • Patterns: %d • Collision Risks: %d • Fouls: %d • Power %.2fx".format(
                            Locale.US,
                            maxOf(totalAttempts, carromMemory.matchCalibration.shotsThisMatch),
                            totalPots,
                            cleanPatternsCount,
                            collisionRiskCount,
                            blockedFouls,
                            carromMemory.matchCalibration.shotPowerGain
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTeal
                    )
                }
            }

            if (!transferStatus.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberTealDark.copy(alpha = 0.55f))
                        .border(1.dp, CyberTeal.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .clickable { onDismissTransferStatus() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = transferStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    if (isRunningThisMode) onStopTraining() else onStartTraining()
                },
                enabled = isRunningThisMode || (state.isServiceActive && !state.isOtherTaskBusy),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .height(50.dp)
                    .testTag("btn_toggle_carrom_train"),
                colors = if (isRunningThisMode) {
                    ButtonDefaults.buttonColors(
                        containerColor = CrimsonContainer,
                        contentColor = CrimsonStop
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = CyberTealDark,
                        contentColor = CyberTeal,
                        disabledContainerColor = SlateSurfaceElevated,
                        disabledContentColor = TextSecondary
                    )
                },
                border = if (isRunningThisMode) null else BorderStroke(1.dp, CyberTeal.copy(alpha = 0.65f))
            ) {
                Icon(
                    imageVector = if (isRunningThisMode) Icons.Rounded.Stop else Icons.Rounded.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRunningThisMode) {
                        stringResource(R.string.action_stop_carrom_train)
                    } else {
                        stringResource(R.string.action_start_carrom_train)
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExportTraining,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .height(48.dp)
                        .testTag("btn_export_carrom_training"),
                    border = BorderStroke(1.dp, CyberTeal.copy(alpha = 0.55f))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.cd_export_carrom_brain),
                        tint = CyberTeal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_export_carrom_brain),
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberTeal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onImportTraining,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .height(48.dp)
                        .testTag("btn_import_carrom_training"),
                    border = BorderStroke(1.dp, SlateBorder)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.UploadFile,
                        contentDescription = stringResource(R.string.cd_import_carrom_brain),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_import_carrom_brain),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenCalibration,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .height(48.dp)
                    .testTag("btn_open_carrom_calibration"),
                border = BorderStroke(
                    1.dp,
                    if (carromMemory.boardCalibration.enabled) CyberTeal else ElectricCyan.copy(alpha = 0.55f)
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = if (carromMemory.boardCalibration.enabled) CyberTeal else ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (carromMemory.boardCalibration.enabled) {
                        "${stringResource(R.string.action_open_calibration)} (ACTIVE ✓)"
                    } else {
                        stringResource(R.string.action_open_calibration)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (carromMemory.boardCalibration.enabled) CyberTeal else ElectricCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
