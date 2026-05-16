package com.hereliesaz.doxray.ui.live

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AnchorPipOverlay(match: MatchEvent?, modifier: Modifier = Modifier) {
    if (match == null) return
    val bitmap = remember(match.firedAtMs) {
        match.anchorImageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    Column(modifier.padding(12.dp)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = match.identityName,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(4.dp)).background(Color.DarkGray))
        }
        Box(modifier = Modifier.background(Color.Black.copy(alpha = 0.6f))) {
            Text(
                text = match.identityName,
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
