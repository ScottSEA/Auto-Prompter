package com.scottsea.autoprompter.androidmedia

import androidx.camera.core.SurfaceRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class CameraXEncoderVideoOutputTest {
    @Test
    fun CameraXSurfaceResultsMapToStableDomainOutcomes() {
        assertEquals(
            VideoSurfaceOutcome.UsedSuccessfully,
            videoSurfaceOutcomeForCode(SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY),
        )
        assertEquals(
            VideoSurfaceOutcome.RequestCancelled,
            videoSurfaceOutcomeForCode(SurfaceRequest.Result.RESULT_REQUEST_CANCELLED),
        )
        assertEquals(
            VideoSurfaceOutcome.InvalidSurface,
            videoSurfaceOutcomeForCode(SurfaceRequest.Result.RESULT_INVALID_SURFACE),
        )
    }
}
