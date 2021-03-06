package com.mirfatif.permissionmanagerx;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.snackbar.Snackbar;
import com.mirfatif.privtasks.Commands;
import com.mirfatif.privtasks.Util;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

public class AboutActivity extends AppCompatActivity {

  private MySettings mMySettings;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_about);

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) actionBar.setTitle(R.string.about_menu_item);

    mMySettings = MySettings.getInstance();

    ((TextView) findViewById(R.id.version)).setText(BuildConfig.VERSION_NAME);
    openWebUrl(R.id.telegram, R.string.telegram_link);
    openWebUrl(R.id.source_code, R.string.source_url);
    openWebUrl(R.id.issues, R.string.issues_url);
    openWebUrl(R.id.rating, R.string.play_store_url);
    findViewById(R.id.contact).setOnClickListener(v -> Utils.sendMail(this, null));
    setLogTitle();
    findViewById(R.id.logging).setOnClickListener(v -> handleLogging());

    if (!MainActivityFlavor.IS_FREE_VERSION) {
      findViewById(R.id.paid_features_container).setVisibility(View.GONE);
    } else {
      TextView paidFeaturesSummaryView = findViewById(R.id.paid_features_summary);
      View paidFeaturesDetailView = findViewById(R.id.paid_features_detail);

      paidFeaturesSummaryView.setText(
          getString(R.string.paid_feature1)
              + ", "
              + getString(R.string.paid_feature2)
              + ", "
              + getString(R.string.paid_feature3));

      findViewById(R.id.paid_features)
          .setOnClickListener(
              v -> {
                if (paidFeaturesDetailView.isVisibleToUser()) {
                  paidFeaturesDetailView.setVisibility(View.GONE);
                  paidFeaturesSummaryView.setVisibility(View.VISIBLE);
                } else {
                  paidFeaturesDetailView.setVisibility(View.VISIBLE);
                  paidFeaturesSummaryView.setVisibility(View.GONE);
                }
              });
    }
  }

  private void openWebUrl(int viewResId, int linkResId) {
    findViewById(viewResId).setOnClickListener(v -> Utils.openWebUrl(this, getString(linkResId)));
  }

  private boolean logInProgress = false;

  private void setLogTitle() {
    logInProgress = mMySettings.DEBUG;
    ((TextView) findViewById(R.id.logging_title))
        .setText(logInProgress ? R.string.stop_logging : R.string.collect_logs);
  }

  private void handleLogging() {
    if (logInProgress) {
      Utils.runInBg(
          () -> {
            Utils.stopLogging();
            Utils.runInFg(
                () -> {
                  setLogTitle();
                  Snackbar.make(findViewById(R.id.logging), R.string.logging_stopped, 5000).show();
                });
          });
      return;
    }

    Toast.makeText(App.getContext(), R.string.select_log_file, Toast.LENGTH_LONG).show();
    ActivityResultCallback<Uri> callback = uri -> Utils.runInBg(() -> doLoggingInBg(uri));
    registerForActivityResult(new ActivityResultContracts.CreateDocument(), callback)
        .launch("PermissionManagerX_" + Util.getCurrDateTime() + ".log");
  }

  private void doLoggingInBg(Uri uri) {
    try {
      OutputStream outStream = getApplication().getContentResolver().openOutputStream(uri, "rw");
      Utils.mLogcatWriter = new BufferedWriter(new OutputStreamWriter(outStream));
    } catch (FileNotFoundException e) {
      e.printStackTrace();
      return;
    }

    if (mMySettings.mPrivDaemonAlive) {
      PrivDaemonHandler.getInstance().sendRequest(Commands.SHUTDOWN);
    }
    mMySettings.doLogging = true;
    mMySettings.DEBUG = true;
    Utils.runCommand("logcat -c", "Logging", null);
    Intent intent = new Intent(App.getContext(), MainActivity.class);
    intent
        .setAction(MainActivity.ACTION_START_LOGGING)
        .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    Utils.runInFg(
        () -> {
          startActivity(intent);
          finish();
        });
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // do not recreate parent (Main) activity
    if (item.getItemId() == android.R.id.home) {
      onBackPressed();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }
}
