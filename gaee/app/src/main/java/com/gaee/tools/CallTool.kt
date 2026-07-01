package com.gaee.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.gaee.model.ToolResult

class CallTool(private val context: Context) : BaseTool {
    override val name = "CallTool"

    override suspend fun execute(args: Map<String, String>): ToolResult {
        val name = args["resolvedName"] ?: args["name"] ?: return ToolResult(false, "I need a name to call.")

        return try {
            // Use pre-resolved number from ContactResolverTool if available
            val number = args["phone"]?.takeIf { it.isNotBlank() }
                ?: resolveContactNumber(name)
                ?: return ToolResult(false, "I could not find $name in your contacts. Do you want to try a different name?")

            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            ToolResult(true, "Calling $name now.")
        } catch (e: Exception) {
            ToolResult(false, "I could not make the call. Please try again or dial manually.", e.message)
        }
    }

    private fun resolveContactNumber(name: String): String? {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
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
