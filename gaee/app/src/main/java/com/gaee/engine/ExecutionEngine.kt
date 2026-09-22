package com.gaee.engine

import android.content.Context
import com.gaee.model.IntentResult
import com.gaee.model.ToolCall
import com.gaee.model.ToolResult
import com.gaee.service.GaeeAccessibilityService
import com.gaee.tools.AlarmTool
import com.gaee.tools.AnswerTool
import com.gaee.tools.AppLauncherTool
import com.gaee.tools.BaseTool
import com.gaee.tools.CallTool
import com.gaee.tools.CameraTool
import com.gaee.tools.ContactResolverTool
import com.gaee.tools.DeviceControlTool
import com.gaee.tools.MediaControllerTool
import com.gaee.tools.NotificationReaderTool
import com.gaee.tools.ReminderTool
import com.gaee.tools.SchedulerTool
import com.gaee.tools.ScreenReaderTool
import com.gaee.tools.SmsTool
import com.gaee.tools.TtsTool
import com.gaee.tools.UINavigator
import com.gaee.tools.VolumeTool
import com.gaee.tools.WeatherTool
import com.gaee.tools.WebFetcherTool
import com.gaee.tools.WifiTool
import kotlinx.coroutines.delay

class ExecutionEngine(private val context: Context) {

    companion object {
        // Cap on mid-execution LLM re-plans per task — bounds cost and prevents infinite loops
        private const val MAX_REPLANS = 3
        // Cap on mid-execution clarification questions per task (e.g. ContactResolverTool asking
        // "who is your daughter?") — bounds how many times the plan can pause to ask the user.
        private const val MAX_CLARIFICATIONS = 2
        // Data keys that steer the clarification loop itself. These must never leak into the
        // slot-fill context (fillSlotsFromContext) or the cloud re-plan prompt — they are engine
        // plumbing, not task data.
        private val CONTROL_KEYS = setOf("needsUserInput", "askUser", "retryArg", "learnAs")
    }

    val ttsTool = TtsTool(context)

    private val tools: Map<String, BaseTool> by lazy {
        mapOf(
            "AlarmTool" to AlarmTool(context),
            "ReminderTool" to ReminderTool(context),
            "CallTool" to CallTool(context),
            "SmsTool" to SmsTool(context),
            "AppLauncherTool" to AppLauncherTool(context),
            "VolumeTool" to VolumeTool(context),
            "WifiTool" to WifiTool(context),
            "WeatherTool" to WeatherTool(context),
            "CameraTool" to CameraTool(context),
            "TtsTool" to ttsTool,
            // Phase 2
            "UINavigator" to UINavigator(context),
            "WebFetcherTool" to WebFetcherTool(),
            "MediaControllerTool" to MediaControllerTool(context),
            "ContactResolverTool" to ContactResolverTool(context),
            "NotificationReaderTool" to NotificationReaderTool(context),
            "ScreenReaderTool" to ScreenReaderTool(context),
            "SchedulerTool" to SchedulerTool(context),
            "AnswerTool" to AnswerTool(context),
            "DeviceControlTool" to DeviceControlTool(context)
        )
    }

