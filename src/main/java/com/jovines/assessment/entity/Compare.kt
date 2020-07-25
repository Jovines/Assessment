package com.jovines.assessment.entity

import java.io.Serializable

data class Compare(
    val fileName: String,
    val isRepeat: Boolean,
    val repeatList: List<Repeat>
):Serializable