package io.embodit.sunpatch.data

import io.embodit.sunpatch.domain.AnchorPose
import io.embodit.sunpatch.domain.Vec3

/** A world anchor as the app sees it. [id] is the platform's UUID as a string. */
data class AnchorRecord(val id: String, val pose: AnchorPose)

sealed interface AnchorOutcome<out T> {
    data class Ok<T>(val value: T) : AnchorOutcome<T>

    data class Failed(val code: Int, val message: String) : AnchorOutcome<Nothing>
}

/** The platform's persistent anchors (`platform.anchor.WorldAnchorGateway`). One anchor per window / marker. */
interface AnchorGateway {
    suspend fun create(position: Vec3, name: String): AnchorOutcome<AnchorRecord>

    /** All anchors of this app in this space; an empty store is `Ok(emptyList())`. */
    suspend fun loadAll(): AnchorOutcome<List<AnchorRecord>>

    suspend fun remove(anchorId: String): AnchorOutcome<Unit>
}
