package com.example.solitairehelper

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat

// ==================== 1. MODELLAR ====================
enum class Suit { HEARTS, DIAMONDS, CLUBS, SPADES }
enum class CardColor { RED, BLACK }

val Suit.color: CardColor
    get() = if (this == Suit.HEARTS || this == Suit.DIAMONDS) CardColor.RED else CardColor.BLACK

data class Card(val rank: Int, val suit: Suit, val isFaceUp: Boolean = true)

data class BoardState(
    val stock: List<Card> = emptyList(),
    val waste: List<Card> = emptyList(),
    val foundations: List<List<Card>> = List(4) { emptyList() },
    val tableau: List<List<Card>> = List(7) { emptyList() }
)

sealed class MoveAdvice {
    data class MoveCard(val from: String, val to: String, val cardDescription: String) : MoveAdvice()
    object DrawCard : MoveAdvice()
    object NoMoves : MoveAdvice()
}

// ==================== 2. ALGORITM (SOLVER) ====================
object SolitaireSolver {
    fun analyzeBoard(board: BoardState): MoveAdvice {
        board.waste.lastOrNull()?.let { card ->
            val fIdx = findFoundationIndex(card, board.foundations)
            if (fIdx != -1) return MoveAdvice.MoveCard("Waste", "Foundation ${fIdx + 1}", "${card.rank} ${card.suit}")
        }
        board.tableau.forEachIndexed { colIdx, column ->
            column.lastOrNull()?.let { card ->
                val fIdx = findFoundationIndex(card, board.foundations)
                if (fIdx != -1) return MoveAdvice.MoveCard("Column ${colIdx + 1}", "Foundation ${fIdx + 1}", "${card.rank} ${card.suit}")
            }
        }
        if (board.stock.isNotEmpty()) return MoveAdvice.DrawCard
        return MoveAdvice.NoMoves
    }

    private fun findFoundationIndex(card: Card, foundations: List<List<Card>>): Int {
        foundations.forEachIndexed { idx, pile ->
            val topCard = pile.lastOrNull()
            if (topCard == null) {
                if (card.rank == 1) return idx
            } else if (topCard.suit == card.suit && topCard.rank == card.rank - 1) {
                return idx
            }
        }
        return -1
    }
}

// ==================== 3. CAPTURE SERVICE ====================
class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("DATA")

        if (resultCode == Activity.RESULT_OK && data != null) {
            startForegroundServiceNotification()
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
        }
        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SolitaireCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_GLOBAL_ACCURACY, imageReader?.surface, null, null
        )
    }

    private fun startForegroundServiceNotification() {
        val channelId = "solitaire_capture_channel"
        val channel = NotificationChannel(channelId, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Solitaire Move Helper")
            .setContentText("Ekran tahlili faol...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        super.onDestroy()
    }
}

// ==================== 4. MAIN ACTIVITY ====================
class MainActivity : ComponentActivity() {
    private var isAnalyzing by mutableStateOf(false)

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            startForegroundService(serviceIntent)
            isAnalyzing = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        isAnalyzing = isAnalyzing,
                        onStartClicked = {
                            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            projectionLauncher.launch(mpManager.createScreenCaptureIntent())
                        },
                        onStopClicked = {
                            stopService(Intent(this, ScreenCaptureService::class.java))
                            isAnalyzing = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(isAnalyzing: Boolean, onStartClicked: () -> Unit, onStopClicked: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Solitaire Move Helper", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(32.dp))
        if (!isAnalyzing) {
            Button(onClick = onStartClicked, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Start Screen Analysis")
            }
        } else {
            Button(onClick = onStopClicked, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Stop Analysis")
            }
        }
    }
}
