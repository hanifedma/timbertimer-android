package com.example.timbertimer

import com.example.timbertimer.widget.ProjectTimeWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How big the time-by-project ring gets, and when it gives up and leaves.
 *
 * The sizing is the one part of that widget with a decision in it, and it is
 * also the part no home screen will tell you is wrong — a ring that is slightly
 * too greedy just quietly eats the project names next to it. So the rules are
 * pinned here: it grows with the widget, it never takes more than its share,
 * and it disappears rather than shrink into decoration.
 */
class ProjectChartSizeTest {

    /** Roughly what three cells and three rows come to on a phone. */
    private val defaultWidth = 250
    private val defaultHeight = 190

    @Test
    fun `ring grows with the widget`() {
        val small = ProjectTimeWidget.chartSizeDp(220, defaultHeight)!!
        val medium = ProjectTimeWidget.chartSizeDp(300, defaultHeight)!!
        val large = ProjectTimeWidget.chartSizeDp(380, defaultHeight)!!

        assertTrue("$small should be smaller than $medium", small < medium)
        assertTrue("$medium should be smaller than $large", medium < large)
    }

    @Test
    fun `the list always keeps the larger half`() {
        listOf(180, 220, 250, 300, 380, 520).forEach { width ->
            val ring = ProjectTimeWidget.chartSizeDp(width, defaultHeight) ?: return@forEach
            val row = width - 34 // root padding, plus the ring's own margins
            assertTrue(
                "at ${width}dp the ring took $ring of $row",
                ring < row - ring,
            )
        }
    }

    @Test
    fun `a very wide widget stops enlarging the ring`() {
        val wide = ProjectTimeWidget.chartSizeDp(900, 600)
        val wider = ProjectTimeWidget.chartSizeDp(1600, 600)

        assertEquals(wide, wider)
        assertTrue("$wide is past anything a home screen needs", wide!! <= 132)
    }

    @Test
    fun `a short widget hides the ring rather than squash the list`() {
        // 70dp is the declared minResizeHeight: header, divider and one row.
        assertNull(ProjectTimeWidget.chartSizeDp(defaultWidth, 70))
    }

    @Test
    fun `a narrow widget hides the ring rather than crowd the names`() {
        // 110dp is the declared minResizeWidth.
        assertNull(ProjectTimeWidget.chartSizeDp(110, defaultHeight))
    }

    @Test
    fun `the size a widget is placed at still gets a ring`() {
        // The declared minimum from project_time_widget_info.xml, which is also
        // the fallback when a launcher will not say how big the widget is. If
        // this ever returns null the ring vanishes on every quiet launcher.
        assertTrue(ProjectTimeWidget.chartSizeDp(180, 110) != null)
    }

    @Test
    fun `height caps the ring on a wide but shallow widget`() {
        val tall = ProjectTimeWidget.chartSizeDp(400, 300)!!
        val shallow = ProjectTimeWidget.chartSizeDp(400, 130)!!

        assertTrue("$shallow should be held back by the height", shallow < tall)
    }

    @Test
    fun `nonsense from the host does not produce a nonsense ring`() {
        // Some hosts have been known to report zero or negative sizes while a
        // widget is being placed; the answer is no ring, not a bitmap with a
        // negative diameter.
        assertNull(ProjectTimeWidget.chartSizeDp(0, 0))
        assertNull(ProjectTimeWidget.chartSizeDp(-100, -100))
    }
}
