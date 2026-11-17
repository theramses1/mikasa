package com.mikasa.casaos;

import android.app.Activity;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Pantalla para introducir la dirección de CasaOS y las credenciales. */
public class SetupActivity extends Activity {

    private EditText server, user, pass;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final Prefs prefs = new Prefs(this);
        int pad = Ui.dp(this, 20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setText(R.string.setup_help);
        help.setTextColor(getResources().getColor(R.color.text_muted));
        help.setTextSize(14);
        root.addView(help);

        server = field(R.string.server_hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        user = field(R.string.user_hint, InputType.TYPE_CLASS_TEXT);
        pass = field(R.string.pass_hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(server);
        root.addView(user);
        root.addView(pass);
        server.setText(prefs.server());
        user.setText(prefs.user());
        pass.setText(prefs.pass());

        Button save = new Button(this);
        save.setText(R.string.save);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 20);
        root.addView(save, lp);

        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tryConnect(prefs); }
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private EditText field(int hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        e.setLayoutParams(lp);
        return e;
    }

    private void tryConnect(final Prefs prefs) {
        String s = server.getText().toString().trim();
        if (s.length() == 0) {
            server.setError("Obligatorio");
            return;
        }
        prefs.save(s, user.getText().toString().trim(), pass.getText().toString());

        final ProgressDialog pd = ProgressDialog.show(this, null, "Conectando…", true, false);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    new CasaApi(prefs).login();
                } catch (Exception e) {
                    err = Ui.describe(e);
                }
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pd.dismiss();
                        if (fErr != null) {
                            Toast.makeText(SetupActivity.this,
                                    "No se pudo iniciar sesión: " + fErr
                                            + "\nPodrás usar igualmente el panel web.",
                                    Toast.LENGTH_LONG).show();
                        }
                        setResult(RESULT_OK);
                        finish();
                    }
                });
            }
        }).start();
    }
}
