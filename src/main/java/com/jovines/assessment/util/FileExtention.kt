package com.jovines.assessment.util

import java.io.File

fun File.createParentFolder(): File {
    if (!parentFile.exists()) {
        parentFile.mkdirs()
    }
    return this
}