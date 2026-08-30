package io.github.hongguo.backgroundaudio;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int PRIMARY = Color.rgb(214, 59, 50);
    private static final int TEXT = Color.rgb(42, 28, 26);
    private static final int MUTED = Color.rgb(107, 85, 80);
    private static final int SURFACE = Color.rgb(255, 249, 247);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(ViewGroup.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(SURFACE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(40), dp(28), dp(40));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView badge = text("LSPosed 模块", 14, PRIMARY);
        badge.setGravity(Gravity.START);
        content.addView(badge);

        TextView title = text("红果后台听", 32, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title, margins(0, 8, 0, 6));

        TextView subtitle = text("让短剧退到后台或锁屏后，声音仍继续播放。", 17, MUTED);
        subtitle.setLineSpacing(0, 1.25f);
        content.addView(subtitle, margins(0, 0, 0, 30));

        addStep(content, "1", "在 LSPosed 中启用本模块");
        addStep(content, "2", "作用域仅勾选“红果免费短剧”");
        addStep(content, "3", "强行停止红果后重新打开并播放短剧");
        addStep(content, "4", "按 Home 或锁屏测试后台声音");

        TextView note = text("提示\n首次启用或更新模块后必须重启红果进程。用户主动暂停、切换剧集和返回前台的行为不会被长期拦截。不同红果版本的播放器实现可能变化，若无效请附上红果版本号和 LSPosed 日志。", 14, MUTED);
        note.setLineSpacing(0, 1.35f);
        SpannableString styled = new SpannableString(note.getText());
        styled.setSpan(new StyleSpan(Typeface.BOLD), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        note.setText(styled);
        note.setBackgroundColor(Color.rgb(250, 235, 231));
        note.setPadding(dp(18), dp(16), dp(18), dp(16));
        content.addView(note, margins(0, 26, 0, 0));

        setContentView(scroll);
    }

    private void addStep(LinearLayout parent, String number, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView marker = text(number, 15, Color.WHITE);
        marker.setGravity(Gravity.CENTER);
        marker.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        marker.setBackgroundColor(PRIMARY);
        row.addView(marker, new LinearLayout.LayoutParams(dp(34), dp(34)));

        TextView label = text(body, 16, TEXT);
        label.setPadding(dp(14), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row, margins(0, 0, 0, 18));
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
