package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.game.BoardCalibrationProfile
import com.example.game.CalibratedHsvColor
import com.example.game.CarromGeometrySolver
import com.example.game.CarromVisionDetector
import com.example.game.CarromVisionOutcome
import com.example.game.CoinColor
import com.example.game.Point2D
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonStop
import com.example.ui.theme.CyberTeal
import com.example.ui.theme.CyberTealDark
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceElevated
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private enum class CalibrationToolTab(val label: String) {
    ALIGN("1. Align Pockets"),
    COLORS("2. Pick Colors"),
    TEST_AIM("3. Test Aim")
}

private enum class DragHandleTarget {
    POCKET_TL,
    POCKET_TR,
    POCKET_BL,
    POCKET_BR,
    BOARD_TL,
    BOARD_BR,
    BASELINE_Y,
    SLIDER_Y
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarromCalibrationDialog(
    initialCalibration: BoardCalibrationProfile,
    onSaveCalibration: (BoardCalibrationProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var profile by remember {
        mutableStateOf(
            if (initialCalibration.pocketsNorm.size == 4) {
                initialCalibration
            } else {
                initialCalibration.copy(pocketsNorm = initialCalibration.defaultCornerPocketsNorm())
            }
        )
    }
    var activeTab by remember { mutableStateOf(CalibrationToolTab.ALIGN) }
    var selectedSwatchRole by remember { mutableStateOf(CoinColor.WHITE) }
    var previewCoinColor by remember { mutableStateOf(CoinColor.WHITE) }
    var previewFreestyle by remember { mutableStateOf(false) }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isSampleBoard by remember { mutableStateOf(false) }
    var debugOutcome by remember { mutableStateOf<CarromVisionOutcome?>(null) }
    // Striker sweep (try-and-see): one entry per tried striker position
    // for the solved coin+pocket — পকেটে যাবে / যাবে না / ফাউল ঝুঁকি.
    var sweepEntries by remember { mutableStateOf<List<CarromGeometrySolver.StrikerSweepEntry>>(emptyList()) }
    var statusText by remember { mutableStateOf("Pick a Carrom game screenshot (or tap Sample Board) to calibrate pockets, colors & test aim.") }
    var activeDragHandle by remember { mutableStateOf<DragHandleTarget?>(null) }

    suspend fun runAimAnalysis(bm: Bitmap, cal: BoardCalibrationProfile) {
        val result = withContext(Dispatchers.Default) {
            CarromVisionDetector.runAimDebugPreview(
                bitmap = bm,
                calibration = cal,
                myCoinColor = previewCoinColor,
                isFreestyle = previewFreestyle
            )
        }
        debugOutcome = result
        // Sweep AFTER the preview, so the aim preview appears immediately
        // and the table fills in next. Full 25-position grid here (no 5s
        // budget in the lab). The profile guard drops a stale sweep when
        // the user dragged a pocket crosshair mid-computation.
        sweepEntries = emptyList()
        if (result is CarromVisionOutcome.SolvedShot) {
            val sweep = withContext(Dispatchers.Default) {
                CarromGeometrySolver.sweepStrikerAim(
                    board = result.board,
                    coin = result.solution.targetCoin,
                    pocketIndex = result.solution.pocketIndex,
                    pieces = result.boardPieces,
                    myCoinColor = previewCoinColor,
                    screenWidth = bm.width,
                    screenHeight = bm.height,
                    existingStriker = result.detectedStriker,
                    shotMemory = null,
                    usePhysicsRollout = true,
                    isFreestyle = previewFreestyle,
                    maxPositions = 25
                )
            }
            if (profile == cal) {
                sweepEntries = sweep
            }
        }
    }

    LaunchedEffect(Unit) {
        if (previewBitmap == null) {
            val sample = withContext(Dispatchers.Default) {
                createSampleCarromBoardBitmap()
            }
            previewBitmap = sample
            isSampleBoard = true
            runAimAnalysis(sample, profile)
        }
    }

    LaunchedEffect(profile, previewCoinColor, previewFreestyle, activeTab) {
        val bm = previewBitmap ?: return@LaunchedEffect
        if (activeTab == CalibrationToolTab.TEST_AIM) {
            runAimAnalysis(bm, profile)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    decodeSoftwareBitmapFromUri(context, uri, maxSidePx = 1080)
                }
                if (loaded != null) {
                    previewBitmap = loaded
                    isSampleBoard = false
                    val snapped = withContext(Dispatchers.Default) {
                        CarromVisionDetector.autoDetectCalibrationFromBitmap(loaded)
                    }
                    profile = snapped.copy(
                        profileName = profile.profileName,
                        maxPullScale = profile.maxPullScale,
                        whiteColor = profile.whiteColor,
                        blackColor = profile.blackColor,
                        queenColor = profile.queenColor,
                        strikerColor = profile.strikerColor
                    )
                    runAimAnalysis(loaded, profile)
                    statusText = "Screenshot loaded & auto-snapped! Fine-tune the 4 pocket crosshairs if needed."
                } else {
                    statusText = "Could not decode image. Try another Carrom screenshot."
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            color = ObsidianBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header
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
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.calibration_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (profile.enabled) "Active • Custom Geometry & Swatches" else "Preview Mode • Tap Save to Activate",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (profile.enabled) CyberTeal else AmberWarning
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("btn_close_calibration")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_close_calibration),
                            tint = TextSecondary
                        )
                    }
                }

