package com.xiaodi.hello

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.view.Gravity
import android.widget.LinearLayout.LayoutParams

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this)
        textView.text = "Hello XiaoDi"
        val params = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.CENTER
        textView.layoutParams = params

        setContentView(textView)
    }
}
