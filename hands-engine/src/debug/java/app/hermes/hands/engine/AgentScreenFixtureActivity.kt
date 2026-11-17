package app.hermes.hands.engine

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug fixture only; does not use or send personal device data. */
class AgentScreenFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xff101721.toInt())
        }
        val state = TextView(this).apply { text = "ISOLATED AGENT FIXTURE"; setTextColor(0xff67e8f9.toInt()); textSize = 22f }
        val input = EditText(this).apply {
            hint = "Fixture text"
            contentDescription = "hands-fixture-input"
            setTextColor(0xffffffff.toInt()); setHintTextColor(0xffd3dce7.toInt())
        }
        val button = Button(this).apply {
            text = "Verify isolated tap"
            isAllCaps = false
            contentDescription = "hands-fixture-verify"
            setOnClickListener { state.text = "ISOLATED TAP VERIFIED" }
        }
        layout.addView(state)
        layout.addView(input)
        layout.addView(button)
        setContentView(layout)
    }
}
