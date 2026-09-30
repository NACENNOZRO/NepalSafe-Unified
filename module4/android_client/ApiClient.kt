package com.example.disasterreport

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * ApiClient
 * Module 4 (client side) -- uploads the photo + phone's live GPS to the
 * backend, and parses the returned analysis/location/admin-transmission
 * result. Talks to the FastAPI backend's POST /analyze endpoint.
 *
 * IMPORTANT: BASE_URL below is a placeholder -- point it at wherever you
 * deploy backend/main.py (e.g. "https://your-server.com/" or, for local
 * testing on an emulator, "http://10.0.2.2:8000/" which maps to your
 * dev machine's localhost).
 */
object ApiConfig {
    // TODO: REPLACE_ME with your real backend URL before shipping
    const val BASE_URL = "https://REPLACE_ME.example.com/"
}

data class AnalysisResult(
    val report_generated_utc: String,
    val image_file: String,
    val user_id: String?,
    val location: LocationResult,
    val analysis: AnalysisDetail,
    val admin_transmission: AdminTransmissionStatus
)

data class LocationResult(val lat: Double?, val lng: Double?, val source: String)

data class AnalysisDetail(
    val disaster_type: String,
    val flood_detected: Boolean,
    val water_coverage_pct: Double,
    val damage_detected: Boolean,
    val texture_score: Double,
    val region_texture_score: Double,
    val fire_detected: Boolean,
    val fire_coverage_pct: Double,
    val dark_background_pct: Double,
    val severity: String,
    val severity_score: Double,
    val confidence_pct: Double,
    val is_problem: Boolean,
    val verdict: String
)

data class AdminTransmissionStatus(val transmitted: Boolean, val detail: String?)

interface DisasterApiService {
    @Multipart
    @POST("analyze")
    suspend fun analyzeImage(
        @Part image: MultipartBody.Part,
        @Part("user_id") userId: okhttp3.RequestBody?,
        @Part("lat") lat: okhttp3.RequestBody?,
        @Part("lng") lng: okhttp3.RequestBody?
    ): Response<AnalysisResult>
}

object ApiClient {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(ApiConfig.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: DisasterApiService = retrofit.create(DisasterApiService::class.java)

    /**
     * Uploads the photo (and, if available, the phone's live GPS lat/lng)
     * to the backend. Returns the parsed AnalysisResult on success, or
     * null on network/server failure -- caller (MainActivity) shows the
     * user an appropriate error rather than silently failing.
     */
    suspend fun submitPhoto(
        imageFile: File,
        userId: String?,
        lat: Double?,
        lng: Double?
    ): AnalysisResult? {
        val imageRequestBody = imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
        val imagePart = MultipartBody.Part.createFormData("image", imageFile.name, imageRequestBody)

        val userIdBody = userId?.toRequestBody("text/plain".toMediaTypeOrNull())
        val latBody = lat?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
        val lngBody = lng?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())

        val response = service.analyzeImage(imagePart, userIdBody, latBody, lngBody)
        return if (response.isSuccessful) response.body() else null
    }
}
