package com.sharl.musicapp.data

import com.sharl.musicapp.data.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val email: String? = null,
    @SerialName("invite_code") val inviteCode: String? = null
)

@Serializable
data class Friendship(
    val id: Long? = null,
    @SerialName("user_a") val userA: String,
    @SerialName("user_b") val userB: String
)

class ProfileRepository(private val auth: AuthRepository = AuthRepository()) {

    private val db get() = SupabaseClientProvider.client

    private val inviteAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun generateInviteCode(): String =
        (1..6).joinToString("") { inviteAlphabet.random().toString() }

    /** Reads the caller's profile, creating one (with a fresh invite code) if absent. */
    suspend fun ensureProfile(): Profile? {
        val uid = auth.currentUserId() ?: return null
        val existing = db.from("profiles")
            .select { filter { eq("id", uid) } }
            .decodeSingleOrNull<Profile>()
        if (existing != null) return existing

        val email = auth.currentUserEmail()
        val row = Profile(
            id = uid,
            displayName = email?.substringBefore('@'),
            email = email,
            inviteCode = generateInviteCode()
        )
        return try {
            db.from("profiles").insert(row)
            row
        } catch (e: Exception) {
            // Likely a duplicate invite_code collision — retry once with a new code.
            val retry = row.copy(inviteCode = generateInviteCode())
            db.from("profiles").insert(retry)
            retry
        }
    }

    suspend fun lookupByInviteCode(code: String): Profile? =
        db.from("profiles")
            .select { filter { eq("invite_code", code.trim().uppercase()) } }
            .decodeSingleOrNull<Profile>()

    /**
     * Creates a friendship from an invite code. Ordering the pair by least/greatest
     * makes the (user_a,user_b) unique constraint collapse both directions.
     */
    suspend fun addFriendByCode(code: String): Result<Unit> {
        val me = auth.currentUserId()
            ?: return Result.failure(IllegalStateException("Not signed in."))
        val them = lookupByInviteCode(code)
            ?: return Result.failure(IllegalStateException("No user found with that code."))
        if (them.id == me) {
            return Result.failure(IllegalStateException("That's your own invite code."))
        }
        val a = minOf(me, them.id)
        val b = maxOf(me, them.id)
        return try {
            db.from("friendships").insert(Friendship(userA = a, userB = b))
            Result.success(Unit)
        } catch (e: Exception) {
            // Unique violation = already friends; treat as success rather than error.
            Result.success(Unit)
        }
    }

    suspend fun friends(): List<Profile> {
        val uid = auth.currentUserId() ?: return emptyList()
        val links = db.from("friendships")
            .select {
                filter {
                    or {
                        eq("user_a", uid)
                        eq("user_b", uid)
                    }
                }
            }
            .decodeList<Friendship>()
        val ids = links.map { if (it.userA == uid) it.userB else it.userA }
        if (ids.isEmpty()) return emptyList()
        return db.from("profiles")
            .select { filter { isIn("id", ids) } }
            .decodeList()
    }
}
