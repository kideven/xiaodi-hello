package com.xiaodi.hello

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var progressBar: View
    private lateinit var playerTitle: TextView
    private lateinit var playerCover: View
    private lateinit var playBtn: TextView
    private var selectedNavIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Helper for dp → px
        fun dp(px: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            px.toFloat(),
            resources.displayMetrics
        ).toInt()

        // Colors
        val bgMain = 0xFF121212.toInt()
        val cardBg = 0xFF1A1A1A.toInt()
        val white = 0xFFFFFFFF.toInt()
        val grey = 0xFFB3B3B3.toInt()
        val green = 0xFF1DB954.toInt()

        // Gradients for quick cards (6 items)
        val quickGradients = listOf(
            intArrayOf(0xFF4776E6.toInt(), 0xFF79BD9A.toInt()),
            intArrayOf(0xFFFF7E5F.toInt(), 0xFFFFB567.toInt()),
            intArrayOf(0xFF667EEA.toInt(), 0xFF764BA2.toInt()),
            intArrayOf(0xFFFC5C63.toInt(), 0xFFFED85D.toInt()),
            intArrayOf(0xFF36D1DC.toInt(), 0xFF5B86E5.toInt()),
            intArrayOf(0xFF00C9FF.toInt(), 0xFF2C5364.toInt())
        )
        // Gradients for recent list (6 items)
        val recentGradients = listOf(
            intArrayOf(0xFF8E2DE2.toInt(), 0xFF4A00E0.toInt()),
            intArrayOf(0xFFFC466B.toInt(), 0xFF3F5EFB.toInt()),
            intArrayOf(0xFF5CB85C.toInt(), 0xFF79E2A0.toInt()),
            intArrayOf(0xFF4E54C8.toInt(), 0xFF8F94FB.toInt()),
            intArrayOf(0xFFFC5C63.toInt(), 0xFFFDCC5D.toInt()),
            intArrayOf(0xFF5D4037.toInt(), 0xFF8D6E63.toInt())
        )

        // Root vertical LinearLayout
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMain)
            setPadding(dp(16), 0, dp(16), 0)
        }

        // 1. Greeting section
        val greeting = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(16))
        }
        val tvHello = TextView(this).apply {
            text = "晚上好"
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val tvSub = TextView(this).apply {
            text = "为你推荐"
            setTextColor(grey)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        greeting.addView(tvHello)
        greeting.addView(tvSub)
        root.addView(greeting)

        // 2. Horizontal ScrollView with quick cards
        val scroll = HorizontalScrollView(this@MainActivity).apply {
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, dp(12), 0)
            }
            // Populate 6 cards
            for (i in 0 until 6) {
                val card = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        dp(140), LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(dp(8), 0, dp(8), 0)
                }
                // Cover
                val cover = View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        dp(140), dp(140)
                    )
                    val gd = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        quickGradients[i]
                    )
                    gd.cornerRadius = dp(12).toFloat()
                    setBackground(gd)
                }
                // Title
                val title = TextView(this@MainActivity).apply {
                    text = "歌曲 $i"
                    setTextColor(white)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(dp(4), dp(8), dp(4), 0)
                    gravity = Gravity.CENTER
                }
                // Artist
                val artist = TextView(this@MainActivity).apply {
                    text = "艺术家 $i"
                    setTextColor(grey)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(4), 0, dp(4), dp(8))
                    gravity = Gravity.CENTER
                }
                card.addView(cover)
                card.addView(title)
                card.addView(artist)
                container.addView(card)
            }
            addView(container)
        }
        root.addView(scroll)

        // 3. Recent list
        val recentTitle = TextView(this).apply {
            text = "最近播放"
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        root.addView(recentTitle)

        val recentList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for (i in 0 until 6) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(72)
                )
                setPadding(dp(16), dp(8), dp(16), dp(8))
                gravity = Gravity.CENTER_VERTICAL
            }
            // left cover
            val cover = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                val gd = GradientDrawable(
                    GradientDrawable.Orientation.BR_TL, recentGradients[i]
                )
                gd.cornerRadius = dp(8).toFloat()
                setBackground(gd)
            }
            row.addView(cover)
            // middle info
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setPadding(dp(12), 0, 0, 0)
            }
            val tTitle = TextView(this).apply {
                text = "歌曲 $i"
                setTextColor(white)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            val tArtist = TextView(this).apply {
                text = "艺术家 $i"
                setTextColor(grey)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            info.addView(tTitle)
            info.addView(tArtist)
            row.addView(info)
            // right menu button
            val more = Button(this).apply {
                text = "···"
                setTextColor(grey)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(more)
            row.setOnClickListener {
                // Update bottom player
                playerTitle.text = "歌曲 $i"
                // could update cover but keep simple
            }
            recentList.addView(row)
        }
        root.addView(recentList)

        // 4. Mini player at bottom
        val playerBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF282828.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(72)
            )
        }
        progressBar = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(2)
            )
            setBackgroundColor(green)
        }
        playerBar.addView(progressBar)

        val playerContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        playerCover = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            val gd = GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                intArrayOf(0xFF555555.toInt(), 0xFF888888.toInt())
            )
            gd.cornerRadius = dp(24).toFloat()
            setBackground(gd)
        }
        playerContent.addView(playerCover)

        playerTitle = TextView(this).apply {
            text = "未播放"
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            setPadding(dp(12), 0, dp(12), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        playerContent.addView(playerTitle)

        playBtn = TextView(this).apply {
            text = "▶"
            setTextColor(white)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setGravity(Gravity.CENTER)
            setBackgroundColor(green)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener {
                if (playBtn.text == "▶") {
                    playBtn.text = "⏸"
                } else {
                    playBtn.text = "▶"
                }
            }
        }
        playerContent.addView(playBtn)

        playerBar.addView(playerContent)
        root.addView(playerBar)

        // 5. Bottom navigation
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0A0A0A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
            gravity = Gravity.BOTTOM
        }
        val titles = listOf("首页", "搜索", "音乐库")
        for (idx in titles.indices) {
            val btn = Button(this).apply {
                text = titles[idx]
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(if (idx == selectedNavIndex) green else grey)
                setOnClickListener {
                    selectedNavIndex = idx
                    // Update colors of all nav buttons
                    for (i in 0 until navBar.childCount) {
                        val b = navBar.getChildAt(i) as Button
                        b.setTextColor(if (i == idx) green else grey)
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "点击了${titles[idx]}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            val lp = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            btn.layoutParams = lp
            navBar.addView(btn)
        }
        root.addView(navBar)

        setContentView(root)
    }
}