    /**
     * Runs a plan step by step. When a UINavigator UI action fails, it first retries once (for
     * transient timing), then — if [intent] and [replanner] are supplied — becomes a "brain with
     * eyes": it reads the REAL current screen and asks the LLM to adapt the remaining steps to
     * what is actually displayed. Bounded by [MAX_REPLANS] so it can never loop forever.
     */
    suspend fun execute(
        steps: List<ToolCall>,
        intent: IntentResult? = null,
        replanner: LlmPlanner? = null,
        confirm: (suspend (title: String, message: String) -> Boolean)? = null,
        ask: (suspend (question: String) -> String?)? = null
    ): List<ToolResult> {
        val results = mutableListOf<ToolResult>()
        // Accumulates data from previous steps so {slots} can be filled
        val context = mutableMapOf<String, String>()
        // Mutable so the re-planner can splice in a fresh set of remaining steps
        val queue = ArrayDeque(steps)
        var replanCount = 0
        var clarifyCount = 0

        android.util.Log.d("ExecutionEngine", "plan=${steps.map { it.toolName }}")

        while (queue.isNotEmpty()) {
            val step = queue.removeFirst()
            val filledStep = fillSlotsFromContext(step, context)
            // Arg VALUES may be phone numbers, SMS/WhatsApp message text, or (on a clarification
            // retry) the relationship pair being taught -- never log those. Key names only.
            android.util.Log.d("ExecutionEngine", "run ${filledStep.toolName} keys=${filledStep.args.keys}")

            val isUiAction = filledStep.toolName == "UINavigator" &&
                filledStep.args["action"] in setOf("tap", "type", "swipe")

            // 0) Before touching a PIN / password / OTP / payment screen, hand back to the user.
            if (isUiAction) {
                sensitiveHandoff()?.let { handoff ->
                    ttsTool.speak(handoff.speakAfter)
                    results.add(handoff)
                    return results
                }
            }

            // 0b) Irreversible taps (Delete/Remove/Unsubscribe…) need the user's explicit yes — twice.
            if (filledStep.toolName == "UINavigator" && filledStep.args["action"] == "tap" &&
                DestructiveActionGuard.isDestructive(filledStep.args["target"])) {
                val label = filledStep.args["target"] ?: "this"
                if (!confirmDestructive(label, confirm)) {
                    val msg = "Okay, I did not do that. It cannot be undone, so I stopped to keep you safe."
                    ttsTool.speak(msg)
                    results.add(ToolResult(false, msg, "destructive_action_declined"))
                    return results
                }
            }

            var result = runStep(filledStep)

            // 1) Transient timing — UI may not be ready yet. One quick retry of the same step.
            if (isUiAction && !result.success) {
                delay(600)
                result = runStep(filledStep)
            }

            // 2) Still failing — read the real screen and let the LLM adapt the rest of the plan.
            //    But never send a PIN/password/payment screen to the cloud — coach + stop instead.
            if (isUiAction && !result.success &&
                intent != null && replanner != null && replanCount < MAX_REPLANS) {
                sensitiveHandoff()?.let { handoff ->
                    ttsTool.speak(handoff.speakAfter)
                    results.add(handoff)
                    return results
                }
                val screenText = GaeeAccessibilityService.instance
                    ?.readScreen()?.data?.get("screenText").orEmpty()
                val newSteps = replanner.replan(intent, screenText, filledStep, context)
                if (newSteps.isNotEmpty()) {
                    replanCount++
                    ttsTool.speak("Let me try a different way.")
                    queue.clear()
                    queue.addAll(newSteps)
                    continue // follow the adapted plan; don't record the failed attempt
                }
            }

            // 3) Generic clarification loop — ANY tool may pause mid-step and ask the user a
            //    follow-up question via needsUserInput/askUser/retryArg/learnAs data keys
            //    (e.g. ContactResolverTool: "I don't know who your daughter is. What is their
            //    name?"). Not contact-specific — any future tool can opt in the same way. A step
            //    is asked about at most once; the whole plan is capped at MAX_CLARIFICATIONS.
            if (!result.success && result.data?.get("needsUserInput") == "true" &&
                ask != null && clarifyCount < MAX_CLARIFICATIONS) {
                val question = result.data?.get("askUser")
                val retryArg = result.data?.get("retryArg")
                val answer = if (!question.isNullOrBlank() && !retryArg.isNullOrBlank()) ask(question) else null
                if (!answer.isNullOrBlank() && retryArg != null) {
                    clarifyCount++
                    val learnAs = result.data?.get("learnAs")
                    // retryArg/learnAs are injected by the ENGINE, after runStep has stripped any
                    // control keys a plan step might otherwise smuggle in via its own args (see
                    // runStep) -- this is the one path allowed to set them.
                    val injectedArgs = mapOf(retryArg to answer) +
                        (learnAs?.let { mapOf("learnAs" to it) } ?: emptyMap())
                    result = runStep(filledStep, injectedArgs)
                    // Narrow, deliberate exception to the whitelist below: this speaks a
                    // "I will remember that..." confirmation that has NO tool of its own to say
                    // it. That is NOT true in general for the rest of a resumed plan, though —
                    // call_contact/send_sms/send_whatsapp's fallback plans (LlmPlanner) all end
                    // with a trailing TtsTool step ("Calling {resolvedName} now." etc.) that runs
                    // moments after this one. ttsTool.speak() is QUEUE_FLUSH, so a plain speak()
                    // here gets cut off mid-sentence by that trailing step ("I will re—Calling
                    // Priya now."). speakAndWait blocks this coroutine until the confirmation
                    // finishes before the next queued step (and its own speech) can run.
                    if (result.success && result.speakAfter.isNotBlank()) {
                        ttsTool.speakAndWait(result.speakAfter)
                    }
                }
                // else: no question/retryArg (malformed contract) or the user's answer came back
                // null/blank (mishear, timeout, or gave up) — fall through to the normal failure
                // path below (speak + break). Must never go silent.
            }

            results.add(result)

            // Merge any structured data this step produced into context — excluding the
            // clarification-loop's own control keys, which must never reach {slot} fill-in or
            // the cloud re-plan prompt.
            result.data?.let { context.putAll(it.filterKeys { key -> key !in CONTROL_KEYS }) }

            // After a successful UI action, let the next screen settle before the next step
            if (isUiAction && result.success) delay(400)

            if (!result.success) {
                // A tool that sets needsUserInput but leaves speakAfter blank would otherwise say
                // literally nothing here (TtsTool.speak("") is a no-op) -- fall back to a generic,
                // non-blank message so the user is never left in silence.
                val message = result.speakAfter.ifBlank {
                    "Something went wrong. Please tap the button and try again."
                }
                ttsTool.speak(message)
                break
            }

            if (result.success && step.toolName in
                setOf("WeatherTool", "AnswerTool", "AlarmTool", "ReminderTool", "DeviceControlTool")) {
                ttsTool.speak(result.speakAfter)
            }
        }

        return results
    }

