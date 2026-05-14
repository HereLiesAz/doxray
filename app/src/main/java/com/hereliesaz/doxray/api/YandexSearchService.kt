package com.hereliesaz.doxray.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.Response

/**
 * Actual implementation of Yandex Reverse Image Search via SerpApi.
 */
class YandexSearchService {

    private val TAG = "YandexSearchService"
    // TODO: Securely inject this via BuildConfig in a production environment
    private val SERP_API_KEY = "YOUR_SERPAPI_API_KEY" 

    private interface SerpApi {
        @GET("search.json")
        suspend fun searchYandexImages(
            @Query("engine") engine: String = "yandex_images",
            @Query("url") imageUrl: String,
            @Query("api_key") apiKey: String
        ): Response<SerpApiResponse>
    }

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://serpapi.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(SerpApi::class.java)

    suspend fun searchIdentity(imageUrl: String): Result? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Searching Yandex via SerpApi for: $imageUrl")
        
        try {
            val response = api.searchYandexImages(imageUrl = imageUrl, apiKey = SERP_API_KEY)
            
            if (response.isSuccessful) {
                val body = response.body()
                val imageResults = body?.images_results ?: emptyList()
                
                // Extract titles and sources from the results to form an identity correlation
                val identities = imageResults.map { it.title }.distinct().take(5)
                val socialLinks = imageResults.map { it.source }.distinct().take(5)
                
                Log.d(TAG, "Successfully fetched ${imageResults.size} image results from Yandex.")
                Result(identities, socialLinks)
            } else {
                Log.e(TAG, "SerpApi Error: ${response.code()} - ${response.errorBody()?.string()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network exception during Yandex search", e)
            null
        }
    }

    data class Result(
        val identities: List<String>,
        val socialLinks: List<String>
    )

    // Data models for SerpApi JSON response
    data class SerpApiResponse(
        val search_metadata: SearchMetadata?,
        val images_results: List<ImageResult>?
    )

    data class SearchMetadata(val status: String, val id: String)

    data class ImageResult(
        val position: Int,
        val title: String,
        val source: String,
        val original: String
    )
}
