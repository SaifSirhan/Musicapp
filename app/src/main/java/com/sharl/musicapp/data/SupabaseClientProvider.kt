package com.sharl.musicapp.data

import com.sharl.musicapp.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

/**
 * Holds the single Supabase client. Values come from BuildConfig (fed by the
 * gitignored secrets.properties), so nothing is hardcoded here.
 *
 * [isConfigured] is false until SUPABASE_URL / SUPABASE_ANON_KEY are filled in;
 * callers must check it before touching [client], because building a client with
 * a blank URL throws.
 */
object SupabaseClientProvider {

    /**
     * Supabase URL normalized to the project base: no trailing slash and no
     * `/rest/v1` suffix. supabase-kt builds the module endpoints itself and throws
     * if the URL already contains `/rest/v1` — a common copy-paste from the
     * dashboard's REST settings.
     */
    private val baseUrl: String
        get() = BuildConfig.SUPABASE_URL
            .trim()
            .trimEnd('/')
            .removeSuffix("/rest/v1")

    val isConfigured: Boolean
        get() = baseUrl.startsWith("http") &&
            BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = baseUrl,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
}
