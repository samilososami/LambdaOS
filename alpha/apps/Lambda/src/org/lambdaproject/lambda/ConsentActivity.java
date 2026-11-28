package org.lambdaproject.lambda;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Fullscreen consent for a browser asking to receive this screen.
 *
 * The page opens this with `am start ... --es token <uuid>` and polls
 * /sdcard/Android/data/org.lambdaproject.lambda/files/consent-&lt;token&gt; for the
 * answer, so nothing is shared until the person taps Accept here.
 */
public final class ConsentActivity extends Activity {
    private static final int BG = 0xff0b0b0c;
    private static final int INK = 0xfff4f4f2;
    private static final int MUTED = 0xff9b9b98;
    private static final int FAINT = 0xff5d5d5b;
    private static final int LINE = 0xff26262a;

    private String token;
    private boolean answered;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        token = getIntent() == null ? null : getIntent().getStringExtra("token");
        // The token names a file next to the app's own data, so keep it boring.
        if (token == null || token.isEmpty() || token.length() > 64 || !token.matches("[A-Za-z0-9-]+")) {
            finish();
            return;
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(build());
        hideSystemBars();
        clearPreviousAnswer();
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT < 30) return;
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) return;
        controller.hide(WindowInsets.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(48), dp(32), dp(40));

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_core);
        root.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(54)));

        root.addView(space(30));

        TextView eyebrow = text("LEX  ·  SOLICITUD DE CONEXIÓN", 11, MUTED);
        eyebrow.setLetterSpacing(.16f);
        root.addView(eyebrow);

        root.addView(space(18));

        TextView title = text("Se ha pedido autorización para recibir pantalla", 30, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(-.03f);
        title.setLineSpacing(dp(2), 1f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        root.addView(space(20));

        TextView body = text(
            "Un navegador conectado por USB quiere mostrar el escritorio de este teléfono. "
                + "No se envía nada hasta que aceptes, y puedes cortarlo cerrando la página.",
            15, MUTED);
        body.setLineSpacing(dp(5), 1f);
        body.setGravity(Gravity.CENTER);
        root.addView(body, new LinearLayout.LayoutParams(-1, -2));

        root.addView(space(40));

        TextView accept = button("Aceptar", true);
        accept.setOnClickListener(v -> answer("accepted"));
        root.addView(accept, new LinearLayout.LayoutParams(-1, dp(58)));

        root.addView(space(12));

        TextView reject = button("Rechazar", false);
        reject.setOnClickListener(v -> answer("rejected"));
        root.addView(reject, new LinearLayout.LayoutParams(-1, dp(58)));

        root.addView(space(24));

        TextView note = text("Cada vez que un navegador quiera conectarse se pedirá aquí.", 12, FAINT);
        note.setGravity(Gravity.CENTER);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        return root;
    }

    private TextView text(String value, float size, int colour) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(colour);
        view.setIncludeFontPadding(false);
        return view;
    }

    private View space(int height) {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        return gap;
    }

    private TextView button(String label, boolean primary) {
        TextView view = text(label, 17, primary ? 0xff0b0b0c : INK);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(29));
        if (primary) {
            shape.setColor(INK);
        } else {
            shape.setColor(Color.TRANSPARENT);
            shape.setStroke(dp(1), LINE);
        }
        view.setBackground(shape);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private File answerFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "consent-" + token);
    }

    private void clearPreviousAnswer() {
        File file = answerFile();
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void answer(String verdict) {
        if (answered) return;
        answered = true;
        try (FileOutputStream out = new FileOutputStream(answerFile())) {
            out.write((verdict + " " + System.currentTimeMillis() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Without an answer file the page times out; nothing is shared either way.
        }
        finish();
    }

    @Override public void onBackPressed() {
        answer("rejected");
    }
}
