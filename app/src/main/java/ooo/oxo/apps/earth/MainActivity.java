/*
 * Mantou Earth - Live your wallpaper with live earth
 * Copyright (C) 2015-2019 XiNGRZ <xxx@oxo.ooo>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ooo.oxo.apps.earth;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.preference.PreferenceManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.umeng.analytics.MobclickAgent;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import ooo.oxo.apps.earth.dao.Settings;
import ooo.oxo.apps.earth.databinding.MainActivityBinding;
import ooo.oxo.apps.earth.provider.EarthsContract;
import ooo.oxo.apps.earth.provider.SettingsContract;
import ooo.oxo.apps.earth.view.InOutAnimationUtils;
import ooo.oxo.apps.earth.widget.ImmersiveUtil;
import ooo.oxo.apps.earth.widget.SystemUiVisibilityUtil;

public class MainActivity extends AppCompatActivity {

    @SuppressWarnings("unused")
    private static final String TAG = "MainActivity";

    private static final int REQUEST_SETTINGS = 1;

    private MainActivityBinding binding;

    private MainViewModel vm = new MainViewModel();

    private SharedPreferences pref;

    private ContentObserver observer = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            runOnUiThread(() -> loadEarth());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 19) {
            SystemUiVisibilityUtil.addFlags(getWindow().getDecorView(),
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        binding = DataBindingUtil.setContentView(this, R.layout.main_activity);

        setTitle(null);
        setSupportActionBar(binding.toolbar.toolbar);

        binding.setVm(vm);
        binding.setAccelerated(BuildConfig.USE_OXO_SERVER);

        binding.settingsPanel.editMode.setOnClickListener(v -> animateInEditMode());
        binding.scaling.apply.setOnClickListener(v -> animateOutEditMode());
        binding.scaling.apply.setOnLongClickListener(v -> restoreScalingDefault());

        binding.action.done.setOnClickListener(v -> saveAndHideSettings());
        binding.action.done.setOnLongClickListener(v -> openAdvancedSettings());

        binding.earth.scalingLayout.setOnClickListener(v -> {
            if (!isSettingsShown()) {
                toggleImmersiveMode();
            }
        });

        pref = PreferenceManager.getDefaultSharedPreferences(this);

        loadSettings();

        loadEarth();

        final ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (NetworkStateUtil.shouldConsiderSavingData(cm)) {
            Toast.makeText(this, R.string.data_saver_considered, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSettings() {
        Cursor cursor = getContentResolver().query(
                SettingsContract.CONTENT_URI, null, null, null, null);

        if (cursor == null) {
            throw new IllegalStateException();
        }

        Settings settings = Settings.fromCursor(cursor);

        cursor.close();

        if (settings == null) {
            throw new IllegalStateException();
        }

        vm.loadFrom(settings);

        if (isFromSettings() || shouldPromptToChangeWallpaper()) {
            showSettings();
        }

        setupUpdate();
    }

    private void saveSettings() {
        Settings settings = new Settings();

        vm.saveTo(settings);

        getContentResolver().update(SettingsContract.CONTENT_URI,
                settings.toContentValues(), null, null);

        sendOnSet(settings);
    }

    private void saveAndHideSettings() {
        saveSettings();

        if (isFromSettings()) {
            if (isCurrentWallpaper()) {
                setResult(RESULT_OK);
                supportFinishAfterTransition();
            } else {
                setResult(RESULT_OK);
                supportFinishAfterTransition();
                WallpaperUtil.changeLiveWallPaper(this);
            }
        } else if (shouldPromptToChangeWallpaper()) {
            setResult(RESULT_OK);
            supportFinishAfterTransition();
            WallpaperUtil.changeLiveWallPaper(this);
        } else {
            if (!isWallpaperSupported()) {
                Toast.makeText(this, R.string.live_wallpaper_unsupported, Toast.LENGTH_SHORT).show();
            }

            animateOutSettings();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        switch (requestCode) {
            case REQUEST_SETTINGS:
                refreshSettingsFromPreference();
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
    }

    private boolean openAdvancedSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
        return true;
    }

    private void refreshSettingsFromPreference() {
        vm.setDebug(pref.getBoolean("debug", false));
        setupUpdate();
    }

    @SuppressLint("RestrictedApi")
    private void showSettings() {
        binding.toolbar.getRoot().setVisibility(View.INVISIBLE);
        binding.settings.setVisibility(View.VISIBLE);
        binding.action.done.setVisibility(View.VISIBLE);
        binding.action.doneHint.setVisibility(View.VISIBLE);
    }

    @SuppressLint("RestrictedApi")
    @SuppressWarnings("unused")
    private void hideSettings() {
        binding.settings.setVisibility(View.INVISIBLE);
        binding.action.done.setVisibility(View.INVISIBLE);
        binding.action.doneHint.setVisibility(View.INVISIBLE);
        binding.toolbar.getRoot().setVisibility(View.VISIBLE);
    }

    private boolean isSettingsShown() {
        return binding.settings.getVisibility() == View.VISIBLE;
    }

    private void animateInSettings() {
        InOutAnimationUtils.animateOut(binding.toolbar.getRoot(), R.anim.main_toolbar_out);

        binding.action.done.postDelayed(() -> binding.action.done.show(), 300L);

        InOutAnimationUtils.animateIn(binding.action.doneHint, R.anim.main_action_hint_in);
        InOutAnimationUtils.animateIn(binding.settings, R.anim.main_settings_in);

        if (Build.VERSION.SDK_INT >= 21) {
            int width = binding.settings.getWidth();
            int height = binding.settings.getHeight();

            int revealOffset = getResources().getDimensionPixelOffset(R.dimen.settings_reveal_offset);

            Animator animator = ViewAnimationUtils.createCircularReveal(
                    binding.settings,
                    width - revealOffset, height - revealOffset,
                    0, Math.max(width, height));

            binding.settings.setVisibility(View.VISIBLE);

            animator.setDuration(300L);

            animator.start();
        }
    }

    private void animateOutSettings() {
        InOutAnimationUtils.animateIn(binding.toolbar.getRoot(), R.anim.main_toolbar_in);

        binding.action.done.hide();

        InOutAnimationUtils.animateOut(binding.action.doneHint, R.anim.main_action_hint_out);
        InOutAnimationUtils.animateOut(binding.settings, R.anim.main_settings_out);

        if (Build.VERSION.SDK_INT >= 21) {
            int width = binding.settings.getWidth();
            int height = binding.settings.getHeight();

            int revealOffset = getResources().getDimensionPixelOffset(R.dimen.settings_reveal_offset);

            Animator animator = ViewAnimationUtils.createCircularReveal(
                    binding.settings,
                    width - revealOffset, height - revealOffset,
                    Math.max(width, height), 0);

            animator.setDuration(300L);

            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    binding.settings.setVisibility(View.INVISIBLE);
                }
            });

            animator.start();
        }
    }

    private void enterEditMode() {
        binding.settings.setVisibility(View.INVISIBLE);
        binding.action.action.setVisibility(View.INVISIBLE);
        binding.scaling.apply.setVisibility(View.VISIBLE);
        binding.scaling.hint.setVisibility(View.VISIBLE);
        binding.earth.scalingLayout.enterEditMode();
    }

    @SuppressWarnings("unused")
    private void exitEditMode() {
        binding.earth.scalingLayout.exitEditMode();
        binding.scaling.hint.setVisibility(View.INVISIBLE);
        binding.scaling.apply.setVisibility(View.INVISIBLE);
        binding.action.action.setVisibility(View.VISIBLE);
        binding.settings.setVisibility(View.VISIBLE);
    }

    private boolean isEditMode() {
        return binding.earth.scalingLayout.isEditMode();
    }

    private void animateInEditMode() {
        InOutAnimationUtils.animateOut(binding.settings, R.anim.main_settings_out);
        InOutAnimationUtils.animateOut(binding.action.action, R.anim.main_settings_out);
        InOutAnimationUtils.animateIn(binding.scaling.apply, R.anim.main_scaling_in);
        InOutAnimationUtils.animateIn(binding.scaling.hint, R.anim.main_toolbar_fade_in);
        binding.earth.scalingLayout.enterEditMode();
    }

    private void animateOutEditMode() {
        binding.earth.scalingLayout.exitEditMode();
        InOutAnimationUtils.animateOut(binding.scaling.hint, R.anim.main_toolbar_fade_out);
        InOutAnimationUtils.animateOut(binding.scaling.apply, R.anim.main_scaling_out);
        InOutAnimationUtils.animateIn(binding.action.action, R.anim.main_settings_in);
        InOutAnimationUtils.animateIn(binding.settings, R.anim.main_settings_in);
    }

    private boolean restoreScalingDefault() {
        vm.setScaling(0f, 0f, 1.0f);
        Toast.makeText(this, R.string.scaling_restore, Toast.LENGTH_SHORT).show();
        return true;
    }

    @Override
    public void onBackPressed() {
        if (isEditMode()) {
            animateOutEditMode();
        } else if (isSettingsShown() && !(isFromSettings() || shouldPromptToChangeWallpaper())) {
            animateOutSettings();
        } else {
            super.onBackPressed();
        }
    }

    private void sendOnSet(Settings settings) {
        HashMap<String, String> event = new HashMap<>();

        event.put("interval", String.valueOf(TimeUnit.MILLISECONDS.toMinutes(settings.interval)));
        event.put("resolution", String.valueOf(settings.resolution));
        event.put("wifi_only", String.valueOf(settings.wifiOnly));

        MobclickAgent.onEvent(this, "set", event);
    }

    @Override
    protected void onResume() {
        super.onResume();

        MobclickAgent.onResume(this);

        getContentResolver().registerContentObserver(
                EarthsContract.LATEST_CONTENT_URI, false, observer);
    }

    @Override
    protected void onPause() {
        super.onPause();

        MobclickAgent.onPause(this);

        getContentResolver().unregisterContentObserver(observer);
    }

    private void loadEarth() {
        Glide.with(this).load(EarthsContract.LATEST_CONTENT_URI
                .buildUpon()
                .appendQueryParameter("t", String.valueOf(System.currentTimeMillis()))
                .build())
                .error(R.drawable.preview)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(binding.earth.earth);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        vm.saveState(outState);

        outState.putBoolean("settings_shown", isSettingsShown());
        outState.putBoolean("in_edit_mode", isEditMode());
        outState.putBoolean("in_immersive", ImmersiveUtil.isEntered(this));
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        vm.restoreState(savedInstanceState);

        if (savedInstanceState.getBoolean("settings_shown", false)) {
            showSettings();
        }

        if (savedInstanceState.getBoolean("in_edit_mode", false)) {
            enterEditMode();
        }

        if (savedInstanceState.getBoolean("in_immersive", false)) {
            setImmersiveMode();
        }
    }

    private void toggleImmersiveMode() {
        if (ImmersiveUtil.isEntered(this)) {
            exitImmersiveMode();
        } else {
            enterImmersiveMode();
        }
    }

    private void enterImmersiveMode() {
        ImmersiveUtil.enter(this);
        InOutAnimationUtils.animateOut(binding.toolbar.getRoot(), R.anim.main_toolbar_fade_out);
    }

    private void exitImmersiveMode() {
        ImmersiveUtil.exit(this);
        InOutAnimationUtils.animateIn(binding.toolbar.getRoot(), R.anim.main_toolbar_fade_in);
    }

    private void setImmersiveMode() {
        ImmersiveUtil.enter(this);
        binding.toolbar.getRoot().setVisibility(View.INVISIBLE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.about:
                startActivity(new Intent(this, AboutActivity.class));
                return true;
            case R.id.settings:
                animateInSettings();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private boolean isCurrentWallpaper() {
        return WallpaperUtil.isCurrent(this);
    }

    private boolean isWallpaperSupported() {
        return WallpaperUtil.isSupported(this);
    }

    private boolean shouldPromptToChangeWallpaper() {
        return isWallpaperSupported() && !isCurrentWallpaper();
    }

    private boolean isFromSettings() {
        return !Intent.ACTION_MAIN.equals(getIntent().getAction());
    }

    private void promptToEnableAutoSync() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.auto_sync_disabled)
                .setMessage(R.string.auto_sync_disabled_text)
                .setPositiveButton(R.string.auto_sync_enable, (dialog, which) ->
                        ContentResolver.setMasterSyncAutomatically(true))
                .setNegativeButton(R.string.auto_sync_ignore, null)
                .show();
    }

    private void setupUpdate() {
        switch (pref.getString("update", "sync")) {
            case "sync":
                SyncUtil.disableBackground(this);
                SyncUtil.enableSync(this);
                if (!ContentResolver.getMasterSyncAutomatically()) {
                    promptToEnableAutoSync();
                }
                break;
            case "background":
                SyncUtil.disableSync(this);
                SyncUtil.enableBackground(this);
                break;
        }
    }

}
