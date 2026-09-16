package com.tadiwaprintbuddy.app.api

import com.tadiwaprintbuddy.app.BuildConfig

object NetworkConfig {
    val BASE_URL: String = BuildConfig.API_BASE_URL
        .trim()
        .trimEnd('/')
        .let { url ->
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "API_BASE_URL must be a valid http(s) URL. Current value: $url"
            }
            "$url/"
        }
}