                // Screenshot Action Row (Zero-permission Photo Picker + Auto-Snap)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .height(48.dp)
                            .testTag("btn_pick_carrom_screenshot"),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.65f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Image,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Pick Screenshot",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val bm = previewBitmap ?: return@OutlinedButton
                            scope.launch {
                                val snapped = withContext(Dispatchers.Default) {
                                    CarromVisionDetector.autoDetectCalibrationFromBitmap(bm)
                                }
                                profile = snapped.copy(
                                    profileName = profile.profileName,
                                    maxPullScale = profile.maxPullScale,
                                    whiteColor = profile.whiteColor,
                                    blackColor = profile.blackColor,
                                    queenColor = profile.queenColor,
                                    strikerColor = profile.strikerColor
                                )
                                runAimAnalysis(bm, profile)
                                statusText = "Auto-snapped board frame, 4 dark pockets & slider track!"
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .height(48.dp)
                            .testTag("btn_auto_snap_calibration"),
                        border = BorderStroke(1.dp, CyberTeal.copy(alpha = 0.65f))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = CyberTeal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Auto-Snap",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberTeal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Board name: this screenshot + pocket alignment + colours +
                // Test Aim are saved as a NAMED board profile, so the bot
                // can recognise this board again at match start (Auto-Snap
                // matches the saved pocket geometry) and use this board's
                // own learned table.
                OutlinedTextField(
                    value = profile.profileName,
                    onValueChange = { profile = profile.copy(profileName = it.take(40)) },
                    label = { Text("Board name (e.g. My Carrom Pool)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_board_profile_name")
                )

                // Mode Tabs
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CalibrationToolTab.entries.forEach { tab ->
                        val selected = activeTab == tab
                        FilterChip(
                            selected = selected,
                            onClick = { activeTab = tab },
                            label = {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberTealDark,
                                selectedLabelColor = ElectricCyan
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("tab_cal_${tab.name.lowercase()}")
                        )
                    }
                }

                // Color Picker Bar when in COLORS tab
                if (activeTab == CalibrationToolTab.COLORS) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateSurface),
                        border = BorderStroke(1.dp, SlateBorder),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Select role below, then tap directly on that coin in the image:",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CoinColor.entries.forEach { role ->
                                    val saved = profile.colorFor(role)
                                    val isSel = selectedSwatchRole == role
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSel) CyberTealDark else SlateSurfaceElevated)
                                            .border(
                                                1.dp,
                                                if (isSel) ElectricCyan else SlateBorder,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable { selectedSwatchRole = role }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val dotColor = if (saved != null) {
                                            Color(AndroidColor.HSVToColor(floatArrayOf(saved.h, saved.s, saved.v)))
                                        } else {
                                            when (role) {
                                                CoinColor.WHITE -> Color(0xFFF3EFE4)
                                                CoinColor.BLACK -> Color(0xFF282C34)
                                                CoinColor.QUEEN -> Color(0xFFE03131)
                                                CoinColor.STRIKER -> ElectricCyan
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(dotColor)
                                                .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (saved != null) "${role.name} ✓" else role.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSel) ElectricCyan else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (saved != null) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Clear ${role.name} swatch",
                                                tint = TextSecondary,
                                                modifier = Modifier
                                                    .size(14.dp)
                                                    .clickable {
                                                        profile = profile.withColorFor(role, null)
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Interactive Calibration & Aim Lab Canvas
                val bm = previewBitmap
                if (bm != null) {
                    val aspect = (bm.width.toFloat() / bm.height.coerceAtLeast(1).toFloat()).coerceIn(0.42f, 1.4f)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Black),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(aspect)
                        ) {
                            val imageBitmap = remember(bm) { bm.asImageBitmap() }
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = stringResource(R.string.cd_calibration_preview),
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )

                            val pointerModifier = when (activeTab) {
                                CalibrationToolTab.ALIGN -> Modifier.pointerInput(profile) {
                                    detectDragGestures(
                                        onDragStart = { startOffset ->
                                            val w = size.width.toFloat().coerceAtLeast(1f)
                                            val h = size.height.toFloat().coerceAtLeast(1f)
                                            val nx = startOffset.x / w
                                            val ny = startOffset.y / h
                                            val pockets = if (profile.pocketsNorm.size == 4) {
                                                profile.pocketsNorm
                                            } else {
                                                profile.defaultCornerPocketsNorm()
                                            }
                                            val baseScreenY = profile.boardTopNorm + (profile.boardBottomNorm - profile.boardTopNorm) * profile.baselineYNorm
                                            val candidates = listOf(
                                                DragHandleTarget.POCKET_TL to hypot(nx - pockets[0].x, ny - pockets[0].y),
                                                DragHandleTarget.POCKET_TR to hypot(nx - pockets[1].x, ny - pockets[1].y),
                                                DragHandleTarget.POCKET_BL to hypot(nx - pockets[2].x, ny - pockets[2].y),
                                                DragHandleTarget.POCKET_BR to hypot(nx - pockets[3].x, ny - pockets[3].y),
                                                DragHandleTarget.BOARD_TL to hypot(nx - profile.boardLeftNorm, ny - profile.boardTopNorm),
                                                DragHandleTarget.BOARD_BR to hypot(nx - profile.boardRightNorm, ny - profile.boardBottomNorm),
                                                DragHandleTarget.BASELINE_Y to abs(ny - baseScreenY),
                                                DragHandleTarget.SLIDER_Y to abs(ny - profile.sliderTrackYNorm)
                                            )
                                            activeDragHandle = candidates.minByOrNull { it.second }
                                                ?.takeIf { it.second <= 0.14f }
                                                ?.first
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val target = activeDragHandle ?: return@detectDragGestures
                                            val w = size.width.toFloat().coerceAtLeast(1f)
                                            val h = size.height.toFloat().coerceAtLeast(1f)
                                            val dxN = dragAmount.x / w
                                            val dyN = dragAmount.y / h
                                            val curPockets = if (profile.pocketsNorm.size == 4) {
                                                profile.pocketsNorm.toMutableList()
                                            } else {
                                                profile.defaultCornerPocketsNorm().toMutableList()
                                            }
                                            profile = when (target) {
                                                DragHandleTarget.POCKET_TL -> {
                                                    curPockets[0] = Point2D((curPockets[0].x + dxN).coerceIn(0.02f, 0.48f), (curPockets[0].y + dyN).coerceIn(0.05f, 0.55f))
                                                    profile.copy(enabled = true, pocketsNorm = curPockets)
                                                }
                                                DragHandleTarget.POCKET_TR -> {
                                                    curPockets[1] = Point2D((curPockets[1].x + dxN).coerceIn(0.52f, 0.98f), (curPockets[1].y + dyN).coerceIn(0.05f, 0.55f))
                                                    profile.copy(enabled = true, pocketsNorm = curPockets)
                                                }
                                                DragHandleTarget.POCKET_BL -> {
                                                    curPockets[2] = Point2D((curPockets[2].x + dxN).coerceIn(0.02f, 0.48f), (curPockets[2].y + dyN).coerceIn(0.35f, 0.88f))
                                                    profile.copy(enabled = true, pocketsNorm = curPockets)
                                                }
                                                DragHandleTarget.POCKET_BR -> {
                                                    curPockets[3] = Point2D((curPockets[3].x + dxN).coerceIn(0.52f, 0.98f), (curPockets[3].y + dyN).coerceIn(0.35f, 0.88f))
                                                    profile.copy(enabled = true, pocketsNorm = curPockets)
                                                }
                                                DragHandleTarget.BOARD_TL -> {
                                                    val nl = (profile.boardLeftNorm + dxN).coerceIn(0.01f, profile.boardRightNorm - 0.30f)
                                                    val nt = (profile.boardTopNorm + dyN).coerceIn(0.03f, profile.boardBottomNorm - 0.25f)
                                                    profile.copy(enabled = true, boardLeftNorm = nl, boardTopNorm = nt)
                                                }
                                                DragHandleTarget.BOARD_BR -> {
                                                    val nr = (profile.boardRightNorm + dxN).coerceIn(profile.boardLeftNorm + 0.30f, 0.99f)
                                                    val nb = (profile.boardBottomNorm + dyN).coerceIn(profile.boardTopNorm + 0.25f, 0.90f)
                                                    profile.copy(enabled = true, boardRightNorm = nr, boardBottomNorm = nb)
                                                }
                                                DragHandleTarget.BASELINE_Y -> {
                                                    val bh = (profile.boardBottomNorm - profile.boardTopNorm).coerceAtLeast(0.1f)
                                                    val newBaseline = (profile.baselineYNorm + dyN / bh).coerceIn(0.70f, 0.94f)
                                                    profile.copy(enabled = true, baselineYNorm = newBaseline)
                                                }
                                                DragHandleTarget.SLIDER_Y -> {
                                                    val newSliderY = (profile.sliderTrackYNorm + dyN).coerceIn(profile.boardBottomNorm + 0.01f, 0.95f)
                                                    profile.copy(enabled = true, sliderTrackYNorm = newSliderY)
                                                }
                                            }
                                        },
                                        onDragEnd = { activeDragHandle = null },
                                        onDragCancel = { activeDragHandle = null }
                                    )
                                }
                                CalibrationToolTab.COLORS -> Modifier.pointerInput(selectedSwatchRole, bm) {
                                    detectTapGestures { tapOffset ->
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        val h = size.height.toFloat().coerceAtLeast(1f)
                                        val bx = ((tapOffset.x / w) * bm.width).toInt().coerceIn(1, bm.width - 2)
                                        val by = ((tapOffset.y / h) * bm.height).toInt().coerceIn(1, bm.height - 2)
                                        var rSum = 0
                                        var gSum = 0
                                        var bSum = 0
                                        var cnt = 0
                                        for (dy in -1..1) {
                                            for (dx in -1..1) {
                                                val p = bm.getPixel(bx + dx, by + dy)
                                                rSum += AndroidColor.red(p)
                                                gSum += AndroidColor.green(p)
                                                bSum += AndroidColor.blue(p)
                                                cnt++
                                            }
                                        }
                                        val avgColor = AndroidColor.rgb(rSum / cnt, gSum / cnt, bSum / cnt)
                                        val swatch = CalibratedHsvColor.fromColorInt(avgColor)
                                        profile = profile.copy(enabled = true).withColorFor(selectedSwatchRole, swatch)
                                        statusText = "Saved ${selectedSwatchRole.name} color (H=${swatch.h.toInt()}°, S=${(swatch.s * 100).toInt()}%, V=${(swatch.v * 100).toInt()}%)."
                                    }
                                }
                                CalibrationToolTab.TEST_AIM -> Modifier
                            }

                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(pointerModifier)
                            ) {
                                val cw = size.width
                                val ch = size.height
                                val bL = profile.boardLeftNorm * cw
                                val bT = profile.boardTopNorm * ch
                                val bR = profile.boardRightNorm * cw
                                val bB = profile.boardBottomNorm * ch

                                // Board rectangle
                                drawRect(
                                    color = ElectricCyan.copy(alpha = 0.85f),
                                    topLeft = Offset(bL, bT),
                                    size = Size((bR - bL).coerceAtLeast(10f), (bB - bT).coerceAtLeast(10f)),
                                    style = Stroke(width = 2.5f)
                                )

                                // Corner handle markers in ALIGN tab
                                if (activeTab == CalibrationToolTab.ALIGN) {
                                    drawCircle(ElectricCyan, radius = 10f, center = Offset(bL, bT))
                                    drawCircle(ElectricCyan, radius = 10f, center = Offset(bR, bB))

                                    val pockets = if (profile.pocketsNorm.size == 4) {
                                        profile.pocketsNorm
                                    } else {
                                        profile.defaultCornerPocketsNorm()
                                    }
                                    pockets.forEach { pNorm ->
                                        val px = pNorm.x * cw
                                        val py = pNorm.y * ch
                                        drawCircle(AmberWarning.copy(alpha = 0.25f), radius = 22f, center = Offset(px, py))
                                        drawCircle(AmberWarning, radius = 18f, center = Offset(px, py), style = Stroke(width = 3f))
                                        drawLine(AmberWarning, Offset(px - 10f, py), Offset(px + 10f, py), strokeWidth = 2.5f)
                                        drawLine(AmberWarning, Offset(px, py - 10f), Offset(px, py + 10f), strokeWidth = 2.5f)
                                    }

                                    // Baseline Y line
                                    val baseY = bT + (bB - bT) * profile.baselineYNorm
                                    drawLine(
                                        color = CyberTeal.copy(alpha = 0.8f),
                                        start = Offset(bL + (bR - bL) * 0.14f, baseY),
                                        end = Offset(bR - (bR - bL) * 0.14f, baseY),
                                        strokeWidth = 3f,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                                    )
                                    // Slider Track line
                                    val sliderY = profile.sliderTrackYNorm * ch
                                    drawLine(
                                        color = AmberWarning,
                                        start = Offset(profile.sliderTrackLeftNorm * cw, sliderY),
                                        end = Offset(profile.sliderTrackRightNorm * cw, sliderY),
                                        strokeWidth = 4.5f
                                    )
                                    drawCircle(AmberWarning, radius = 8f, center = Offset((profile.sliderTrackLeftNorm + profile.sliderTrackRightNorm) * 0.5f * cw, sliderY))
                                }

                                // Render live Aim Debug overlays when solved
                                val solved = debugOutcome as? CarromVisionOutcome.SolvedShot
                                if (activeTab == CalibrationToolTab.TEST_AIM && solved != null) {
                                    val sxScale = cw / bm.width.coerceAtLeast(1).toFloat()
                                    val syScale = ch / bm.height.coerceAtLeast(1).toFloat()

                                    for (piece in solved.boardPieces) {
                                        if (piece.color == CoinColor.STRIKER) continue
                                        val ringColor = when (piece.color) {
                                            CoinColor.WHITE -> Color(0xFFF3EFE4)
                                            CoinColor.BLACK -> Color(0xFF9EABB8)
                                            CoinColor.QUEEN -> Color(0xFFFF4D6D)
                                            CoinColor.STRIKER -> CyberTeal
                                        }
                                        drawCircle(
                                            color = ringColor,
                                            radius = (piece.radiusPx * sxScale).coerceIn(8f, 22f),
                                            center = Offset(piece.center.x * sxScale, piece.center.y * syScale),
                                            style = Stroke(width = 2.5f)
                                        )
                                    }

                                    val sol = solved.solution
                                    val stPos = Offset(sol.strikerPos.x * sxScale, sol.strikerPos.y * syScale)
                                    val ghost = Offset(sol.ghostBall.x * sxScale, sol.ghostBall.y * syScale)
                                    val coinPos = Offset(sol.targetCoin.center.x * sxScale, sol.targetCoin.center.y * syScale)
                                    val pocketPos = Offset(sol.targetPocket.x * sxScale, sol.targetPocket.y * syScale)
                                    val dragEnd = Offset(sol.dragEnd.x * sxScale, sol.dragEnd.y * syScale)

                                    drawLine(
                                        color = ElectricCyan,
                                        start = stPos,
                                        end = ghost,
                                        strokeWidth = 4f,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                                    )
                                    drawCircle(ElectricCyan, radius = 10f, center = ghost, style = Stroke(width = 2.5f))
                                    drawLine(
                                        color = Color(0xFF10E88A),
                                        start = coinPos,
                                        end = pocketPos,
                                        strokeWidth = 4.5f
                                    )
                                    drawCircle(Color(0xFF10E88A), radius = 15f, center = stPos, style = Stroke(width = 3f))

                                    // Slingshot arrow
                                    drawLine(color = AmberWarning, start = stPos, end = dragEnd, strokeWidth = 5f)
                                    val ang = atan2((dragEnd.y - stPos.y).toDouble(), (dragEnd.x - stPos.x).toDouble())
                                    val headLen = 14.0
                                    drawLine(
                                        color = AmberWarning,
                                        start = dragEnd,
                                        end = Offset(
                                            (dragEnd.x - headLen * cos(ang - 0.5)).toFloat(),
                                            (dragEnd.y - headLen * sin(ang - 0.5)).toFloat()
                                        ),
                                        strokeWidth = 4.5f
                                    )
                                    drawLine(
                                        color = AmberWarning,
                                        start = dragEnd,
                                        end = Offset(
                                            (dragEnd.x - headLen * cos(ang + 0.5)).toFloat(),
                                            (dragEnd.y - headLen * sin(ang + 0.5)).toFloat()
                                        ),
                                        strokeWidth = 4.5f
                                    )
                                }
                            }
                        }
                    }
                }

                // Diagnostics Card in TEST_AIM or concise status in other tabs
                if (activeTab == CalibrationToolTab.TEST_AIM) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = !previewFreestyle && previewCoinColor == CoinColor.WHITE,
                            onClick = { previewFreestyle = false; previewCoinColor = CoinColor.WHITE },
                            label = { Text("White", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        )
                        FilterChip(
                            selected = !previewFreestyle && previewCoinColor == CoinColor.BLACK,
                            onClick = { previewFreestyle = false; previewCoinColor = CoinColor.BLACK },
                            label = { Text("Black", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        )
                        FilterChip(
                            selected = previewFreestyle,
                            onClick = { previewFreestyle = true },
                            label = { Text("Freestyle", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        )
                    }
                    val outcome = debugOutcome
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateSurfaceElevated),
                        border = BorderStroke(
                            1.dp,
                            if (outcome is CarromVisionOutcome.SolvedShot && outcome.solution.isSureShot) CyberTeal else AmberWarning
                        ),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            when (outcome) {
                                is CarromVisionOutcome.SolvedShot -> {
                                    val wCnt = outcome.boardPieces.count { it.color == CoinColor.WHITE }
                                    val bCnt = outcome.boardPieces.count { it.color == CoinColor.BLACK }
                                    val qCnt = outcome.boardPieces.count { it.color == CoinColor.QUEEN }
                                    val sol = outcome.solution
                                    Text(
                                        text = "Traced W:$wCnt B:$bCnt Q:$qCnt • Striker=${if (outcome.detectedStriker != null) "LOCKED ✓" else "NONE"} • Conf ${(sol.confidenceScore * 100).toInt()}% ${if (sol.isSureShot) "• SURE SHOT ✓" else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (sol.isSureShot) CyberTeal else AmberWarning,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = sol.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                is CarromVisionOutcome.NeedsAiVision -> {
                                    Text(
                                        text = outcome.diagnostic,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AmberWarning
                                    )
                                }
                                is CarromVisionOutcome.WaitStillnessOrTurn -> {
                                    Text(
                                        text = outcome.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AmberWarning
                                    )
                                }
                                null -> {
                                    Text(
                                        text = "Running local Aim Lab preview…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                    // Striker sweep (try-and-see): for the solved coin +
                    // pocket, every tried striker position on the baseline
                    // with its simulated verdict — the same test the live
                    // probe loop runs before sliding to the first position
                    // that pots.
                    if (sweepEntries.isNotEmpty()) {
                        val potsN = sweepEntries.count { it.outcome == CarromGeometrySolver.StrikerSweepOutcome.POTS }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateSurfaceElevated),
                            border = BorderStroke(1.dp, SlateBorder),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Striker Sweep — ${sweepEntries.size}টি জায়গা যাচাই • পকেটে যাবে ${potsN}টি",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.Bold
                                )
                                sweepEntries.forEach { entry ->
                                    val (verdict, vColor) = when (entry.outcome) {
                                        CarromGeometrySolver.StrikerSweepOutcome.POTS -> "পকেটে যাবে ✓" to CyberTeal
                                        CarromGeometrySolver.StrikerSweepOutcome.FOUL_RISK -> "ফাউল ঝুঁকি ✗" to AmberWarning
                                        CarromGeometrySolver.StrikerSweepOutcome.MISSES -> "যাবে না" to TextSecondary
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "স্ট্রাইকার x=${String.format(Locale.US, "%.2f", entry.strikerNormX)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "$verdict ${(entry.confidence * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = vColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = if (isSampleBoard) "$statusText (Showing Sample Board)" else statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                // Precision Physics & Slingshot Tuning Sliders
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    border = BorderStroke(1.dp, SlateBorder),
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Aim & Slingshot Fine-Tuning",
                            style = MaterialTheme.typography.titleSmall,
                            color = ElectricCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                        CalibrationTuningSlider(
                            label = "Slingshot Pull Power Scale",
                            valueText = String.format(Locale.US, "%.2fx", profile.maxPullScale),
                            value = profile.maxPullScale,
                            range = 0.65f..1.45f,
                            onValueChange = { profile = profile.copy(enabled = true, maxPullScale = it.coerceIn(0.65f, 1.45f)) }
                        )
                        CalibrationTuningSlider(
                            label = "Slingshot Deadzone (min grab)",
                            valueText = "${profile.slingshotDeadzonePx.toInt()} px",
                            value = profile.slingshotDeadzonePx,
                            range = 0f..54f,
                            onValueChange = { profile = profile.copy(enabled = true, slingshotDeadzonePx = it.coerceIn(0f, 60f)) }
                        )
                        CalibrationTuningSlider(
                            label = "Pull Curve Gamma (soft vs hard)",
                            valueText = String.format(Locale.US, "%.2f", profile.slingshotCurveGamma),
                            value = profile.slingshotCurveGamma,
                            range = 0.75f..1.35f,
                            onValueChange = { profile = profile.copy(enabled = true, slingshotCurveGamma = it.coerceIn(0.75f, 1.35f)) }
                        )
                        CalibrationTuningSlider(
                            label = "Cut-Throw Compensation",
                            valueText = String.format(Locale.US, "%.2fx", profile.cutThrowBias),
                            value = profile.cutThrowBias,
                            range = 0f..1.8f,
                            onValueChange = { profile = profile.copy(enabled = true, cutThrowBias = it.coerceIn(0f, 2.0f)) }
                        )
                        CalibrationTuningSlider(
                            label = "Coin Size Ratio",
                            valueText = String.format(Locale.US, "%.3f", profile.coinRadiusRatio),
                            value = profile.coinRadiusRatio,
                            range = 0.020f..0.042f,
                            onValueChange = { profile = profile.copy(enabled = true, coinRadiusRatio = it.coerceIn(0.018f, 0.048f)) }
                        )
                        CalibrationTuningSlider(
                            label = "Striker Size Ratio",
                            valueText = String.format(Locale.US, "%.3f", profile.strikerRadiusRatio),
                            value = profile.strikerRadiusRatio,
                            range = 0.026f..0.050f,
                            onValueChange = { profile = profile.copy(enabled = true, strikerRadiusRatio = it.coerceIn(0.024f, 0.058f)) }
                        )
                    }
                }

                // Bottom Save / Reset Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            val reset = BoardCalibrationProfile(enabled = false)
                            onSaveCalibration(reset)
                            onDismiss()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("btn_reset_board_calibration")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = CrimsonStop,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset Auto", color = CrimsonStop)
                    }

                    Button(
                        onClick = {
                            val finalCal = profile.copy(
                                profileName = profile.profileName.ifBlank { "My Board" },
                                enabled = true,
                                pocketsNorm = if (profile.pocketsNorm.size == 4) {
                                    profile.pocketsNorm
                                } else {
                                    profile.defaultCornerPocketsNorm()
                                }
                            )
                            onSaveCalibration(finalCal)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .height(50.dp)
                            .testTag("btn_save_board_calibration"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberTeal,
                            contentColor = ObsidianBg
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Save & Activate",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun decodeSoftwareBitmapFromUri(context: Context, uri: Uri, maxSidePx: Int): Bitmap? {
    return runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return null
        var sampleSize = 1
        while (maxOf(origW / sampleSize, origH / sampleSize) > maxSidePx * 2) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return null
        if (decoded.config != Bitmap.Config.ARGB_8888) {
            decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
        } else {
            decoded
        }
    }.getOrNull()
}

private fun createSampleCarromBoardBitmap(): Bitmap {
    val w = 360
    val h = 640
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bm)
    canvas.drawColor(AndroidColor.rgb(42, 16, 14))

    val bLeft = w * 0.055f
    val bw = w * 0.89f
    val bRight = bLeft + bw
    val bTop = (h - bw) * 0.48f
    val bBottom = bTop + bw

    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    paint.color = AndroidColor.rgb(66, 32, 18)
    canvas.drawRect(bLeft - 8f, bTop - 8f, bRight + 8f, bBottom + 8f, paint)
    paint.color = AndroidColor.rgb(214, 174, 130)
    canvas.drawRect(bLeft, bTop, bRight, bBottom, paint)

    // 4 Corner Pockets
    paint.color = AndroidColor.rgb(18, 16, 16)
    val inset = bw * 0.064f
    val pR = bw * 0.048f
    canvas.drawCircle(bLeft + inset, bTop + inset, pR, paint)
    canvas.drawCircle(bRight - inset, bTop + inset, pR, paint)
    canvas.drawCircle(bLeft + inset, bBottom - inset, pR, paint)
    canvas.drawCircle(bRight - inset, bBottom - inset, pR, paint)

    // Baseline & Red End Circles
    val baseY = bTop + bw * 0.845f
    paint.color = AndroidColor.rgb(80, 48, 32)
    paint.strokeWidth = 2f
    canvas.drawLine(bLeft + bw * 0.16f, baseY, bRight - bw * 0.16f, baseY, paint)

    // Coins
    paint.color = AndroidColor.rgb(245, 243, 236)
    canvas.drawCircle(bLeft + bw * 0.34f, bTop + bw * 0.34f, 10f, paint)
    canvas.drawCircle(bLeft + bw * 0.66f, bTop + bw * 0.36f, 10f, paint)
    paint.color = AndroidColor.rgb(34, 34, 38)
    canvas.drawCircle(bLeft + bw * 0.56f, bTop + bw * 0.56f, 10f, paint)
    paint.color = AndroidColor.rgb(214, 30, 40)
    canvas.drawCircle(bLeft + bw * 0.50f, bTop + bw * 0.48f, 10f, paint)

    // Striker on baseline
    val strikerX = bLeft + bw * 0.52f
    paint.color = AndroidColor.rgb(0, 215, 230)
    canvas.drawCircle(strikerX, baseY, 12f, paint)

    // Bottom Slider bar & knob
    val sliderY = bBottom + h * 0.085f
    paint.color = AndroidColor.rgb(120, 76, 44)
    canvas.drawRect(bLeft + bw * 0.16f, sliderY - 6f, bRight - bw * 0.16f, sliderY + 6f, paint)
    paint.color = AndroidColor.rgb(255, 198, 28)
    canvas.drawCircle(strikerX, sliderY, 10f, paint)

    return bm
}

@Composable
private fun CalibrationTuningSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = ElectricCyan,
                activeTrackColor = CyberTeal
            )
        )
    }
}

