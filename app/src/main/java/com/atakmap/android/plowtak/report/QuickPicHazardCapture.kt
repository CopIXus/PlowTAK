package com.atakmap.android.plowtak.report

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.atakmap.android.ipc.AtakBroadcast
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter
import com.atakmap.android.image.quickpic.QuickPicReceiver
import com.atakmap.android.plowtak.model.HazardType
import com.atakmap.android.util.AttachmentManager
import com.atakmap.coremap.filesystem.FileSystemUtils
import java.io.File

/**
 * Captures hazard photos through ATAK's built-in QuickPic tool, then
 * attaches the resulting image to a PlowTAK hazard marker.
 *
 * Flow:
 *  1. [request] stores the pending hazard type and broadcasts
 *     [QuickPicReceiver.QUICK_PIC].
 *  2. ATAK camera/gallery runs; on success it fires
 *     [QuickPicReceiver.QUICK_PIC_CAPTURED] with `uid` + `path` extras.
 *  3. We copy the image into [AttachmentManager]'s folder for the new
 *     hazard uid and invoke [onCaptured] so the controller can publish.
 */
class QuickPicHazardCapture(
    private val onCaptured: (HazardType, String /* photo file name */) -> Unit
) {

    @Volatile
    private var pendingType: HazardType? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != QuickPicReceiver.QUICK_PIC_CAPTURED) return
            val type = pendingType ?: return
            pendingType = null
            val path = intent.getStringExtra("path")
            if (path.isNullOrEmpty()) {
                Log.w(TAG, "QuickPic captured with empty path")
                onCaptured(type, "")
                return
            }
            val src = File(FileSystemUtils.sanitizeWithSpacesAndSlashes(path))
            if (!src.isFile) {
                Log.w(TAG, "QuickPic file missing: $path")
                onCaptured(type, "")
                return
            }
            onCaptured(type, src.name)
            // Attachment copy happens after the hazard uid is known — see
            // [attachToHazard].
            lastCaptureFile = src
        }
    }

    @Volatile
    private var lastCaptureFile: File? = null

    fun start() {
        val filter = DocumentedIntentFilter()
        filter.addAction(
            QuickPicReceiver.QUICK_PIC_CAPTURED,
            "QuickPic finished; attach photo to PlowTAK hazard"
        )
        AtakBroadcast.getInstance().registerReceiver(receiver, filter)
    }

    fun stop() {
        try {
            AtakBroadcast.getInstance().unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "unregister QuickPic receiver failed", e)
        }
        pendingType = null
        lastCaptureFile = null
    }

    /** Launch ATAK QuickPic; the next capture completes the pending hazard. */
    fun request(type: HazardType) {
        pendingType = type
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                Intent(QuickPicReceiver.QUICK_PIC)
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to launch QuickPic", e)
            pendingType = null
        }
    }

    /**
     * Copy the most recent QuickPic file into the attachment folder for
     * [hazardUid] so TAK attachment sync carries it with the marker.
     */
    fun attachToHazard(hazardUid: String): Boolean {
        val src = lastCaptureFile ?: return false
        lastCaptureFile = null
        return try {
            val folder = File(AttachmentManager.getFolderPath(hazardUid))
            if (!folder.exists() && !folder.mkdirs()) {
                Log.w(TAG, "cannot create attachment folder $folder")
                return false
            }
            val dest = File(folder, src.name)
            FileSystemUtils.copyFile(src, dest)
            AttachmentManager.notifyAttachmentChange(hazardUid)
            true
        } catch (e: Exception) {
            Log.w(TAG, "attach QuickPic to $hazardUid failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "PlowTakQuickPic"
    }
}
