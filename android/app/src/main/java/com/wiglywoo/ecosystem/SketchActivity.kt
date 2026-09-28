package com.wiglywoo.ecosystem

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import com.wiglywoo.CompanionManager
import com.wiglywoo.CoreBridge
import com.wiglywoo.WooState
import org.json.JSONObject
import java.io.File

/** Finger ink for a picture the Mac sent. The A52s has no pressure stylus. */
class SketchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val strokes = remember { mutableStateListOf<List<Offset>>() }
            var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
            Column(Modifier.fillMaxSize()) {
                Canvas(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDrag = { change, _ -> current = current + change.position },
                        onDragEnd = { strokes += current; current = emptyList() },
                    )
                }) {
                    val paint = Stroke(width = 6f, cap = StrokeCap.Round)
                    (strokes + listOf(current)).forEach { stroke ->
                        if (stroke.size < 2) return@forEach
                        val path = Path().apply {
                            moveTo(stroke.first().x, stroke.first().y)
                            stroke.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path, Color.Black, style = paint)
                    }
                }
                Button(onClick = {
                    val file = File(cacheDir, "sketch-${System.currentTimeMillis()}.png")
                    val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        strokeWidth = 8f
                        style = android.graphics.Paint.Style.STROKE
                        strokeCap = android.graphics.Paint.Cap.ROUND
                    }
                    strokes.forEach { stroke ->
                        for (i in 1 until stroke.size) {
                            canvas.drawLine(stroke[i - 1].x, stroke[i - 1].y, stroke[i].x, stroke[i].y, paint)
                        }
                    }
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, file.outputStream())
                    bitmap.recycle()
                    val peer = WooState.peers.firstOrNull()
                    if (peer != null) CoreBridge.sendFile(peer.id, file.absolutePath)
                    CompanionManager.send(JSONObject().put("type", "sketch_done").put("name", file.name))
                    finish()
                }) { Text("Send back") }
            }
        }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, SketchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
