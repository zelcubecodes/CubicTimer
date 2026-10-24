package com.hatopigeon.cubictimer.activity;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.view.WindowCompat;

import android.view.View;
import android.view.Window;

import com.afollestad.materialdialogs.MaterialDialog;
import com.hatopigeon.cubicify.R;
import com.hatopigeon.cubictimer.utils.InsetsUtils;
import com.hatopigeon.cubictimer.utils.LocaleUtils;
import com.hatopigeon.cubictimer.utils.Prefs;
import com.hatopigeon.cubictimer.utils.StoreUtils;
import com.hatopigeon.cubictimer.utils.ThemeUtils;

import butterknife.BindView;
import butterknife.ButterKnife;
import de.psdev.licensesdialog.LicensesDialog;

public class AboutActivity extends AppCompatActivity {

    private final static String APP_TITLE = "Ztimer";
    @BindView(R.id.feedbackButton)    AppCompatTextView feedbackButton;
    @BindView(R.id.licenseButton)     AppCompatTextView licenseButton;
    @BindView(R.id.testersButton)     AppCompatTextView testersButton;
    @BindView(R.id.sourceButton)      AppCompatTextView sourceButton;
    @BindView(R.id.appVersion)        AppCompatTextView appVersion;
    @BindView(R.id.translatorsButton) AppCompatTextView translatorsButton;
    @BindView(R.id.contributorsButton)AppCompatTextView contributorsButton;
    @BindView(R.id.back)               View     backButton;

    View.OnClickListener clickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            switch (view.getId()) {
                case R.id.feedbackButton:
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/zelcubecodes/CubicTimer/issues")));
                    break;
                case R.id.licenseButton:
                    new LicensesDialog.Builder(AboutActivity.this)
                        .setNotices(R.raw.notices_app)
                        .build()
                        .show();
                    break;
                case R.id.testersButton:
                    ThemeUtils.roundAndShowDialog(AboutActivity.this, new MaterialDialog.Builder(AboutActivity.this)
                        .title(R.string.testers)
                        .content(R.string.testers_content)
                        .positiveText(R.string.action_ok)
                        .build());
                    break;
                case R.id.translatorsButton:
                    ThemeUtils.roundAndShowDialog(AboutActivity.this, new MaterialDialog.Builder(AboutActivity.this)
                        .title(R.string.translators)
                        .content(getString(R.string.translators_content, StoreUtils.getStringFromRaw(getResources(), R.raw.translators)))
                        .positiveText(R.string.action_ok)
                        .build());
                    break;
                case R.id.contributorsButton:
                    ThemeUtils.roundAndShowDialog(AboutActivity.this, new MaterialDialog.Builder(AboutActivity.this)
                            .title(R.string.contributors)
                            .content(getString(R.string.contributors_content, getString(R.string.contributors_names)))
                            .positiveText(R.string.action_ok)
                            .build());
                    break;
                case R.id.sourceButton:
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/hato-ya/CubicTimer"));
                    startActivity(browserIntent);
                    break;
            }
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleUtils.updateLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setTheme(ThemeUtils.getPreferredTheme());

        super.onCreate(savedInstanceState);

        // Since windowLightNavigationBar is not supported below API level 27,
        // set a dark navigation bar color to maintain contrast with light themes
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 &&
                !Prefs.getBoolean(R.string.pk_tint_navigation_bar, false)) {
            Window window = getWindow();
            if (window != null) {
                window.setNavigationBarColor(0xB3000000);
            }
        }

        setContentView(R.layout.activity_about);
        ButterKnife.bind(this);

        backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            appVersion.setText(versionName);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        AppCompatTextView creatorText = (AppCompatTextView) findViewById(R.id.creator);
        creatorText.setText(R.string.about_creator);
        creatorText.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        feedbackButton.setOnClickListener(clickListener);
        licenseButton.setOnClickListener(clickListener);
        testersButton.setOnClickListener(clickListener);
        sourceButton.setOnClickListener(clickListener);
        translatorsButton.setOnClickListener(clickListener);
        contributorsButton.setOnClickListener(clickListener);

        InsetsUtils.applySafeInsetsPadding(findViewById(R.id.activity_about), false);
    }
}
