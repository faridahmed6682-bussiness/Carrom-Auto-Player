package com.example.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.CarromCalibrationDialog
import com.example.ui.theme.TapPilotTheme

/**
 * Minimal launcher Activity for the Carrom-Auto-Player standalone export.
 *
 * Home screen only: app title, engine status, the active board-calibration
 * profile, and a button that opens [CarromCalibrationDialog] (Board
 * Calibration & Aim Lab — align pockets, pick coin colours, test aim on a
 * board screenshot). The calibration profile is kept in memory for this
 * session; persisting it is the host app's job.
 *
 * What this shell does NOT do (see README.md): it has no screenshot feed,
 * no 5s turn clock and no slingshot-drag execution — the live auto-play
 * loop lives in TapPilot's AgentCoordinator/GestureExecutor, which are
 * NOT part of this carrom-only repo.
 */
class CarromMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TapPilotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showCalibration by remember { mutableStateOf(false) }
                    var profile by remember { mutableStateOf(BoardCalibrationProfile()) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Top
                    ) {
                        Text(
                            text = "Carrom Auto Player",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Engine ready: 5s sure-shot solver, self-play trainer, per-board profiles.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Profile: ${profile.profileName} — pockets calibrated: " +
                                if (profile.pocketsNorm.size == 4) "yes" else "not yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { showCalibration = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.calibration_title))
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Note: this shell hosts calibration only. Auto-play " +
                                "(screenshot feed, 5s clock, slingshot drag) still needs " +
                                "a host loop — see README.md.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (showCalibration) {
                        CarromCalibrationDialog(
                            initialCalibration = profile,
                            onSaveCalibration = { saved ->
                                profile = saved
                                showCalibration = false
                            },
                            onDismiss = { showCalibration = false }
                        )
                    }
                }
            }
        }
    }
}
