/*
 * Enginehost integration for LÖVE for Android.
 * Copyright (c) 2026 the Enginehost contributors.
 *
 * This software is provided 'as-is', without any express or implied
 * warranty. In no event will the authors be held liable for any damages
 * arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 */

package org.love2d.android;

import android.content.Intent;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.Keep;

import org.json.JSONException;
import org.json.JSONObject;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLControllerManager;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs a LÖVE game Enginehost hands over, in place: the game folder, its
 * .love, or its fused Windows executable (love.exe with the game appended,
 * which PhysFS mounts as the zip it ends in).
 *
 * Saves: LÖVE saves under the system's application-data folder, as
 * %APPDATA%/LOVE/&lt;identity&gt; for a game love.exe runs and
 * %APPDATA%/&lt;identity&gt; for a fused one (love.filesystem.getSaveDirectory).
 * Enginehost does not change where a game saves; it makes that system
 * folder mean the save folder it hands the runtime. The engine's Android
 * branch reads ENGINEHOST_LOVE_SAVE_PARENT for that (Filesystem::setIdentity);
 * the game still picks its own identity.
 *
 * Controller: LÖVE reads the pad itself (love.gamepad, SDL's game
 * controller), so Enginehost's map for this engine is LÖVE's own button and
 * axis names and bypass is on by default. When the person turns bypass off
 * and remaps, the map arrives as CONTROLLER_BINDINGS and every pad event is
 * reported to the engine as the LÖVE button or axis it is bound to.
 */
@Keep
public final class EngineHostGameActivity extends GameActivity {
    private static final String TAG = "LOVE[Enginehost]";
    private static final String EXTRA = "dev.enginehost.runtime.";
    private static final String ERROR = "dev.enginehost.runtime.ERROR";
    private static final float DIGITAL_THRESHOLD = 0.5f;

    /** LÖVE's GamepadButton names, as the engine button each one reports (an Android key SDL maps to it). */
    private static final Map<String, Integer> BUTTONS = new HashMap<>();
    /** LÖVE's GamepadAxis names, as the Android axis SDL reads for each. */
    private static final Map<String, Integer> AXES = new HashMap<>();

    static {
        BUTTONS.put("love_a", KeyEvent.KEYCODE_BUTTON_A);
        BUTTONS.put("love_b", KeyEvent.KEYCODE_BUTTON_B);
        BUTTONS.put("love_x", KeyEvent.KEYCODE_BUTTON_X);
        BUTTONS.put("love_y", KeyEvent.KEYCODE_BUTTON_Y);
        BUTTONS.put("love_back", KeyEvent.KEYCODE_BUTTON_SELECT);
        BUTTONS.put("love_guide", KeyEvent.KEYCODE_BUTTON_MODE);
        BUTTONS.put("love_start", KeyEvent.KEYCODE_BUTTON_START);
        BUTTONS.put("love_leftstick", KeyEvent.KEYCODE_BUTTON_THUMBL);
        BUTTONS.put("love_rightstick", KeyEvent.KEYCODE_BUTTON_THUMBR);
        BUTTONS.put("love_leftshoulder", KeyEvent.KEYCODE_BUTTON_L1);
        BUTTONS.put("love_rightshoulder", KeyEvent.KEYCODE_BUTTON_R1);
        BUTTONS.put("love_dpup", KeyEvent.KEYCODE_DPAD_UP);
        BUTTONS.put("love_dpdown", KeyEvent.KEYCODE_DPAD_DOWN);
        BUTTONS.put("love_dpleft", KeyEvent.KEYCODE_DPAD_LEFT);
        BUTTONS.put("love_dpright", KeyEvent.KEYCODE_DPAD_RIGHT);
        AXES.put("love_leftx", MotionEvent.AXIS_X);
        AXES.put("love_lefty", MotionEvent.AXIS_Y);
        AXES.put("love_rightx", MotionEvent.AXIS_Z);
        AXES.put("love_righty", MotionEvent.AXIS_RZ);
        AXES.put("love_triggerleft", MotionEvent.AXIS_LTRIGGER);
        AXES.put("love_triggerright", MotionEvent.AXIS_RTRIGGER);
    }

