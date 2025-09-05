package com.example.lightuniformitycam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlin.math.*

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val req = registerForActivityResult(
      ActivityResultContracts.RequestPermission()
    ){ setUI() }

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) setUI()
    else req.launch(Manifest.permission.CAMERA)
  }
  private fun setUI() { setContent { App() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
  val ctx = LocalContext.current
  val providerFuture = remember { ProcessCameraProvider.getInstance(ctx) }

  var manual by remember { mutableStateOf(true) }
  var exposureMs by remember { mutableStateOf(10f) } // 0.1..50 ms (Slider unten)
  var iso by remember { mutableStateOf(200f) }       // 50..1600

  val gridW = 160
  var bmp by remember { mutableStateOf<Bitmap?>(null) }
  var hProf by remember { mutableStateOf(FloatArray(0)) }
  var vProf by remember { mutableStateOf(FloatArray(0)) }
  var met by remember { mutableStateOf(Metrics()) }
  var c2ctrl by remember { mutableStateOf<Camera2CameraControl?>(null) }

  val preview = remember { PreviewView(ctx).apply {
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    scaleType = PreviewView.ScaleType.FIT_CENTER
  } }

  LaunchedEffect(manual, exposureMs, iso) {
    c2ctrl?.let { ctrl ->
      if (manual) {
        ctrl.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
          android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
        )
        ctrl.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
          (exposureMs * 1_000_000L).roundToLong()
        )
        ctrl.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY,
          iso.roundToInt()
        )
      } else {
        ctrl.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
          android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON
        )
      }
    }
  }

  LaunchedEffect(Unit) {
    val provider = providerFuture.get()
    val sel = CameraSelector.DEFAULT_BACK_CAMERA

    val prevBuilder = Preview.Builder()
    Camera2Interop.Extender(prevBuilder).apply {
      setCaptureRequestOption(
        android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
        if (manual) android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
        else android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON
      )
    }
    val prev = prevBuilder.build().apply { setSurfaceProvider(preview.surfaceProvider) }

    val analysis = ImageAnalysis.Builder()
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
      .build()
      .apply {
        setAnalyzer(Dispatchers.Default.asExecutor(),
          LumaAnalyzer(gridW) { hb, hp, vp, m ->
            bmp = hb; hProf = hp; vProf = vp; met = m
          })
      }

    provider.unbindAll()
    val cam = provider.bindToLifecycle(ctx as ComponentActivity, sel, prev, analysis)
    c2ctrl = Camera2CameraControl.from(cam.cameraControl)
  }

  MaterialTheme {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      Box(Modifier.fillMaxWidth().weight(1f)) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        bmp?.let {
          Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(0.55f))
        }
      }

      MetricsBar(met)

      Row(Modifier.fillMaxWidth().height(160.dp).padding(8.dp)) {
        ChartLine(hProf, "Horizontal-Profil", Modifier.weight(1f).fillMaxHeight())
        Spacer(Modifier.width(8.dp))
        ChartLine(vProf, "Vertikal-Profil", Modifier.weight(1f).fillMaxHeight())
      }

      Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Switch(checked = manual, onCheckedChange = { manual = it })
          Spacer(Modifier.width(8.dp))
          Text(if (manual) "Manuelle Belichtung" else "Auto Belichtung")
        }
        if (manual) {
          Text("Belichtungszeit: ${"%.2f".format(exposureMs)} ms")
          Slider(value = exposureMs, onValueChange = { exposureMs = it }, valueRange = 0.1f..50f)
          Text("ISO: ${iso.roundToInt()}")
          Slider(value = iso, onValueChange = { iso = it }, valueRange = 50f..1600f)
        }
      }
    }
  }
}

/* ---------- Analyse + Render ---------- */

data class Metrics(
  val center: Float = 1f,
  val min: Float = Float.NaN,
  val max: Float = Float.NaN,
  val mean: Float = Float.NaN,
  val std: Float = Float.NaN,
  val uniformityMinOverMax: Float = Float.NaN
)

