package nl.tippie.cloudhub.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The launcher icon's own mark, drawn rather than imported.
 *
 * The geometry is the unit box from tools/make-icons.php: three bumps over a
 * slab, with the upload arrow knocked out of the cloud rather than drawn
 * beside it. A stock cloud icon here would put a different mark on the sign-in
 * screen than the one on the home screen.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = LocalReduceMotion.current

    val appear = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) {
            appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 320f))
        }
    }

    Canvas(
        modifier.graphicsLayer {
            scaleX = appear.value
            scaleY = appear.value
            alpha = appear.value.coerceIn(0f, 1f)
        }
    ) {
        val s = size.minDimension
        fun p(x: Float, y: Float) = Offset(x * s / 100f, y * s / 100f)
        fun d(n: Float) = n * s / 100f

        val cloud = Path().apply {
            addOval(Rect(p(13f, 33f), Size(d(34f), d(34f))))
            addOval(Rect(p(29f, 17f), Size(d(46f), d(46f))))
            addOval(Rect(p(59f, 37f), Size(d(30f), d(30f))))
            addRect(Rect(p(30f, 50f), p(74f, 67f)))
        }

        val arrow = Path().apply {
            moveTo(p(52f, 26f).x, p(52f, 26f).y)
            lineTo(p(67f, 46f).x, p(67f, 46f).y)
            lineTo(p(37f, 46f).x, p(37f, 46f).y)
            close()
            addRect(Rect(p(46f, 44f), p(58f, 72f)))
        }

        /*
         * The arrow is subtracted from the cloud as a shape.
         *
         * Adding both to one path and setting EvenOdd knocks out every region
         * covered an even number of times -- which includes each place two of
         * the cloud's bumps overlap, and where the slab crosses them. The mark
         * came out as a lattice rather than a cloud. Difference removes the
         * arrow and nothing else; the cloud keeps its default non-zero fill, so
         * its bumps merge the way they are meant to.
         */
        val mark = Path.combine(PathOperation.Difference, cloud, arrow)
        drawPath(mark, brush = Brush.linearGradient(listOf(scheme.primary, scheme.secondary)))
    }
}
