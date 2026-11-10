package com.videoblocker.app

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Screens:
 *  - first run: create a password
 *  - every open: enter the password
 *  - main: blocking on/off (password required to turn off), lists, protections
 */
class MainActivity : Activity() {

    private var unlocked = false
    private var failCount = 0
    private var lockedUntil = 0L

    private val navy = Color.parseColor("#1E3A5F")
    private val green = Color.parseColor("#2E7D4F")
    private val red = Color.parseColor("#C0392B")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide content from screenshots and the recent-apps preview
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) unlocked = false   // locks again when leaving
    }

    private fun render() = when {
        !Prefs.hasPin(this) -> showSetPin(firstTime = true)
        !unlocked -> showLock()
        else -> showMain()
    }

    // ================= Screens =================

    private fun showSetPin(firstTime: Boolean) {
        val col = page()
        col.addView(title(if (firstTime) "ברוך הבא לחוסם הווידאו" else "שינוי סיסמה"))
        col.addView(text("בחר סיסמה (לפחות 4 תווים). היא תידרש לפתיחת האפליקציה ולכיבוי החסימה."))
        val p1 = passField("סיסמה חדשה")
        val p2 = passField("אימות סיסמה")
        col.addView(p1); col.addView(p2)
        col.addView(button("שמור סיסמה", navy) {
            val a = p1.text.toString(); val b = p2.text.toString()
            when {
                a.length < 4 -> toast("הסיסמה קצרה מדי")
                a != b -> toast("הסיסמאות אינן תואמות")
                else -> {
                    Prefs.setPin(this, a)
                    unlocked = true
                    toast("הסיסמה נשמרה")
                    render()
                }
            }
        })
        if (!firstTime) col.addView(button("ביטול", Color.GRAY) { render() })
    }

    private fun showLock() {
        val col = page()
        col.addView(title("🔒 חוסם וידאו"))
        col.addView(text("הזן סיסמה כדי לפתוח"))
        val p = passField("סיסמה")
        col.addView(p)
        col.addView(button("פתח", navy) {
            val now = SystemClock.elapsedRealtime()
            if (now < lockedUntil) {
                toast("יותר מדי ניסיונות. נסה שוב בעוד ${(lockedUntil - now) / 1000} שניות")
                return@button
            }
            if (Prefs.checkPin(this, p.text.toString())) {
                failCount = 0
                unlocked = true
                render()
            } else {
                failCount++
                p.setText("")
                if (failCount >= 5) {
                    lockedUntil = now + 60_000
                    failCount = 0
                    toast("סיסמה שגויה. הכניסה נחסמה לדקה")
                } else toast("סיסמה שגויה")
            }
        })
    }

    private fun showMain() {
        val col = page()
        col.addView(title("חוסם וידאו"))

        // --- Accessibility service status ---
        val svcOn = isServiceOn()
        col.addView(card(
            if (svcOn) "✅ שירות החסימה פועל" else "⚠️ שירות החסימה כבוי – יש להפעיל אותו",
            if (svcOn) green else red
        ))
        if (!svcOn) {
            col.addView(text("בהגדרות: נגישות ← אפליקציות מותקנות / שירותים ← \"חוסם וידאו\" ← הפעל."))
            col.addView(button("פתח הגדרות נגישות", navy) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }

        // --- Blocking on/off ---
        val on = Prefs.isEnabled(this)
        col.addView(section("חסימה"))
        col.addView(card(if (on) "החסימה פעילה" else "החסימה כבויה", if (on) green else red))
        col.addView(
            if (on) button("כבה חסימה (דורש סיסמה)", red) {
                askPin { Prefs.setEnabled(this, false); DeviceOwner.apply(this); render() }
            } else button("הפעל חסימה", green) {
                Prefs.setEnabled(this, true); DeviceOwner.apply(this); render()
            }
        )

        // --- Device-level video blocking ---
        col.addView(section("חסימת וידאו ברמת המכשיר"))
        col.addView(text(
            "מזהה נגן וידאו בתוך כל אפליקציה (וואטסאפ, גלריה, טלגרם, דפדפן ועוד) " +
            "וכל השמעה שהמערכת מסמנת כסרט – עוצר אותה וחוזר למסך הבית."
        ))
        val detect = Prefs.isDetectVideo(this)
        col.addView(card(if (detect) "זיהוי וידאו בכל האפליקציות: פעיל" else "זיהוי וידאו בכל האפליקציות: כבוי",
            if (detect) green else red))
        col.addView(
            if (detect) button("כבה זיהוי וידאו (דורש סיסמה)", red) {
                askPin { Prefs.setDetectVideo(this, false); render() }
            } else button("הפעל זיהוי וידאו", green) {
                Prefs.setDetectVideo(this, true); render()
            }
        )
        val strict = Prefs.isStrict(this)
        col.addView(
            if (strict) button("מצב מחמיר: פעיל – לחץ לכיבוי (דורש סיסמה)", Color.DKGRAY) {
                askPin { Prefs.setStrict(this, false); render() }
            } else button("מצב מחמיר: כבוי – לחץ להפעלה", Color.DKGRAY) {
                Prefs.setStrict(this, true); render()
            }
        )
        col.addView(text("מצב מחמיר מזהה גם נגנים שאפליקציות בנו בעצמן. עלול לחסום לפעמים גם שיחות וידאו."))

        // --- Allowed-apps-only mode ---
        col.addView(section("מצב אפליקציות מאושרות בלבד"))
        col.addView(text(
            "החסימה החזקה ביותר בלי מחשב: רק אפליקציות שברשימה למטה (וחיוג, מקלדת, הגדרות ומסך הבית) נפתחות. " +
            "כל השאר – כולל אפליקציות חדשות – נחסמות."
        ))
        val allowMode = Prefs.isAllowlistMode(this)
        col.addView(
            if (allowMode) button("מצב מאושרות בלבד: פעיל – לחץ לכיבוי (דורש סיסמה)", red) {
                askPin { Prefs.setAllowlistMode(this, false); render() }
            } else button("הפעל מצב מאושרות בלבד", green) {
                Prefs.setAllowlistMode(this, true); render()
            }
        )
        col.addView(text("אפליקציות מאושרות (שם חבילה בכל שורה):"))
        val allowed = listField(Prefs.allowedText(this))
        col.addView(allowed)
        col.addView(button("שמור רשימת מאושרות", navy) {
            Prefs.saveAllowed(this, allowed.text.toString()); toast("נשמר"); render()
        })

        // --- Device Owner ---
        col.addView(section("מצב בעלים (נעילה ברמת המערכת)"))
        if (DeviceOwner.isOwner(this)) {
            col.addView(card("✅ מצב בעלים פעיל", green))
            col.addView(text(
                "אפליקציות הווידאו שברשימת החסומות מוסתרות מהמכשיר כולו. " +
                "האפליקציה הזו לא ניתנת להסרה, ומצב בטוח ואיפוס יצרן חסומים."
            ))
            val inst = Prefs.isBlockInstalls(this)
            col.addView(
                if (inst) button("התקנת אפליקציות: חסומה – לחץ להתרה (דורש סיסמה)", Color.DKGRAY) {
                    askPin { Prefs.setBlockInstalls(this, false); DeviceOwner.apply(this); render() }
                } else button("חסום התקנת אפליקציות חדשות", Color.DKGRAY) {
                    Prefs.setBlockInstalls(this, true); DeviceOwner.apply(this); render()
                }
            )
            col.addView(button("הסר מצב בעלים (דורש סיסמה)", red) {
                askPin { DeviceOwner.release(this); toast("מצב בעלים הוסר"); render() }
            })
        } else {
            col.addView(text(
                "החסימה החזקה ביותר: מסתיר את אפליקציות הווידאו (גם יוטיוב המובנה) מהמערכת, " +
                "חוסם מצב בטוח, איפוס יצרן והתקנת אפליקציות. מוגדר פעם אחת ממחשב – ההוראות בקובץ README. " +
                "לפני ההרצה יש להסיר זמנית את כל החשבונות מהטלפון (גוגל, סמסונג)."
            ))
            col.addView(TextView(this).apply {
                text = DeviceOwner.ADB_COMMAND
                setTextIsSelectable(true)
                textDirection = View.TEXT_DIRECTION_LTR
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setBackgroundColor(Color.WHITE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = lp(6)
            })
        }

        // --- Protection against removal ---
        col.addView(section("הגנה מפני עקיפה"))
        val tamper = Prefs.isTamperProtect(this)
        col.addView(text(
            "כשההגנה פעילה, מסכי ההגדרות שמאפשרים לכבות את השירות או להסיר את האפליקציה נחסמים. " +
            "הפעל אותה אחרי שהשירות ומניעת ההסרה כבר פועלים."
        ))
        col.addView(
            if (tamper) button("כבה הגנת הגדרות (דורש סיסמה)", red) {
                askPin { Prefs.setTamperProtect(this, false); render() }
            } else button("הפעל הגנת הגדרות", green) {
                Prefs.setTamperProtect(this, true); render()
            }
        )

        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (DeviceOwner.isOwner(this)) {
            col.addView(text("✅ מניעת הסרת האפליקציה פעילה (מצב בעלים)"))
        } else if (dpm.isAdminActive(admin)) {
            col.addView(text("✅ מניעת הסרת האפליקציה פעילה"))
            col.addView(button("בטל מניעת הסרה (דורש סיסמה)", red) {
                askPin { dpm.removeActiveAdmin(admin); toast("בוטל"); render() }
            })
        } else {
            col.addView(button("הפעל מניעת הסרת האפליקציה", navy) {
                Prefs.startGrace(this)
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_desc)))
            })
        }

        // --- Lists ---
        col.addView(section("אפליקציות חסומות"))
        col.addView(text("שם חבילה בכל שורה (למשל com.google.android.youtube)"))
        val apps = listField(Prefs.appsText(this))
        col.addView(apps)

        col.addView(section("אתרים חסומים בדפדפן"))
        col.addView(text("כתובת בכל שורה (למשל youtube.com)"))
        val sites = listField(Prefs.sitesText(this))
        col.addView(sites)

        col.addView(button("שמור רשימות", navy) {
            Prefs.saveLists(this, apps.text.toString(), sites.text.toString())
            DeviceOwner.apply(this)
            toast("נשמר")
            render()
        })
        col.addView(button("שחזר רשימות ברירת מחדל", Color.GRAY) {
            apps.setText(Prefs.DEFAULT_APPS.joinToString("\n"))
            sites.setText(Prefs.DEFAULT_SITES.joinToString("\n"))
            toast("לחץ \"שמור רשימות\" לאישור")
        })

        // --- Account ---
        col.addView(section("סיסמה"))
        col.addView(button("שנה סיסמה", navy) { askPin { showSetPin(firstTime = false) } })
        col.addView(button("נעל עכשיו", Color.DKGRAY) { unlocked = false; render() })
    }

    // ================= Helpers =================

    private fun isServiceOn(): Boolean {
        val list = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return list.split(':').any { it.startsWith("$packageName/") }
    }

    private fun askPin(onOk: () -> Unit) {
        val f = passField("סיסמה")
        AlertDialog.Builder(this)
            .setTitle("אימות סיסמה")
            .setView(LinearLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0); addView(f)
            })
            .setPositiveButton("אישור") { _, _ ->
                if (Prefs.checkPin(this, f.text.toString())) onOk() else toast("סיסמה שגויה")
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun page(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F4F6F9"))
            addView(col)
        })
        return col
    }

    private fun lp(top: Int = 8) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(navy); gravity = Gravity.START; layoutParams = lp(0)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(navy); layoutParams = lp(24)
    }

    private fun text(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Color.parseColor("#4A5568"))
        layoutParams = lp(6); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun card(t: String, color: Int) = TextView(this).apply {
        text = t; textSize = 16f; setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
        layoutParams = lp(12)
    }

    private fun button(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
        layoutParams = lp(10)
        setOnClickListener { onClick() }
    }

    private fun passField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine()
        layoutParams = lp(10)
    }

    private fun listField(value: String) = EditText(this).apply {
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 4; maxLines = 10; textSize = 13f
        textDirection = View.TEXT_DIRECTION_LTR
        setBackgroundColor(Color.WHITE)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = lp(6)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
