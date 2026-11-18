package com.eyediatech.eyedeeaphotos.util

import android.content.Context
import com.eyediatech.eyedeeaphotos.api.RetrofitClient
import com.eyediatech.eyedeeaphotos.repository.AuthRepository
import com.eyediatech.eyedeeaphotos.utils.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Reads upload allowance from raw JSON so R8 cannot rename the keys.
 * Fields are taken from "data" first, then from the root object.
 */
object UploadAllowance {

    private const val TAG = "ALLOWANCE"
    private const val PREFS = "UploadPrefs"
    private const val KEY_RESUME_AT = "uploadResumeAt"
    private const val UPLOAD_NOT_ENABLED = "Upload is not enabled."

    private val timestampPattern = Regex(
        """^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})$""",
        RegexOption.IGNORE_CASE
    )

    sealed class Gate {
        data object Allowed : Gate()
        data class Blocked(val message: String) : Gate()
    }

    data class Status(
        val uploadsEnabled: Boolean?,
        val uploadResumeAt: String?
    )

    fun parse(body: String?): Status {
        if (body.isNullOrBlank()) {
            return Status(uploadsEnabled = null, uploadResumeAt = null)
        }
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            return Status(uploadsEnabled = null, uploadResumeAt = null)
        }
        val enabled = findBoolean(json, "uploadsEnabled")
        val resume = findString(json, "uploadResumeAt")
            ?: resumeFromSide(json.optJSONObject("data"))
            ?: resumeFromSide(json)
        val resolved = when (enabled) {
            null -> if (allowanceSideExhausted(json)) false else null
            else -> enabled
        }
        return Status(uploadsEnabled = resolved, uploadResumeAt = resume)
    }

    fun blockedMessage(resumeAt: String?): String {
        val localTime = resumeAt?.let { formatResumeLocal(it) }
        return if (!localTime.isNullOrEmpty()) {
            "You can upload after $localTime"
        } else {
            "Uploads are used up for this period."
        }
    }

    /**
     * Missing credentials, transport errors, and non-200 responses allow the upload.
     * A 200 body with uploadsEnabled false blocks it. A 200 body without the flag is logged and allowed.
     */
    suspend fun evaluate(context: Context, token: String?, householdId: String?): Gate {
        if (householdId.isNullOrEmpty() || token.isNullOrEmpty()) {
            return Gate.Allowed
        }
        return try {
            val response = withContext(Dispatchers.IO) {
                RetrofitClient.instance.getAllowance("Bearer $token", householdId)
            }
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                FileLogger.e(TAG, "API failed: ${response.code()} $errorBody")
                if (isUploadPermissionDenied(response.code(), errorBody)) {
                    return Gate.Blocked(UPLOAD_NOT_ENABLED)
                }
            } else {
                val body = response.body()?.string()
                FileLogger.d(TAG, "Allowance ${response.code()} ${body?.take(800)}")
                val status = parse(body)
                when (status.uploadsEnabled) {
                    false -> {
                        saveResumeAt(context, status.uploadResumeAt)
                        return Gate.Blocked(blockedMessage(status.uploadResumeAt))
                    }
                    true -> clearResumeAt(context)
                    null -> FileLogger.e(TAG, "Allowance response missing uploadsEnabled: $body")
                }
            }
            if (isMemberUploadDisabled(context, token, householdId)) {
                return Gate.Blocked(UPLOAD_NOT_ENABLED)
            }
            if (isResumeWindowActive(context)) {
                return Gate.Blocked(blockedMessage(storedResumeAt(context)))
            }
            Gate.Allowed
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to check upload allowance", e)
            if (isResumeWindowActive(context)) {
                Gate.Blocked(blockedMessage(storedResumeAt(context)))
            } else {
                Gate.Allowed
            }
        }
    }

    fun saveResumeAt(context: Context, resumeAt: String?) {
        if (resumeAt.isNullOrEmpty()) return
        prefs(context).edit().putString(KEY_RESUME_AT, resumeAt).apply()
    }

    fun clearResumeAt(context: Context) {
        prefs(context).edit().remove(KEY_RESUME_AT).apply()
    }

    fun storedResumeAt(context: Context): String? {
        return prefs(context).getString(KEY_RESUME_AT, null)
    }

    /**
     * True while the stored resume time is still in the future.
     * An unparseable value is cleared so a bad string cannot block uploads forever.
     */
    fun isResumeWindowActive(context: Context): Boolean {
        val resumeAtStr = storedResumeAt(context) ?: return false
        val resumeAtDate = parseInstant(resumeAtStr)
        if (resumeAtDate == null) {
            clearResumeAt(context)
            return false
        }
        if (Date().before(resumeAtDate)) {
            return true
        }
        clearResumeAt(context)
        return false
    }

    fun formatResumeLocal(resumeAt: String): String? {
        val date = parseInstant(resumeAt) ?: return null
        val localFormat = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
        localFormat.timeZone = TimeZone.getDefault()
        return localFormat.format(date)
    }

    fun parseInstant(resumeAt: String): Date? {
        val match = timestampPattern.matchEntire(resumeAt.trim()) ?: return null
        val millis = when (val fraction = match.groupValues[3]) {
            "" -> "000"
            else -> fraction.take(3).padEnd(3, '0')
        }
        val offsetMinutes = offsetMinutes(match.groupValues[4]) ?: return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.isLenient = false
            val utcClock = format.parse("${match.groupValues[1]}T${match.groupValues[2]}.$millis") ?: return null
            Date(utcClock.time - offsetMinutes * 60_000L)
        } catch (_: Exception) {
            null
        }
    }

    private fun offsetMinutes(zone: String): Int? {
        if (zone.equals("Z", ignoreCase = true)) return 0
        val match = Regex("""^([+-])(\d{2}):?(\d{2})$""").matchEntire(zone) ?: return null
        val hours = match.groupValues[2].toIntOrNull() ?: return null
        val minutes = match.groupValues[3].toIntOrNull() ?: return null
        if (hours > 18 || minutes > 59) return null
        val sign = if (match.groupValues[1] == "-") -1 else 1
        return sign * (hours * 60 + minutes)
    }

    private fun isUploadPermissionDenied(code: Int, body: String?): Boolean {
        if (code != 403 || body.isNullOrBlank()) return false
        return body.contains("can_upload") || body.contains("HOUSEHOLD_CAPABILITY_REQUIRED")
    }

    private suspend fun isMemberUploadDisabled(context: Context, token: String, householdId: String): Boolean {
        return try {
            val response = withContext(Dispatchers.IO) {
                RetrofitClient.instance.getHouseholdMembers("Bearer $token", householdId)
            }
            if (!response.isSuccessful) {
                FileLogger.e(TAG, "Members API failed: ${response.code()} ${response.errorBody()?.string()}")
                return false
            }
            val body = response.body()?.string()
            val auth = AuthRepository(context)
            val disabled = memberUploadDisabled(body, userId(auth), auth.getEmail())
            FileLogger.d(TAG, "Member upload disabled=$disabled")
            disabled
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to check upload permission", e)
            false
        }
    }

    private fun memberUploadDisabled(body: String?, userId: Long?, email: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            return false
        }
        val members = json.optJSONArray("members")
            ?: json.optJSONObject("data")?.optJSONArray("members")
            ?: return false
        val normalizedEmail = email?.trim()?.lowercase(Locale.US)
        for (index in 0 until members.length()) {
            val member = members.optJSONObject(index) ?: continue
            if (!memberMatches(member, userId, normalizedEmail)) continue
            return readEnabledFlag(member, "can_upload") == false
        }
        return false
    }

    private fun memberMatches(member: JSONObject, userId: Long?, email: String?): Boolean {
        val memberId = readLong(member, "user_id")
        if (userId != null && memberId != null && userId == memberId) return true
        val memberEmail = readString(member, "email")?.trim()?.lowercase(Locale.US)
        return !email.isNullOrEmpty() && email == memberEmail
    }

    private fun userId(auth: AuthRepository): Long? {
        val raw = auth.getUserJson() ?: return null
        return try {
            readLong(JSONObject(raw), "id")
        } catch (_: Exception) {
            null
        }
    }

    private fun allowanceSideExhausted(json: JSONObject): Boolean {
        val data = json.optJSONObject("data")
        return sideExhausted(data?.optJSONObject("upload"))
            || sideExhausted(data?.optJSONObject("analysis"))
            || sideExhausted(json.optJSONObject("upload"))
            || sideExhausted(json.optJSONObject("analysis"))
    }

    private fun sideExhausted(side: JSONObject?): Boolean {
        if (side == null || readBoolean(side, "unlimited") == true) return false
        val photosNow = readNumber(side, "photosNow") ?: return false
        return photosNow == 0.0
    }

    private fun resumeFromSide(container: JSONObject?): String? {
        if (container == null) return null
        return readString(container.optJSONObject("upload"), "resumeAt")
            ?: readString(container.optJSONObject("analysis"), "resumeAt")
    }

    private fun findBoolean(value: Any?, key: String): Boolean? {
        return when (value) {
            is JSONObject -> readBoolean(value, key) ?: findInObject(value, key, ::findBoolean)
            is JSONArray -> findInArray(value, key, ::findBoolean)
            else -> null
        }
    }

    private fun findString(value: Any?, key: String): String? {
        return when (value) {
            is JSONObject -> readString(value, key) ?: findInObject(value, key, ::findString)
            is JSONArray -> findInArray(value, key, ::findString)
            else -> null
        }
    }

    private fun <T> findInObject(obj: JSONObject, key: String, walk: (Any?, String) -> T?): T? {
        val names = obj.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (name == key) continue
            walk(obj.opt(name), key)?.let { return it }
        }
        return null
    }

    private fun <T> findInArray(array: JSONArray, key: String, walk: (Any?, String) -> T?): T? {
        for (index in 0 until array.length()) {
            walk(array.opt(index), key)?.let { return it }
        }
        return null
    }

    private fun readBoolean(obj: JSONObject?, key: String): Boolean? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        return when (val value = obj.get(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase(Locale.US)) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun readEnabledFlag(obj: JSONObject, key: String): Boolean? = readBoolean(obj, key)

    private fun readNumber(obj: JSONObject?, key: String): Double? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        return when (val value = obj.get(key)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun readLong(obj: JSONObject?, key: String): Long? {
        val number = readNumber(obj, key) ?: return null
        return number.toLong()
    }

    private fun readString(obj: JSONObject?, key: String): String? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        return obj.optString(key, "").takeIf { it.isNotEmpty() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
