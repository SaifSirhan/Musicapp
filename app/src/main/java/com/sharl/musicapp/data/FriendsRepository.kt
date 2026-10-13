package com.sharl.musicapp.data

import android.content.Context
import com.sharl.musicapp.data.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SharedPlaylist(
    val id: Long? = null,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("playlist_name") val playlistName: String,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SharedPlaylistSong(
    val id: Long? = null,
    @SerialName("shared_playlist_id") val sharedPlaylistId: Long,
    @SerialName("video_id") val videoId: String,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val position: Int
)

@Serializable
data class NowPlaying(
    @SerialName("user_id") val userId: String,
    @SerialName("video_id") val videoId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

class FriendsRepository(
    private val auth: AuthRepository = AuthRepository()
) {

    private val db get() = SupabaseClientProvider.client

    /** The caller's own shared playlist, if any (one per owner). */
    suspend fun mySharedPlaylist(): SharedPlaylist? {
        val uid = auth.currentUserId() ?: return null
        return db.from("shared_playlists")
            .select { filter { eq("owner_id", uid) } }
            .decodeSingleOrNull()
    }

    /**
     * Replaces the caller's shared playlist with [playlistName]'s local tracks.
     * Deleting the old row cascades its songs (FK on delete cascade).
     */
    suspend fun sharePlaylist(context: Context, playlistId: Long, playlistName: String) {
        val uid = auth.currentUserId() ?: throw IllegalStateException("Not signed in.")
        mySharedPlaylist()?.id?.let { oldId ->
            db.from("shared_playlists").delete { filter { eq("id", oldId) } }
        }
        val songs = PlaylistRepository(context).songs(playlistId).first()
        val inserted = db.from("shared_playlists")
            .insert(SharedPlaylist(ownerId = uid, playlistName = playlistName)) { select() }
            .decodeSingle<SharedPlaylist>()
        val sharedId = inserted.id
            ?: throw IllegalStateException("Server did not return the shared playlist id.")
        if (songs.isNotEmpty()) {
            db.from("shared_playlist_songs").insert(
                songs.mapIndexed { idx, s ->
                    SharedPlaylistSong(
                        sharedPlaylistId = sharedId,
                        videoId = s.videoId,
                        title = s.title,
                        artist = s.artist,
                        thumbnailUrl = s.thumbnailUrl,
                        position = idx
                    )
                }
            )
        }
    }

    suspend fun stopSharing() {
        mySharedPlaylist()?.id?.let { id ->
            db.from("shared_playlists").delete { filter { eq("id", id) } }
        }
    }

    /** Shared playlists belonging to the caller's friends. */
    suspend fun friendsPlaylists(friendIds: List<String>): List<SharedPlaylist> {
        if (friendIds.isEmpty()) return emptyList()
        return db.from("shared_playlists")
            .select { filter { isIn("owner_id", friendIds) } }
            .decodeList()
    }

    suspend fun songsOf(sharedPlaylistId: Long): List<SharedPlaylistSong> =
        db.from("shared_playlist_songs")
            .select { filter { eq("shared_playlist_id", sharedPlaylistId) } }
            .decodeList<SharedPlaylistSong>()
            .sortedBy { it.position }

    /** Upserts the caller's now-playing row. No-op when there is nothing playing. */
    suspend fun updateNowPlaying(videoId: String?, title: String?, artist: String?) {
        val uid = auth.currentUserId() ?: return
        db.from("now_playing").upsert(
            NowPlaying(userId = uid, videoId = videoId, title = title, artist = artist)
        ) {
            onConflict = "user_id"
        }
    }

    /**
     * Emits the friends' now-playing rows, then re-reads on every Realtime change.
     * Torn down when the collecting scope is cancelled (screen leaves composition).
     */
    fun observeFriendsNowPlaying(friendIds: List<String>): Flow<List<NowPlaying>> = flow {
        if (!auth.configured || friendIds.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        suspend fun readLatest() = db.from("now_playing")
            .select { filter { isIn("user_id", friendIds) } }
            .decodeList<NowPlaying>()

        emit(readLatest())
        val channel = SupabaseClientProvider.client.channel("now_playing_friends")
        channel.subscribe()
        try {
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "now_playing"
            }.collect {
                emit(readLatest())
            }
        } finally {
            channel.unsubscribe()
        }
    }
}
