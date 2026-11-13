package com.mathieu.madtrainer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exporte le journal Bluetooth dans un fichier texte et ouvre le menu de partage Android.
 * Choisir « Drive » (compte Scalizer) dépose le fichier là où Claude peut le relire.
 * Le nom commence toujours par "mad-trainer-log-" pour être retrouvé facilement.
 */
object LogExporter {

    fun share(context: Context, report: String) {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        // On ne garde que les 10 derniers exports
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(Date())
        val file = File(dir, "mad-trainer-log-$stamp.txt")
        file.writeText(report)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Envoyer le journal").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
