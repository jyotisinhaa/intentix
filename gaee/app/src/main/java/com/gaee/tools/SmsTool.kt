package com.gaee.tools

import android.content.Context
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.gaee.model.ToolResult

class SmsTool(private val context: Context) : BaseTool {
    override val name = "SmsTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val name = args["resolvedName"] ?: args["name"] ?: return ToolResult(false, "I need a name to send a message to.")
        val message = args["message"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, "What would you like to say to $name? Please try again and include your message.")

        return try {
            // Use pre-resolved number from ContactResolverTool if available
            val number = args["phone"]?.takeIf { it.isNotBlank() }
                ?: resolveContactNumber(name)
                ?: return ToolResult(false, "I could not find $name in your contacts. Do you want to try a different name?")

            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(number, null, message, null, null)

            ToolResult(true, "Message sent to $name.")
        } catch (e: Exception) {
            ToolResult(false, "I could not send the message. Please try again.", e.message)
        }
    }

    private fun resolveContactNumber(name: String): String? {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null

        cursor.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val lowerName = name.lowercase()

            while (it.moveToNext()) {
                val contactName = it.getString(nameIndex) ?: continue
                if (contactName.lowercase().contains(lowerName)) {
                    return it.getString(numberIndex)
                }
            }
        }
        return null
    }
}
