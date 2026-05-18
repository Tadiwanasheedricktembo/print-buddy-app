package com.tadiwaprintbuddy.app

import android.content.Context
import android.os.Environment
import java.io.File

object StorageUtils {
    fun getPicturesDir(context: Context): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
    }

    fun getFullImagePath(context: Context, filenameOrPath: String): String {
        if (filenameOrPath.isEmpty()) return ""
        // If it's an absolute path, use it as is (backward compatibility)
        if (filenameOrPath.startsWith("/")) return filenameOrPath
        // Otherwise treat as filename in Pictures dir
        return File(getPicturesDir(context), filenameOrPath).absolutePath
    }

    fun getRelativeFilename(path: String): String {
        return if (path.contains("/")) path.substringAfterLast("/") else path
    }
}
