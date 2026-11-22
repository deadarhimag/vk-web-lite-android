package com.example.vkweb.owner

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OwnerRefreshResultTest {
    private fun response(payload: JSONObject) =
        JSONObject().put("operation", "2").put("key", "/club42").put("payload", payload)

    @Test fun emptyNetworkWallIsAValidRefreshResult() {
        val result = OwnerRefreshResult.fromJson(
            response(JSONObject().put("key", "/club42").put("ownerId", -42).put("wallSeen", true)),
        )
        assertEquals("2", result?.operation)
        assertNotNull(result?.payload)
        assertEquals(emptyList<WallPost>(), result?.payload?.posts)
    }

    @Test fun oldSnapshotOrAnotherRouteCannotSatisfyARefresh() {
        val wall = JSONObject().put("key", "/club42").put("ownerId", -42).put("wallSeen", true)
        assertNull(OwnerRefreshResult.fromJson(response(wall.put("snapshot", true)))?.payload)
        assertNull(OwnerRefreshResult.fromJson(response(wall.put("snapshot", false).put("key", "/club43")))?.payload)
    }
}
