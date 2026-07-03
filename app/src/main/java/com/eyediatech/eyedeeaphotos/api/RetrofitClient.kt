package com.eyediatech.eyedeeaphotos.api

import android.content.Context
import com.eyediatech.eyedeeaphotos.BuildConfig
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    val BASE_URL = BuildConfig.BASE_URL

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.HEADERS // Changed from BODY to HEADERS to prevent OOM/stalling on large multipart uploads
    }

    private var okHttpClient: OkHttpClient? = null
    private var apiService: ApiService? = null

    fun init(context: Context) {
        if (okHttpClient == null) {
            val authRepository = AuthRepository(context.applicationContext)
            val authInterceptor = AuthInterceptor(context.applicationContext, authRepository)
            
            val userAgent = com.eyediatech.eyedeeaphotos.utils.UserAgentUtils.getUserAgent(context)
            val userAgentInterceptor = okhttp3.Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .build()
                chain.proceed(request)
            }
            
            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.MINUTES)
                .addInterceptor(userAgentInterceptor)
                .addInterceptor(authInterceptor)
                .addInterceptor(loggingInterceptor)
                .build()
        }
    }

    val instance: ApiService
        get() {
            if (apiService == null) {
                apiService = Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(okHttpClient ?: OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(15, java.util.concurrent.TimeUnit.MINUTES)
                        .addInterceptor(loggingInterceptor)
                        .build())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(ApiService::class.java)
            }
            return apiService!!
        }
}
