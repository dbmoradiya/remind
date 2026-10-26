package com.remind.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import com.remind.app.data.PlanItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class WidgetRemoteViewsTest {
    @Test fun actual_remoteviews_inflates_draws_and_has_content() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val widget = RemindWidget()
        val data = RemindWidget.WidgetData("Mon, 26 Oct", listOf(
            PlanItemEntity(id=1, name="Gym", timeMinutes=390, sort=0) to false,
            PlanItemEntity(id=2, name="Fruit", timeMinutes=null, sort=1) to true
        ), true, 3, 30, "next ~8:00 PM", listOf("Insurance · tomorrow 9:00 AM"))
        for ((w,h) in listOf(320 to 360, 250 to 210, 320 to 300)) {
            val rv = GlanceRemoteViews().compose(ctx, DpSize(w.dp,h.dp)) { widget.Content(data) }.remoteViews
            val parent = FrameLayout(ctx)
            val view = rv.apply(ctx, parent)
            val width = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            view.measure(width,height); view.layout(0,0,w,h)
            val texts = mutableListOf<String>()
            fun scan(v: View) {
                if (v is TextView) texts.add(v.text.toString())
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) scan(v.getChildAt(i))
            }
            scan(view)
            assertTrue(texts.contains("Remind")); assertTrue(texts.contains("Gym"))
            assertTrue(texts.any { it.contains("Water") }); assertTrue(texts.any { it.contains("Insurance") })
            val bmp = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            val out = File("/tmp/widget-pixels-$w-$h.png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
            println("REMOTE_VIEWS_PIXELS ${out.absolutePath} TEXTS $texts")
        }
    }
}
