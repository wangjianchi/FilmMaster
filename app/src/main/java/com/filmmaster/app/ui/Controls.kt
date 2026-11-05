package com.filmmaster.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一行标签 + 滑杆 + 读数，Pro 面板复用。 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit = {},
    formatter: (Float) -> String = { "${(it * 100).toInt()}%" }
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xCCFFFFFF), fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinish,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        )
        Text(formatter(value), color = Color(0xCCFFFFFF), fontSize = 11.sp)
    }
}
