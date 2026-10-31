package x.vladgba.gps.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import x.vladgba.gps.R;

/**
 * The shared p* "About" dialog (Java port of the pUI kit's PAbout.kt, same layout and tokens):
 * icon, name, version, e-mail and GitHub / Report a problem buttons.
 */
public final class AboutDialog {
    private static final String REPO = "https://github.com/vladgba/pGPS";
    private static final String EMAIL = "vladgba@gmail.com";

    private AboutDialog() {}

    public static Dialog show(Activity a) {
        float d = a.getResources().getDisplayMetrics().density;
        String version = "";
        try {
            version = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception ignored) { }

        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setBackground(shape(a.getColor(R.color.p_surface), 20 * d, false, a));
        content.setPadding(px(d, 24), px(d, 28), px(d, 24), px(d, 20));

        ImageView icon = new ImageView(a);
        icon.setImageDrawable(a.getApplicationInfo().loadIcon(a.getPackageManager()));
        content.addView(icon, new LinearLayout.LayoutParams(px(d, 72), px(d, 72)));

        TextView name = new TextView(a);
        name.setText(a.getApplicationInfo().loadLabel(a.getPackageManager()));
        name.setTextSize(24);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setTextColor(a.getColor(R.color.p_text));
        name.setPadding(0, px(d, 16), 0, 0);
        content.addView(name);

        if (version != null && !version.isEmpty()) {
            TextView v = new TextView(a);
            v.setText("Version " + version);
            v.setTextSize(15);
            v.setTextColor(a.getColor(R.color.p_text2));
            v.setPadding(0, px(d, 4), 0, 0);
            content.addView(v);
        }

        TextView mail = new TextView(a);
        mail.setText(EMAIL);
        mail.setTextSize(15);
        mail.setTextColor(a.getColor(R.color.p_accent_text));
        mail.setPadding(px(d, 12), px(d, 10), px(d, 12), px(d, 10));
        mail.setBackground(shape(0, 999 * d, true, a));
        mail.setOnClickListener(v -> open(a, "mailto:" + EMAIL));
        content.addView(mail);

        List<String[]> buttons = new ArrayList<>();
        buttons.add(new String[]{"GitHub", REPO});
        buttons.add(new String[]{"Report a problem", REPO + "/issues"});
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] b : buttons) {
            Button btn = new Button(a, null, 0, R.style.P_Button_Tonal);
            btn.setText(b[0]);
            btn.setOnClickListener(v -> open(a, b[1]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(px(d, 4), px(d, 4), px(d, 4), px(d, 4));
            row.addView(btn, lp);
        }
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = px(d, 12);
        content.addView(row, rowLp);

        Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(Math.min((int) (a.getResources().getDisplayMetrics().widthPixels * 0.9f), px(d, 420)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        return dialog;
    }

    private static Drawable shape(int fill, float radius, boolean ripple, Activity a) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (!ripple) return g;
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(a.getColor(R.color.p_ripple)), g, mask);
    }

    private static int px(float density, int dp) {
        return (int) (dp * density + 0.5f);
    }

    private static void open(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) { }
    }
}
