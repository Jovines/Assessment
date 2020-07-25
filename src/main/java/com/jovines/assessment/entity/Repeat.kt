package com.jovines.assessment.entity

import java.io.Serializable

data class Repeat(
    val similarityRate: Double,
    val networkText:String,
    val localText:String,
    val url: String
): Serializable