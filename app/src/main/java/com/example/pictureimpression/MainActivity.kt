package com.example.pictureimpression

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pictureimpression.ui.theme.PictureImpressionTheme
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PictureImpressionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ImageProcessingScreen()
                }
            }
        }
    }
}

class ImageProcessor(private val context: Context) {

    // 二值化处理
    fun convertToBinary(src: Bitmap, threshold: Int): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = src.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                val newPixel = if (gray > threshold) Color.WHITE else Color.BLACK
                output.setPixel(x, y, newPixel)
            }
        }
        return output
    }

    // Bitmap 转字节数组 (取模)
    fun bitmapToByteArray(bmp: Bitmap): ByteArray {
        val width = bmp.width
        val height = bmp.height
        val bytesPerRow = (width + 7) / 8 // 每行字节数
        val buffer = ByteArray(bytesPerRow * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (bmp.getPixel(x, y) == Color.BLACK) {
                    val index = y * bytesPerRow + x / 8
                    buffer[index] = (buffer[index].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
        }
        return buffer
    }

    // 格式化字节数组为十六进制字符串
    fun formatByteArray(data: ByteArray, bytesPerLine: Int = 16): String {
        val sb = StringBuilder()
        for ((index, byte) in data.withIndex()) {
            if (index % bytesPerLine == 0) sb.append("\n")
            sb.append("0x${byte.toUByte().toString(16).padStart(2, '0').uppercase()}, ")
        }
        return sb.toString().removeSuffix(", ")
    }

    // 创建临时文件URI (使用FileProvider)
    fun createTempUri(prefix: String = "img_", suffix: String = ".jpg"): Uri {
        val file = File.createTempFile(prefix, suffix, context.cacheDir)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    // 启动裁剪
    fun startCrop(sourceUri: Uri, destinationUri: Uri? = null): UCrop {
        val dest = destinationUri ?: createTempUri("cropped_")
        return UCrop.of(sourceUri, dest)
            .withAspectRatio(250f, 122f)
            .withMaxResultSize(250, 122)
    }
}

class ImageViewModel : ViewModel() {
    var originalBitmap by mutableStateOf<Bitmap?>(null)
    var processedBitmap by mutableStateOf<Bitmap?>(null)
    var binaryData by mutableStateOf("")
    var threshold by mutableStateOf(128)
    var isLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)

    fun processImage(bitmap: Bitmap, processor: ImageProcessor) {
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                withContext(Dispatchers.Default) {
                    originalBitmap = bitmap
                    processedBitmap = processor.convertToBinary(bitmap, threshold)
                    val byteArray = processor.bitmapToByteArray(processedBitmap!!)
                    binaryData = processor.formatByteArray(byteArray)
                }
            } catch (e: Exception) {
                errorMessage = "处理失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun updateThreshold(newValue: Int, processor: ImageProcessor) {
        threshold = newValue
        originalBitmap?.let { processImage(it, processor) }
    }
}

@Composable
fun ImageProcessingScreen(viewModel: ImageViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val context = LocalContext.current
    val processor = remember { ImageProcessor(context) }
    val scope = rememberCoroutineScope()

    // UCrop 启动器
    val cropLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                result.data?.let { intent ->
                    UCrop.getOutput(intent)?.let { uri ->
                        scope.launch(Dispatchers.IO) {
                            try {
                                val bitmap = context.contentResolver.loadBitmap(uri, 250)
                                viewModel.processImage(bitmap, processor)
                            } catch (e: Exception) {
                                viewModel.errorMessage = "加载图片失败: ${e.message}"
                            }
                        }
                    }
                }
            }
            UCrop.RESULT_ERROR -> {
                result.data?.let {
                    val error = UCrop.getError(it)
                    viewModel.errorMessage = "裁剪错误: ${error?.message}"
                }
            }
        }
    }

    // 图片选择器
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val destination = processor.createTempUri("cropped_")
                val cropIntent = processor.startCrop(uri, destination)
                    .getIntent(context)
                cropLauncher.launch(cropIntent)
            } catch (e: Exception) {
                viewModel.errorMessage = "启动裁剪失败: ${e.message}"
            }
        }
    }

    // 显示错误消息
    viewModel.errorMessage?.let {
        ErrorDialog(message = it) { viewModel.errorMessage = null }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 图片显示区域
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ImagePreview(
                    bitmap = viewModel.originalBitmap,
                    label = "原始图片"
                )
                ImagePreview(
                    bitmap = viewModel.processedBitmap,
                    label = "二值化图片"
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 阈值控制
            ThresholdControl(
                threshold = viewModel.threshold,
                onThresholdChange = { viewModel.updateThreshold(it, processor) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 操作按钮
            ActionButtons(
                onSelectImage = { galleryLauncher.launch("image/*") },
                onSaveData = { /* 保存逻辑 */ }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 取模结果显示
            BinaryDataDisplay(data = viewModel.binaryData)
        }

        // 加载状态指示器（居中显示）
        if (viewModel.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun ImagePreview(bitmap: Bitmap?, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier
                    .size(150.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("无图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ThresholdControl(threshold: Int, onThresholdChange: (Int) -> Unit) {
    Column {
        Text("二值化阈值: $threshold", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = threshold.toFloat(),
            onValueChange = { onThresholdChange(it.toInt()) },
            valueRange = 0f..255f,
            steps = 254,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun ActionButtons(onSelectImage: () -> Unit, onSaveData: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Button(onClick = onSelectImage) {
            Text("选择图片")
        }
        Button(onClick = onSaveData) {
            Text("保存数据")
        }
    }
}

@Composable
fun BinaryDataDisplay(data: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        if (data.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.padding(8.dp)
            ) {
                item {
                    SelectionContainer {
                        Text(
                            text = data,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("无取模数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("错误") },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("确定")
            }
        }
    )
}

// 安全的Bitmap加载函数（带采样）
fun android.content.ContentResolver.loadBitmap(uri: Uri, maxSize: Int = 1024): Bitmap {
    val input = this.openInputStream(uri) ?: throw IOException("无法打开输入流")

    // 只获取尺寸信息
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeStream(input, null, options)
    input.close()

    // 计算采样率
    var inSampleSize = 1
    if (options.outHeight > maxSize || options.outWidth > maxSize) {
        val halfHeight = options.outHeight / 2
        val halfWidth = options.outWidth / 2
        while (halfHeight / inSampleSize >= maxSize &&
            halfWidth / inSampleSize >= maxSize) {
            inSampleSize *= 2
        }
    }

    // 使用采样率加载实际图片
    return BitmapFactory.Options().run {
        inSampleSize = this@run.inSampleSize
        inJustDecodeBounds = false

        openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, this)
                ?: throw IOException("无法解码位图")
        } ?: throw IOException("无法打开输入流")
    }
}