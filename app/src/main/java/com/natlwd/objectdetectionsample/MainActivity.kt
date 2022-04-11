package com.natlwd.objectdetectionsample

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.natlwd.objectdetectionsample.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

@androidx.camera.core.ExperimentalGetImage
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var objectDetector: ObjectDetector? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var analyzer: ObjectImageAnalyzer? = null

    private var tts: TextToSpeech? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
        private const val MAX_FONT_SIZE = 96F
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupView()
        setupListener()
    }

    override fun onDestroy() {
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
        super.onDestroy()
    }

    private fun setupView() {
        //init parameters
        tts = TextToSpeech(this, this)

        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        objectDetector =
            ObjectDetection.getClient(options)

        //check permission
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun setupListener() {
        binding.takePhotoButton.setOnClickListener {
            takePhoto()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                animateFlash()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            //set up data
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            imageCapture = ImageCapture.Builder().build()
            analyzer = ObjectImageAnalyzer()
            analysisUseCase = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysisUseCase?.setAnalyzer(
                ContextCompat.getMainExecutor(this),
                analyzer ?: return@addListener
            )

            try {
                cameraProvider?.unbindAll()

                //bind camera
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, analysisUseCase
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        if (imageCapture == null) return

        //file content
        val fileName = "JPEG_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            .build()

        imageCapture?.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Log.d(TAG, msg)

                    //convert Uri to Bitmap
                    val bitmap =
                        MediaStore.Images.Media.getBitmap(contentResolver, output.savedUri)
                    val rotatedBitmap = bitmap.rotate(90f)

                    showImageCapture(rotatedBitmap)
                }

            }
        )
    }

    fun Bitmap.rotate(degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun showImageCapture(bitmap: Bitmap) {
        //stop cameraX
        cameraProvider?.unbindAll()

        //process the image
        val image = InputImage.fromBitmap(bitmap, 0)
        objectDetector?.process(image)
            ?.addOnFailureListener { e ->
                e.printStackTrace()
                Toast.makeText(this, "Process Image Failure!", Toast.LENGTH_SHORT).show()
            }?.addOnSuccessListener { results ->
                //draw canvas on image

                /**
                 *  Credit: https://developers.google.com/codelabs/tflite-object-detection-android#0
                 */

                val detectedObjects = results.map {
                    var text = "Unknown"
                    if (it.labels.isNotEmpty()) {
                        val firstLabel = it.labels.first()
                        text = "${firstLabel.text}, ${firstLabel.confidence.times(100).toInt()}%"
                        //speak the result
                        tts?.speak(
                            firstLabel.text,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            ""
                        )
                    }
                    BoxWithText(it.boundingBox, text)
                }
                val visualizedResult = drawDetectionResult(bitmap, detectedObjects)
                binding.previewImageView.setImageBitmap(visualizedResult)

                //hide button
                binding.previewImageView.visibility = View.VISIBLE
                binding.takePhotoButton.visibility = View.GONE
            }

        lifecycleScope.launch(Dispatchers.IO) {
            delay(4000L)

            lifecycleScope.launch(Dispatchers.Main) {
                //show button
                binding.previewImageView.setImageDrawable(null)
                binding.previewImageView.visibility = View.GONE
                binding.takePhotoButton.visibility = View.VISIBLE

                //start cameraX
                imageCapture = ImageCapture.Builder().build()
                cameraProvider?.bindToLifecycle(
                    this@MainActivity,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    Preview.Builder()
                        .build()
                        .also {
                            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                        },
                    imageCapture,
                    analysisUseCase
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun animateFlash() {
        binding.root.postDelayed({
            binding.root.foreground = ColorDrawable(Color.WHITE)
            binding.root.postDelayed({
                binding.root.foreground = null
            }, 50)
        }, 100)
    }

    override fun onInit(p0: Int) {
        if (p0 == TextToSpeech.SUCCESS) {
            // set US English as language for tts
            val result = tts!!.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language specified is not supported!")
            }
        } else {
            Log.e("TTS", "Initilization Failed!")
        }
    }

    /**
     * Credit: https://developers.google.com/codelabs/tflite-object-detection-android#0
     */
    private fun drawDetectionResult(
        bitmap: Bitmap,
        detectionResults: List<BoxWithText>
    ): Bitmap {
        val outputBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)
        val pen = Paint()
        pen.textAlign = Paint.Align.LEFT

        detectionResults.forEach {
            // draw bounding box
            pen.color = Color.RED
            pen.strokeWidth = 8F
            pen.style = Paint.Style.STROKE
            val box = it.box
            canvas.drawRect(box, pen)

            val tagSize = Rect(0, 0, 0, 0)

            // calculate the right font size
            pen.style = Paint.Style.FILL_AND_STROKE
            pen.color = Color.YELLOW
            pen.strokeWidth = 2F

            pen.textSize = MAX_FONT_SIZE
            pen.getTextBounds(it.text, 0, it.text.length, tagSize)
            val fontSize: Float = pen.textSize * box.width() / tagSize.width()

            // adjust the font size so texts are inside the bounding box
            if (fontSize < pen.textSize) pen.textSize = fontSize

            var margin = (box.width() - tagSize.width()) / 2.0F
            if (margin < 0F) margin = 0F
            canvas.drawText(
                it.text, box.left + margin,
                box.top + tagSize.height().times(1F), pen
            )
        }
        return outputBitmap
    }
}

@androidx.camera.core.ExperimentalGetImage
private class ObjectImageAnalyzer : ImageAnalysis.Analyzer {

    var imageProxy: ImageProxy? = null

    override fun analyze(imageProxy: ImageProxy) {
        this.imageProxy = imageProxy
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            //do somethings
        }
    }
}

data class BoxWithText(val box: Rect, val text: String)
