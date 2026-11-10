package com.videoblocker.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen "blocked" message. */
class BlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E3A5F"))
            setPadding((24 * d).toInt(), 0, (24 * d).toInt(), 0)
        }
        root.addView(TextView(this).apply {
            text = "⛔"
            textSize = 64f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "התוכן הזה חסום"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, (16 * d).toInt(), 0, (8 * d).toInt())
        })
        root.addView(TextView(this).apply {
            text = "צפייה בווידאו חסומה במכשיר זה."
            textSize = 16f
            setTextColor(Color.parseColor("#C8D6E8"))
            gravity = Gravity.CENTER
        })
        root.addView(Button(this).apply {
            text = "חזרה למסך הבית"
            setOnClickListener { goHome() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (32 * d).toInt() })
        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goHome()

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
