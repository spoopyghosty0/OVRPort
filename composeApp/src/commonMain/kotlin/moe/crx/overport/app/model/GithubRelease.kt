package moe.crx.overport.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GithubRelease(
    @SerialName("html_url")
    var htmlUrl: String,
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("published_at")
    val publishedAt: String,
) {
    fun isNewerThan(currentVersion: String): Boolean {
        val current = currentVersion.split('.').map { it.toIntOrNull() }
        val candidate = tagName.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() }
        if (current.size != 3 || candidate.size != 3 || current.any { it == null } || candidate.any { it == null }) {
            return false
        }
        val difference = candidate.indices.firstOrNull { candidate[it] != current[it] } ?: return false
        return candidate[difference]!! > current[difference]!!
    }
}