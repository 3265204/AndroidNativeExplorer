package com.ane.filemanager.ui.onboarding

import android.content.Context
import com.ane.filemanager.R
import java.io.File

/** Disposable filesystem used by onboarding; operations here are real but never touch user files. */
internal data class OnboardingWorkspace(
    val root: File,
    val sample: File,
    val moveTarget: File,
    val copyTarget: File,
    val rootLabel: String,
    val moveTargetLabel: String,
    val copyTargetLabel: String
) {
    val movedSample: File get() = File(moveTarget, sample.name)
    val copiedSample: File get() = File(copyTarget, sample.name)

    companion object {
        fun prepare(context: Context): OnboardingWorkspace {
            val root = directory(context)
            root.deleteRecursively()
            root.mkdirs()
            val sample = File(root, context.getString(R.string.tutorial_workspace_sample))
            createSample(context, sample)
            val moveTarget = File(root, MOVE_TARGET).apply(File::mkdirs)
            val copyTarget = File(root, COPY_TARGET).apply(File::mkdirs)
            return OnboardingWorkspace(
                root = root,
                sample = sample,
                moveTarget = moveTarget,
                copyTarget = copyTarget,
                rootLabel = context.getString(R.string.tutorial_workspace_root),
                moveTargetLabel = context.getString(R.string.tutorial_workspace_archive),
                copyTargetLabel = context.getString(R.string.tutorial_workspace_copies)
            )
        }

        fun clear(context: Context) {
            directory(context).deleteRecursively()
        }

        private fun directory(context: Context) = File(context.cacheDir, WORKSPACE)
        private fun createSample(context: Context, sample: File) {
            sample.mkdirs()
            File(sample, context.getString(R.string.tutorial_workspace_note)).writeText(
                context.getString(R.string.tutorial_workspace_note_content)
            )
        }
        private const val WORKSPACE = "ane-onboarding-workspace"
        private const val MOVE_TARGET = "archive"
        private const val COPY_TARGET = "copies"
    }
}