    // Replace {key} placeholders in step args with values accumulated from prior step results
    private fun fillSlotsFromContext(step: ToolCall, ctx: Map<String, String>): ToolCall {
        if (ctx.isEmpty()) return step
        val filled = step.args.mapValues { (_, v) ->
            var value = v
            ctx.forEach { (key, data) -> value = value.replace("{$key}", data) }
            value
        }
        return step.copy(args = filled)
    }

    // Asks the user TWICE via popup before an irreversible tap. Any "no" (or no way to ask) rejects.
    private suspend fun confirmDestructive(
        label: String,
        confirm: (suspend (title: String, message: String) -> Boolean)?
    ): Boolean {
        if (confirm == null) return false // cannot ask → refuse the irreversible action
        val first = confirm(
            "Please confirm",
            "I am about to tap \"$label\". This cannot be undone. Do you want me to do it?"
        )
        if (!first) return false
        val second = confirm(
            "Are you absolutely sure?",
            "This will \"$label\" for good and cannot be reversed. Tap Yes only if you are certain."
        )
        return second
    }

    // Returns a coaching result if the current screen is a PIN/password/payment screen, else null.
    private fun sensitiveHandoff(): ToolResult? {
        val kind = GaeeAccessibilityService.instance?.assessSensitivity()
            ?: SensitiveScreenGuard.Kind.NONE
        if (kind == SensitiveScreenGuard.Kind.NONE) return null
        return ToolResult(false, SensitiveScreenGuard.guidance(kind), "sensitive_screen_handoff")
    }

    // [engineInjectedArgs] lets the clarification-retry path (above) add retryArg/learnAs AFTER
    // control keys are stripped from the plan-authored args below -- a plan step (LLM-generated
    // or cached) must never be able to set these itself. Concretely: an LLM-emitted step like
    // `ContactResolverTool {query:"Rakesh", learnAs:"my daughter"}` would otherwise rewrite the
    // relationship map permanently with no clarification round-trip at all. BaseTool's interface
    // is unchanged -- this parameter is purely an ExecutionEngine-internal plumbing detail.
    private suspend fun runStep(step: ToolCall, engineInjectedArgs: Map<String, String> = emptyMap()): ToolResult {
        val sanitizedArgs = (step.args - CONTROL_KEYS) + engineInjectedArgs
        return try {
            val tool = tools[step.toolName]
                ?: return ToolResult(false, "Something went wrong. Please tap the button and try again.")
            tool.execute(sanitizedArgs)
        } catch (e: Exception) {
            ToolResult(false, "Something went wrong. Please tap the button and try again.", e.message)
        }
    }

    fun shutdown() {
        ttsTool.shutdown()
    }
}
