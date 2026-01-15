package com.emrelic.kutusay.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object ShareUtils {

    fun shareViaWhatsApp(
        context: Context,
        text: String,
        imageUris: List<Uri> = emptyList()
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (imageUris.isNotEmpty()) "image/*" else "text/plain"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_TEXT, text)

                if (imageUris.isNotEmpty()) {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(imageUris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(Intent.createChooser(intent, "WhatsApp ile Paylas"))
                true
            } else {
                // WhatsApp yuklu degil, normal paylasim yap
                shareGeneric(context, text, imageUris)
            }
        } catch (e: Exception) {
            shareGeneric(context, text, imageUris)
        }
    }

    fun shareGeneric(
        context: Context,
        text: String,
        imageUris: List<Uri> = emptyList()
    ): Boolean {
        return try {
            val intent = if (imageUris.size > 1) {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_TEXT, text)
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(imageUris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else if (imageUris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_STREAM, imageUris.first())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
            }

            context.startActivity(Intent.createChooser(intent, "Paylas"))
            true
        } catch (e: Exception) {
            false
        }
    }
}
