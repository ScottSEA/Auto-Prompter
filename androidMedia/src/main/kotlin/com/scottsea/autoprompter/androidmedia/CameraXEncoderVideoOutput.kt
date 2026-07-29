package com.scottsea.autoprompter.androidmedia

import android.view.Surface
import androidx.camera.core.SurfaceRequest
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoOutput
import java.util.concurrent.Executor

data class EncoderSurfaceRequest(
    val width: Int,
    val height: Int,
    val dynamicRangeEncoding: Int,
    val bitDepth: Int,
    val expectedFrameRateLower: Int,
    val expectedFrameRateUpper: Int,
)

enum class VideoSurfaceOutcome {
    UsedSuccessfully,
    RequestCancelled,
    InvalidSurface,
    SurfaceAlreadyProvided,
    WillNotProvideSurface,
    Unknown,
}

interface EncoderSurfaceLease {
    val surface: Surface
    fun complete(outcome: VideoSurfaceOutcome)
}

fun interface EncoderSurfaceFactory {
    fun create(request: EncoderSurfaceRequest): EncoderSurfaceLease
}

/**
 * CameraX 1.6.1 custom output seam for an app-owned MediaCodec input surface.
 *
 * This class proves public SurfaceRequest handoff without using CameraX Recorder (which would own
 * microphone capture). Actual encoder timing/quality remains device-gated.
 */
class CameraXEncoderVideoOutput(
    private val callbackExecutor: Executor,
    private val surfaceFactory: EncoderSurfaceFactory,
) : VideoOutput {
    override fun onSurfaceRequested(request: SurfaceRequest) {
        val resolution = request.resolution
        val frameRate = request.expectedFrameRate
        val lease =
            try {
                surfaceFactory.create(
                    EncoderSurfaceRequest(
                        width = resolution.width,
                        height = resolution.height,
                        dynamicRangeEncoding = request.dynamicRange.encoding,
                        bitDepth = request.dynamicRange.bitDepth,
                        expectedFrameRateLower = frameRate.lower,
                        expectedFrameRateUpper = frameRate.upper,
                    ),
                )
            } catch (_: Exception) {
                request.willNotProvideSurface()
                return
            }
        request.provideSurface(lease.surface, callbackExecutor) { result ->
            lease.complete(videoSurfaceOutcomeForCode(result.resultCode))
        }
    }
}

fun createCameraXVideoCapture(
    output: CameraXEncoderVideoOutput,
): VideoCapture<CameraXEncoderVideoOutput> = VideoCapture.withOutput(output)

internal fun videoSurfaceOutcomeForCode(code: Int): VideoSurfaceOutcome =
    when (code) {
        SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY ->
            VideoSurfaceOutcome.UsedSuccessfully
        SurfaceRequest.Result.RESULT_REQUEST_CANCELLED ->
            VideoSurfaceOutcome.RequestCancelled
        SurfaceRequest.Result.RESULT_INVALID_SURFACE ->
            VideoSurfaceOutcome.InvalidSurface
        SurfaceRequest.Result.RESULT_SURFACE_ALREADY_PROVIDED ->
            VideoSurfaceOutcome.SurfaceAlreadyProvided
        SurfaceRequest.Result.RESULT_WILL_NOT_PROVIDE_SURFACE ->
            VideoSurfaceOutcome.WillNotProvideSurface
        else -> VideoSurfaceOutcome.Unknown
    }
