package com.ane.filemanager.operation

import android.content.Context
import com.ane.filemanager.R

/** Presentation mapping from domain failures to localized Android resources. */
internal fun Context.fileProblemMessage(problem: FileProblem): String {
    val subject = problem.subject.orEmpty()
    return when (problem.failure) {
        FileFailure.INVALID_NAME -> getString(R.string.error_invalid_name)
        FileFailure.NAME_EXISTS -> getString(R.string.error_name_exists)
        FileFailure.CREATE_FAILED -> getString(R.string.error_create_failed)
        FileFailure.RENAME_FAILED -> getString(R.string.error_rename_failed)
        FileFailure.DELETE_FAILED -> getString(R.string.error_delete_failed, subject)
        FileFailure.SOURCE_MISSING -> getString(R.string.error_source_missing)
        FileFailure.CREATE_DIRECTORY -> getString(R.string.error_create_directory, subject)
        FileFailure.MOVE_INTO_SELF -> getString(R.string.error_move_into_self)
        FileFailure.COPY_INTO_SELF -> getString(R.string.error_copy_into_self)
        FileFailure.COPY_FAILED -> getString(R.string.error_copy_failed, subject)
        FileFailure.MOVE_FAILED -> getString(R.string.error_move_failed, subject)
        FileFailure.PARTIAL_MOVE -> getString(R.string.error_partial_move, subject)
        FileFailure.WRITE_FAILED -> getString(R.string.error_unknown)
        FileFailure.HISTORY_NODE_MISSING -> getString(R.string.error_unknown)
        FileFailure.UNKNOWN -> getString(R.string.error_unknown)
    }
}
