package com.xiaodi.hello
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.util.TypedValue
class MainActivity : Activity() {
    private lateinit var miniPlayer: LinearLayout
    private lateinit var miniPlayerSongTitle: TextView
    private lateinit var miniPlayerPlayButton: Button
    private var isPlaying = false
    data class Song(val title: String, val artist: String, val color: Int)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val greetingTextView = TextView(this).apply {
            text = "晚上好"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(dpToPx(16f), dpToPx(16f), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(greetingTextView)
        val scrollView = ScrollView(this)
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val songs = listOf(
            Song("Song 1", "Artist A", Color.parseColor("#FF5733")),
            Song("Song 2", "Artist B", Color.parseColor("#33FF57")),
            Song("Song 3", "Artist C", Color.parseColor("#3357FF")),
            Song("Song 4", "Artist D", Color.parseColor("#F033FF")),
            Song("Song 5", "Artist E", Color.parseColor("#33FFF5")),
            Song("Song 6", "Artist F", Color.parseColor("#FFD700"))
        )
        for (song in songs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(72f)
                )
                setPadding(dpToPx(16f), 0, dpToPx(16f), 0)
                setBackgroundColor(Color.TRANSPARENT)
            }
            val colorView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(56f), dpToPx(56f))
                setBackgroundColor(song.color)
            }
            row.addView(colorView)
            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setPadding(dpToPx(16f), 0, 0, 0)
            }
            val titleTv = TextView(this).apply {
                text = song.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            textContainer.addView(titleTv)
            val artistTv = TextView(this).apply {
                text = song.artist
                setTextColor(Color.parseColor("#B3B3B3"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            textContainer.addView(artistTv)
            row.addView(textContainer)
            row.setOnClickListener {
                miniPlayerSongTitle.text = song.title
                miniPlayerPlayButton.text = "▶"
                isPlaying = false
                miniPlayer.visibility = View.VISIBLE
            }
            scrollContent.addView(row)
        }
        scrollView.addView(scrollContent)
        val scrollParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0
        )
        scrollParams.weight = 1f
        scrollView.layoutParams = scrollParams
        rootLayout.addView(scrollView)
        miniPlayer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#282828"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(64f)
            )
            visibility = View.GONE
        }
        val coverView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(48f), dpToPx(48f))
            setBackgroundColor(Color.parseColor("#B3B3B3"))
            setPadding(dpToPx(8f), dpToPx(8f), 0, 0)
        }
        miniPlayer.addView(coverView)
        miniPlayerSongTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                weight = 1f
                setMargins(12, 0, 0, 0)
            }
            gravity = Gravity.CENTER
        }
        miniPlayer.addView(miniPlayerSongTitle)
        miniPlayerPlayButton = Button(this).apply {
            text = "▶"
            setOnClickListener {
                isPlaying = !isPlaying
                text = if (isPlaying) "⏸" else "▶"
            }
        }
        miniPlayer.addView(miniPlayerPlayButton)
        rootLayout.addView(miniPlayer)
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#000000"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(56f)
            )
            val btn1 = Button(this).apply {
                text = "首页"
                setTextColor(Color.GRAY)
                setOnClickListener { Toast.makeText(this@MainActivity, "首页", Toast.LENGTH_SHORT).show() }
            }
            val btn2 = Button(this).apply {
                text = "搜索"
                setTextColor(Color.GRAY)
                setOnClickListener { Toast.makeText(this@MainActivity, "搜索", Toast.LENGTH_SHORT).show() }
            }
            val btn3 = Button(this).apply {
                text = "我的音乐库"
                setTextColor(Color.GRAY)
                setOnClickListener { Toast.makeText(this@MainActivity, "我的音乐库", Toast.LENGTH_SHORT).show() }
            }
            btn1.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            btn2.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            btn3.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(btn1)
            addView(btn2)
            addView(btn3)
        }
        rootLayout.addView(navBar)
        setContentView(rootLayout)
    }
    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()
}
