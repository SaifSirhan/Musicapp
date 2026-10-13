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

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.startsWith("http") &&
            BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
}
