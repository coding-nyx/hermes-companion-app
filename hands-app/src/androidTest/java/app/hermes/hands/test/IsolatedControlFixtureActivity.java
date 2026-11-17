package app.hermes.hands.test;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Disposable native test UI. This Activity runs in the test APK's separate process,
 * whose class loader cannot use Kotlin libraries provided only by the target APK.
 */
public final class IsolatedControlFixtureActivity extends Activity {
    private int taps;
    private EditText input;
    private TextView tapStatus;
    private TextView typedStatus;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(240, 246, 250));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        content.setFocusableInTouchMode(true);
        content.addView(label("HANDS ACCEPTANCE FIXTURE", 48));

        tapStatus = label("Isolated taps: 0", 48);
        typedStatus = label("Typed value not checked", 48);
        input = new EditText(this);
        input.setId(View.generateViewId());
        input.setHint("Agent test input");
        input.setContentDescription("Agent test input");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setMinHeight(dp(56));
        input.setTextSize(18f);
        input.setTextColor(Color.rgb(15, 30, 45));
        input.setHintTextColor(Color.rgb(70, 85, 100));
        input.setAutofillHints((String[]) null);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));

        Button verifyTap = button("Verify isolated tap");
        verifyTap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                taps++;
                tapStatus.setText("Isolated taps: " + taps);
            }
        });
        content.addView(verifyTap, new LinearLayout.LayoutParams(-1, -2));
        content.addView(tapStatus);

        Button verifyTyped = button("Verify typed value");
        verifyTyped.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                typedStatus.setText("HANDS_ASCII_123".equals(input.getText().toString())
                        ? "Typed value verified" : "Typed value missing");
                input.clearFocus();
                InputMethodManager keyboard = getSystemService(InputMethodManager.class);
                if (keyboard != null) {
                    keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
                }
            }
        });
        content.addView(verifyTyped, new LinearLayout.LayoutParams(-1, -2));
        content.addView(typedStatus);

        for (int index = 1; index <= 18; index++) {
            content.addView(label("Synthetic scroll row " + index, 72));
        }
        content.addView(label("Synthetic bottom reached", 48));
        final Button verifyScroll = button("Verify isolated scroll");
        verifyScroll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                verifyScroll.setText("Scroll verified");
            }
        });
        content.addView(verifyScroll, new LinearLayout.LayoutParams(-1, -2));

        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        setContentView(scroll);
        content.requestFocus();
    }

    private TextView label(String value, int height) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(18f);
        label.setTextColor(Color.rgb(15, 30, 45));
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setMinHeight(dp(height));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return label;
    }

    private Button button(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setMinHeight(dp(56));
        return button;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
