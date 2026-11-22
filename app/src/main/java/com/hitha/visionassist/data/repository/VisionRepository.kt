package com.hitha.visionassist.data.repository

import android.content.Context
import android.net.Uri
import com.hitha.visionassist.data.model.VisionAnalysisResult
import com.hitha.visionassist.vision.VisionEngine
import java.io.File

/**
 * Interface defining the Vision repository contract.
 */
interface VisionRepository {
    suspend fun analyzeSurroundings(imageFile: File, userQuestion: String? = null, forceOffline: Boolean = false): VisionAnalysisResult
    suspend fun searchObject(imageFile: File, targetObject: String, forceOffline: Boolean = false): VisionAnalysisResult
    suspend fun countObjects(imageFile: File, targetObject: String, forceOffline: Boolean = false): VisionAnalysisResult
    suspend fun recognizeText(imageFile: File): VisionAnalysisResult
    fun processSelectedUri(uri: Uri, targetFile: File): Boolean
    fun cleanupTemporaryFiles(vararg keepFiles: File?)
    fun close()
}

/**
 * Default implementation of VisionRepository delegating to VisionEngine.
 */
class DefaultVisionRepository(context: Context) : VisionRepository {

    private val visionEngine = VisionEngine(context)

    override suspend fun analyzeSurroundings(
        imageFile: File,
        userQuestion: String?,
        forceOffline: Boolean
    ): VisionAnalysisResult {
        return visionEngine.describeScene(imageFile, userQuestion, forceOffline)
    }

    override suspend fun searchObject(
        imageFile: File,
        targetObject: String,
        forceOffline: Boolean
    ): VisionAnalysisResult {
        return visionEngine.searchObject(imageFile, targetObject, forceOffline)
    }

    override suspend fun countObjects(
        imageFile: File,
        targetObject: String,
        forceOffline: Boolean
    ): VisionAnalysisResult {
        return visionEngine.countObjects(imageFile, targetObject, forceOffline)
    }

    override suspend fun recognizeText(imageFile: File): VisionAnalysisResult {
        return visionEngine.recognizeText(imageFile)
    }

    override fun processSelectedUri(uri: Uri, targetFile: File): Boolean {
        return visionEngine.processSelectedImageUri(uri, targetFile)
    }

    override fun cleanupTemporaryFiles(vararg keepFiles: File?) {
        visionEngine.cleanupTemporaryFiles(*keepFiles)
    }

    override fun close() {
        visionEngine.close()
    }
}