    /** One binding from the host's map: a key, a signed axis (digital), a whole axis (analogue), or nothing. */
    private static final class Binding {
        final String type;
        final int code;
        final int direction;

        Binding(String type, int code, int direction) {
            this.type = type;
            this.code = code;
            this.direction = direction;
        }

        static Binding parse(JSONObject json) {
            String type = json.optString("type", "none");
            if ("key".equals(type)) return new Binding(type, json.optInt("code"), 0);
            if ("axis".equals(type)) return new Binding(type, json.optInt("axis"), json.optInt("direction", 0));
            return new Binding("none", 0, 0);
        }

        boolean isKey(int keyCode) {
            return "key".equals(type) && code == keyCode;
        }
    }

    private String gameFile;
    /** Null when the host sent no map: bypass, the engine reads the pad as it is. */
    private Map<String, Binding> bindings;
    private final Set<String> held = new HashSet<>();
    private final Set<Integer> keysDown = new HashSet<>();
    /** The last value read for each LÖVE axis bound to a physical axis, so a key event does not zero a held stick. */
    private final Map<String, Float> axisValues = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        Intent intent = getIntent();
        String path = intent.getStringExtra(EXTRA + "PATH");
        File folder = path == null ? null : new File(path);
        if (folder == null || !folder.isDirectory()) {
            fail(state, "Enginehost did not provide a valid game folder");
            return;
        }
        String exec = intent.getStringExtra(EXTRA + "EXEC_FILE");
        File game = exec == null || exec.isEmpty() ? folder : new File(folder, exec);
        if (!game.exists()) {
            fail(state, "The game file " + exec + " is not in " + folder.getPath());
            return;
        }
        boolean fused = game.isFile() && game.getName().toLowerCase(Locale.ROOT).endsWith(".exe");
        String save = intent.getStringExtra(EXTRA + "SAVE_PATH");
        if (save != null && !save.isEmpty()) {
            try {
                Os.setenv("ENGINEHOST_LOVE_SAVE_PARENT", fused ? save : save + "/LOVE", true);
            } catch (ErrnoException error) {
                fail(state, "Could not hand the save folder to LÖVE: " + error.getMessage());
                return;
            }
        }
        // PhysFS mounts a folder by its path with a trailing separator and an
        // archive (a .love, or the zip a fused exe ends in) by its file path.
        gameFile = game.isDirectory() ? game.getPath() + "/" : game.getPath();
        bindings = parseBindings(intent.getStringExtra(EXTRA + "CONTROLLER_BINDINGS"));
        super.onCreate(state);
    }

    @Override
    protected void handleIntent(Intent intent) {
        setEnginehostGame(gameFile);
        Log.d(TAG, "game: " + gameFile + (bindings == null ? ", pad bypassed" : ", pad remapped"));
    }

    private static Map<String, Binding> parseBindings(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(raw);
            Map<String, Binding> parsed = new HashMap<>();
            for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                String id = keys.next();
                JSONObject binding = json.optJSONObject(id);
                if (binding != null) parsed.put(id, Binding.parse(binding));
            }
            return parsed;
        } catch (JSONException error) {
            Log.w(TAG, "Unreadable controller map; the pad is left to the engine", error);
            return null;
        }
    }

    private static boolean fromPad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    /**
     * Keys go to SDL from the activity, not through view focus, which a
     * runtime hosted behind Enginehost's manifest proxy cannot rely on (the
     * EasyRPG plugin found presses logged by the input dispatcher and
     * delivered nowhere). Back stays Android's: SDL turns it into the escape
     * key LÖVE games quit on, through its own handler.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (mSurface == null || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        if (bindings != null && fromPad(event.getSource()) && keyCode != KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() > 0) return true;
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (down) keysDown.add(keyCode); else keysDown.remove(keyCode);
            int device = event.getDeviceId();
            for (Map.Entry<String, Integer> button : BUTTONS.entrySet()) {
                Binding binding = bindings.get(button.getKey());
                if (binding != null && binding.isKey(keyCode)) press(device, button.getKey(), button.getValue(), down);
            }
            reportAxes(device, null);
            return true;
        }
        if (SDLActivity.handleKeyEvent(mSurface, keyCode, event, null)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event);
        }
        if (bindings == null) {
            if (SDLControllerManager.handleJoystickMotionEvent(event)) return true;
            return super.dispatchGenericMotionEvent(event);
        }
        int device = event.getDeviceId();
        // A d-pad that reports as the hat axes presses the same keys a d-pad
        // that reports keys does, so one binding serves both kinds of pad.
        hat(device, event.getAxisValue(MotionEvent.AXIS_HAT_X), KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT);
        hat(device, event.getAxisValue(MotionEvent.AXIS_HAT_Y), KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN);
        for (Map.Entry<String, Integer> button : BUTTONS.entrySet()) {
            Binding binding = bindings.get(button.getKey());
            if (binding == null || !"axis".equals(binding.type) || binding.direction == 0) continue;
            press(device, button.getKey(), button.getValue(),
                event.getAxisValue(binding.code) * binding.direction > DIGITAL_THRESHOLD);
        }
        reportAxes(device, event);
        return true;
    }

    private void hat(int device, float value, int negative, int positive) {
        for (int key : new int[] {negative, positive}) {
            boolean down = key == negative ? value < -DIGITAL_THRESHOLD : value > DIGITAL_THRESHOLD;
            if (down == keysDown.contains(key)) continue;
            if (down) keysDown.add(key); else keysDown.remove(key);
            for (Map.Entry<String, Integer> button : BUTTONS.entrySet()) {
                Binding binding = bindings.get(button.getKey());
                if (binding != null && binding.isKey(key)) press(device, button.getKey(), button.getValue(), down);
            }
        }
    }

    /** Reports a LÖVE button once per change, so two controls bound to one button do not stutter it. */
    private void press(int device, String action, int engineKey, boolean down) {
        if (down == held.contains(action)) return;
        if (down) {
            held.add(action);
            SDLControllerManager.onNativePadDown(device, engineKey);
        } else {
            held.remove(action);
            SDLControllerManager.onNativePadUp(device, engineKey);
        }
    }

    /**
     * Reports every LÖVE axis from whatever it is bound to, as one joystick
     * event in the axis layout SDL already knows for this device: the value
     * of each engine axis is written at that axis, which is how SDL reads it.
     */
    private void reportAxes(int device, MotionEvent source) {
        MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        for (Map.Entry<String, Integer> axis : AXES.entrySet()) {
            Binding binding = bindings.get(axis.getKey());
            float value = 0f;
            if (binding != null && "axis".equals(binding.type)) {
                if (source != null) {
                    float raw = source.getAxisValue(binding.code);
                    axisValues.put(axis.getKey(), binding.direction == 0 ? raw : Math.max(0f, raw * binding.direction));
                }
                Float last = axisValues.get(axis.getKey());
                value = last == null ? 0f : last;
            } else if (binding != null && "key".equals(binding.type)) {
                value = keysDown.contains(binding.code) ? 1f : 0f;
            }
            coords[0].setAxisValue(axis.getValue(), value);
        }
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent remapped = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, properties, coords,
            0, 0, 1f, 1f, device, 0, InputDevice.SOURCE_JOYSTICK, 0);
        try {
            SDLControllerManager.handleJoystickMotionEvent(remapped);
        } finally {
            remapped.recycle();
        }
    }

    /**
     * Ends the runtime with [message] on Enginehost's launch screen. Android
     * requires onCreate to reach Activity's, so the engine's still runs, with
     * no game, on an activity that is already finishing.
     */
    private void fail(Bundle state, String message) {
        Log.e(TAG, message);
        setResult(RESULT_FIRST_USER, new Intent().putExtra(ERROR, message));
        finish();
        gameFile = "";
        super.onCreate(state);
    }
}
