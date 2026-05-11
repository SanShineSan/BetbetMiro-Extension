package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.DataStoreHelper.ResumeWatchingResult
import com.phisher98.UltimaStorageManager as sm

object WatchSyncUtils {
    data class WatchSyncCreds(
            @param:JsonProperty("token") var token: String? = null,
            @param:JsonProperty("projectNum") var projectNum: Int? = null,
            @param:JsonProperty("deviceName") var deviceName: String? = null,
            @param:JsonProperty("deviceId") var deviceId: String? = null, // draftIssueID
            @param:JsonProperty("itemId") var itemId: String? = null, // projectItemID
            @param:JsonProperty("projectId") var projectId: String? = null,
            @param:JsonProperty("isThisDeviceSync") var isThisDeviceSync: Boolean = false,
            @param:JsonProperty("enabledDevices") var enabledDevices: MutableList<String>? = null
    ) {
        data class APIRes(@param:JsonProperty("data") var data: Data) {
            data class Data(
                    @param:JsonProperty("viewer") var viewer: Viewer?,
                    @param:JsonProperty("addProjectV2DraftIssue") var issue: Issue?,
                    @param:JsonProperty("deleteProjectV2Item") var delItem: DelItem?
            ) {
                data class Viewer(@param:JsonProperty("projectV2") var projectV2: ProjectV2) {
                    data class ProjectV2(
                            @param:JsonProperty("id") var id: String,
                            @param:JsonProperty("items") var items: Items?
                    ) {
                        data class Items(
                                @param:JsonProperty("nodes") var nodes: Array<Node>?,
                        ) {
                            data class Node(
                                    @param:JsonProperty("id") var id: String,
                                    @param:JsonProperty("content") var content: Content
                            ) {
                                data class Content(
                                        @param:JsonProperty("id") var id: String,
                                        @param:JsonProperty("title") var title: String,
                                        @param:JsonProperty("bodyText") var bodyText: String,
                                )
                            }
                        }
                    }
                }
                data class Issue(@param:JsonProperty("projectItem") var projectItem: ProjectItem) {
                    data class ProjectItem(
                            @param:JsonProperty("id") var id: String,
                            @param:JsonProperty("content") var content: Content
                    ) {
                        data class Content(@param:JsonProperty("id") var id: String)
                    }
                }
                data class DelItem(@param:JsonProperty("deletedItemId") var deletedItemId: String)
            }
        }

        data class SyncDevice(
                @param:JsonProperty("name") var name: String,
                @param:JsonProperty("deviceId") var deviceId: String, // draftIssueID // for add update
                @param:JsonProperty("itemId") var itemId: String, // projectItemID // for delete
                @param:JsonProperty("syncedData") var syncedData: List<ResumeWatchingResult>? = null
        )

        private val apiUrl = "https://api.github.com/graphql"

        private fun Any.toStringData(): String {
            return mapper.writeValueAsString(this)
        }

        fun isLoggedIn(): Boolean {
            return !(token.isNullOrEmpty() ||
                    projectNum == null ||
                    deviceName.isNullOrEmpty() ||
                    projectId.isNullOrEmpty())
        }

        private suspend fun apiCall(query: String): APIRes? {
            val apiUrl = "https://api.github.com/graphql"
            val header =
                    mapOf(
                            "Content-Type" to "application/json",
                            "Authorization" to "Bearer " + (token ?: return null)
                    )
            val data = """ { "query": ${query} } """
            val test = app.post(apiUrl, headers = header, json = data)
            val res = test.parsedSafe<APIRes>()
            return res
        }

        suspend fun syncProjectDetails(): Pair<Boolean, String?> {
            val failure = false to "something went wrong"
            val query =
                    """ query Viewer { viewer { projectV2(number: ${projectNum ?: return failure}) { id } } } """
            val res = apiCall(query.toStringData()) ?: return failure
            projectId = res.data.viewer?.projectV2?.id ?: return failure
            sm.deviceSyncCreds = this
            return true to "Project details saved"
        }

        suspend fun registerThisDevice(): Pair<Boolean, String?> {
            val failure = false to "something went wrong"
            if (!isLoggedIn()) return failure
            val syncData = getResumeWatching()?.toStringData() ?: "[]"
            val data = base64Encode(syncData.toByteArray())
            val query =
                    """ mutation AddProjectV2DraftIssue { addProjectV2DraftIssue( input: { projectId: "$projectId", title: "$deviceName", body: "$data" } ) { projectItem { id content { ... on DraftIssue { id } } } } } """
            val res = apiCall(query.toStringData()) ?: return failure
            itemId = res.data.issue?.projectItem?.id ?: return failure
            deviceId = res.data.issue?.projectItem?.content?.id ?: return failure
            isThisDeviceSync = true
            sm.deviceSyncCreds = this
            return true to "Device is registered"
        }

        suspend fun deregisterThisDevice(): Pair<Boolean, String?> {
            val failure = false to "something went wrong"
            if (!isLoggedIn()) return failure
            val query =
                    """ mutation DeleteIssue { deleteProjectV2Item( input: { projectId: "$projectId" itemId: "$itemId" } ) { deletedItemId } } """
            val res = apiCall(query.toStringData()) ?: return failure
            if (res.data.delItem?.deletedItemId.equals(itemId)) {
                itemId = null
                deviceId = null
                isThisDeviceSync = false
                sm.deviceSyncCreds = this
                return true to "Device de-registered"
            } else return failure
        }

        suspend fun syncThisDevice(): Pair<Boolean, String?> {
            val failure = false to "something went wrong"
            if (!isLoggedIn()) return failure
            if (!isThisDeviceSync) return failure
            val syncData = getResumeWatching()?.toStringData() ?: "[]"
            val data = base64Encode(syncData.toByteArray())
            val query =
                    """ mutation UpdateProjectV2DraftIssue { updateProjectV2DraftIssue( input: { draftIssueId: "$deviceId", title: "$deviceName", body: "$data" } ) { draftIssue { id } } } """
            apiCall(query.toStringData()) ?: return failure
            return true to "sync complete"
        }

        suspend fun fetchDevices(): List<SyncDevice>? {
            if (!isLoggedIn()) return null
            val query =
                    """ query User { viewer { projectV2(number: ${projectNum ?: return null}) { id items(first: 50) { nodes { id content { ... on DraftIssue { id title bodyText } } } totalCount } } } } """
            val res = apiCall(query.toStringData()) ?: return null
            val data =
                    res.data.viewer?.projectV2?.items?.nodes?.map {
                        val data = base64Decode(it.content.bodyText)
                        val syncData =
                                parseJson<Array<ResumeWatchingResult>?>(data)?.toList()
                                        ?: return null
                        SyncDevice(it.content.title, it.content.id, it.id, syncData)
                    }
            return data
        }
    }
}
