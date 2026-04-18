package org.stypox.dicio.llm

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DicioTools @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolSet {

    @Tool(description = "Sets a timer for a given number of minutes with an optional label. Use this when the user asks to start, set, or remind them via a timer.")
    fun setTimer(
        @ToolParam(description = "Duration in minutes (integer).") minutes: Int,
        @ToolParam(description = "Optional short label for the timer.") label: String = "",
    ): Map<String, String> {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf(
                "result" to "success",
                "minutes" to minutes.toString(),
                "label" to label,
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity can handle ACTION_SET_TIMER", e)
            mapOf("result" to "error", "reason" to (e.message ?: "no timer app"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start timer", e)
            mapOf("result" to "error", "reason" to (e.message ?: e.javaClass.simpleName))
        }
    }

    @Tool(description = "Searches the web for the given query using the user's default search provider.")
    fun searchWeb(
        @ToolParam(description = "The search query to run on the web.") query: String,
    ): Map<String, String> {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf("result" to "success", "query" to query)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity can handle ACTION_WEB_SEARCH", e)
            mapOf("result" to "error", "reason" to (e.message ?: "no search app"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to run web search", e)
            mapOf("result" to "error", "reason" to (e.message ?: e.javaClass.simpleName))
        }
    }

    @Tool(description = "Opens a URL in the user's default browser.")
    fun openUrl(
        @ToolParam(description = "The full URL or bare domain to open.") url: String,
    ): Map<String, String> {
        return try {
            val normalized = if (url.contains("://")) url else "https://$url"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf("result" to "success", "url" to normalized)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity can handle ACTION_VIEW for URL", e)
            mapOf("result" to "error", "reason" to (e.message ?: "no browser app"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open URL", e)
            mapOf("result" to "error", "reason" to (e.message ?: e.javaClass.simpleName))
        }
    }

    @Tool(description = "Sets an alarm for a specific time of day (24-hour clock) with an optional label.")
    fun setAlarm(
        @ToolParam(description = "Hour of the day, 0-23.") hour: Int,
        @ToolParam(description = "Minute of the hour, 0-59.") minute: Int,
        @ToolParam(description = "Optional label for the alarm.") label: String = "",
    ): Map<String, String> {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf(
                "result" to "success",
                "hour" to hour.toString(),
                "minute" to minute.toString(),
                "label" to label,
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity can handle ACTION_SET_ALARM", e)
            mapOf("result" to "error", "reason" to (e.message ?: "no alarm app"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set alarm", e)
            mapOf("result" to "error", "reason" to (e.message ?: e.javaClass.simpleName))
        }
    }

    @Tool(description = "Opens the phone dialer pre-filled with the given number, but does not place the call - the user must press call.")
    fun dialPhone(
        @ToolParam(description = "The phone number to dial, digits and optional leading + for international.") number: String,
    ): Map<String, String> {
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf("result" to "success", "number" to number)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity can handle ACTION_DIAL", e)
            mapOf("result" to "error", "reason" to (e.message ?: "no dialer app"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dial number", e)
            mapOf("result" to "error", "reason" to (e.message ?: e.javaClass.simpleName))
        }
    }

    @Tool(description = "Opens the calendar app pre-filled with a new event. The user must confirm saving.")
    fun createCalendarEvent(
        @ToolParam(description = "The event title.") title: String,
        @ToolParam(description = "Start datetime in ISO 8601 local format (YYYY-MM-DDTHH:MM:SS).") startIso: String,
        @ToolParam(description = "Duration of the event in minutes.") durationMinutes: Int,
    ): Map<String, String> {
        val startMs = try {
            LocalDateTime.parse(startIso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            Log.w(TAG, "Invalid datetime for calendar event: $startIso", e)
            return mapOf("result" to "error", "reason" to "invalid datetime format, expected YYYY-MM-DDTHH:MM:SS")
        }
        return try {
            val endMs = startMs + durationMinutes * 60_000L
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            mapOf(
                "result" to "success",
                "title" to title,
                "start" to startIso,
                "durationMinutes" to durationMinutes.toString(),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity can handle ACTION_INSERT for calendar", e)
            mapOf("result" to "error", "reason" to (e.message ?: "no calendar app"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create calendar event", e)
            mapOf("result" to "error", "reason" to (e.message ?: e.javaClass.simpleName))
        }
    }

    @Tool(description = "Opens an installed app by its display name (fuzzy match).")
    fun openApp(
        @ToolParam(description = "Partial or full name of the app as shown in the launcher (e.g. 'signal', 'whatsapp', 'settings').") appName: String,
    ): Map<String, String> {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(mainIntent, 0)
            val matches = resolved.filter {
                it.loadLabel(pm).toString().contains(appName, ignoreCase = true)
            }
            when {
                matches.isEmpty() ->
                    mapOf("result" to "error", "reason" to "no app matching '$appName' found")
                matches.size > 1 -> mapOf(
                    "result" to "error",
                    "reason" to "ambiguous app name, matched ${matches.size} apps",
                    "candidates" to matches.joinToString(", ") { it.loadLabel(pm).toString() },
                )
                else -> {
                    val match = matches.first()
                    val label = match.loadLabel(pm).toString()
                    val pkg = match.activityInfo.packageName
                    val launch = pm.getLaunchIntentForPackage(pkg)
                        ?: return mapOf("result" to "error", "reason" to "no launch intent for $pkg")
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    mapOf("result" to "success", "app" to label, "package" to pkg)
                }
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Failed to launch app '$appName'", e)
            mapOf("result" to "error", "reason" to (e.message ?: "activity not found"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open app '$appName'", e)
            mapOf("result" to "error", "reason" to (e.message ?: e.javaClass.simpleName))
        }
    }

    companion object {
        private const val TAG = "DicioTools"
    }
}