class LumaAnalyzer(
  private val gridW: Int,
  private val onResult: (Bitmap, FloatArray, FloatArray, Metrics) -> Unit
) : ImageAnalysis.Analyzer {

  override fun analyze(image: ImageProxy) {
    val y = image.planes[0]
    val w = image.width
    val h = image.height
    val rs = y.rowStride
    val ps = y.pixelStride
    val buf = y.buffer

    val gridH = max(1, (h * gridW) / w)
    val stepX = w.toFloat() / gridW
    val stepY = h.toFloat() / gridH
    val g = FloatArray(gridW * gridH)
    val hist = IntArray(256)
    var samples = 0

    fun yAt(ix:Int, iy:Int): Int {
      val off = iy * rs + ix * ps
      return buf.get(off).toInt() and 0xff
    }

    var k = 0
    for (gy in 0 until gridH) {
      val yy0 = (gy * stepY).toInt().coerceIn(0, h-1)
      val yy1 = ((gy + 0.5f) * stepY).toInt().coerceIn(0, h-1)
      for (gx in 0 until gridW) {
        val xx0 = (gx * stepX).toInt().coerceIn(0, w-1)
        val xx1 = ((gx + 0.5f) * stepX).toInt().coerceIn(0, w-1)
        val p = (yAt(xx0,yy0) + yAt(xx1,yy0) + yAt(xx0,yy1) + yAt(xx1,yy1)) * 0.25f
        val iv = p.roundToInt().coerceIn(0,255)
        hist[iv]++; samples++
        g[k++] = p
      }
    }

    fun perc(p: Float): Float {
      val tgt = (samples * p).roundToInt().coerceIn(0, samples-1)
      var acc = 0
      for (i in 0..255) { acc += hist[i]; if (acc >= tgt) return i.toFloat() }
      return 255f
    }

    val p1 = perc(0.01f); val p99 = perc(0.99f)
    val scale = if (p99 > p1 + 1f) 1f / (p99 - p1) else 1f
    for (i in g.indices) g[i] = ((g[i] - p1) * scale).coerceIn(0f, 1f)

    val gw = gridW; val gh = gridH
    val c = g[(gh/2)*gw + gw/2].coerceAtLeast(1e-6f)
    var mn = 1f; var mx = 0f; var s = 0f; var s2 = 0f
    for (v in g) {
      val r = v / c
      mn = min(mn, r); mx = max(mx, r); s += r; s2 += r*r
    }
    val n = g.size
    val mean = s / n
    val std = sqrt(max(0f, s2/n - mean*mean))
    val m = Metrics(center = 1f, min = mn, max = mx, mean = mean, std = std,
      uniformityMinOverMax = if (mx > 0f) mn / mx else Float.NaN)

    val bmp = toHeatmapBitmap(g, gw, gh)
    val hProf = FloatArray(gw) { i -> g[(gh/2)*gw + i] / c }
    val vProf = FloatArray(gh) { j -> g[j*gw + gw/2] / c }

    onResult(bmp, hProf, vProf, m)
    image.close()
  }

  private fun toHeatmapBitmap(g: FloatArray, gw:Int, gh:Int): Bitmap {
    val out = IntArray(g.size) { turbo(g[it]) }
    return Bitmap.createBitmap(out, gw, gh, Bitmap.Config.ARGB_8888)
  }
  private fun turbo(x: Float): Int {
    val v = x.coerceIn(0f,1f)
    val r = (34.61 + v*(1172.33 + v*(-10793.56 + v*(33300.12 + v*(-38394.49 + v*16632.1))))) / 255.0
    val g = (23.31 + v*(557.33   + v*(1225.33  + v*(-3574.96 + v*(2794.0    + v*-658.0))))) / 255.0
    val b = (27.2  + v*(3211.1   + v*(-15327.97+ v*(27814.0  + v*(-22569.18 + v*6838.66))))) / 255.0
    fun ch(c: Double) = (255.0 * c.coerceIn(0.0,1.0)).roundToInt()
    return Color.argb(160, ch(r), ch(g), ch(b))
  }
}

/* ---------- UI Helfer ---------- */

@Composable
fun MetricsBar(m: Metrics) {
  Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
    fun T(n:String, v:Float) = Text("$n: " + (if (v.isFinite()) String.format("%.3f", v) else "—"),
      modifier = Modifier.padding(end = 12.dp))
    T("Center", m.center); T("Min", m.min); T("Max", m.max)
    T("Mean", m.mean); T("Std", m.std); T("Min/Max", m.uniformityMinOverMax)
  }
}

@Composable
fun ChartLine(data: FloatArray, title: String, modifier: Modifier = Modifier) {
  Column(modifier) {
    Text(title, style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxSize().padding(top = 4.dp)) {
      if (data.isEmpty()) return@Canvas
      val minY = data.minOrNull() ?: 0f
      val maxY = data.maxOrNull() ?: 1f
      val range = (maxY - minY).takeIf { it > 1e-6f } ?: 1f
      val w = size.width
      val h = size.height
      val dx = w / (data.size - 1).coerceAtLeast(1)
      var x = 0f
      for (i in 1 until data.size) {
        val y0 = h - ((data[i-1]-minY)/range)*h
        val y1 = h - ((data[i]-minY)/range)*h
        drawLine(color = androidx.compose.ui.graphics.Color.Cyan,
          start = androidx.compose.ui.geometry.Offset(x, y0),
          end = androidx.compose.ui.geometry.Offset(x+dx, y1),
          strokeWidth = 3f)
        x += dx
      }
    }
  }
}
