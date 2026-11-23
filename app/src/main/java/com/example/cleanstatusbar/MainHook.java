package com.example.cleanstatusbar;

import android.content.res.Resources;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 module for SystemUI (Xperia 1 VI, Android 16):
 *  - hide up/down data arrows (mobile + Wi-Fi)
 *  - hide VoLTE / VoNR icon in the mobile signal group
 *  - hide the privacy green dot
 *
 * View ids checked against the SystemUI.apk pulled from the device:
 *   mobile_in, mobile_out, wifi_in, wifi_out, inout_container
 *   mobile_volte, volte, vonr
 *   privacy_dot
 *
 * Forces View.setVisibility(GONE) for views with those id names,
 * inside the SystemUI process only.
 */
public class MainHook extends XposedModule {

    // Switch features on/off here
    private static final boolean HIDE_DATA_ARROWS = true;
    private static final boolean HIDE_VOLTE = true;
    private static final boolean HIDE_PRIVACY_DOT = true;
    // Also remove the empty space reserved at the right end of the status bar for the dot
    private static final boolean REMOVE_PRIVACY_DOT_SPACE = true;
    private static final boolean HIDE_PRIVACY_CHIP = true;

    private static final String TAG = "NoDataArrows";

    private static final Set<String> HIDDEN = new HashSet<>();

    static {
        if (HIDE_DATA_ARROWS) {
            HIDDEN.addAll(Arrays.asList(
                    "mobile_in", "mobile_out", "wifi_in", "wifi_out", "inout_container"));
        }
        if (HIDE_VOLTE) {
            HIDDEN.addAll(Arrays.asList("mobile_volte", "volte", "vonr"));
        }
        if (HIDE_PRIVACY_DOT) {
            HIDDEN.add("privacy_dot");
        }
        if (HIDE_PRIVACY_CHIP) {
            HIDDEN.add("privacy_chip");
        }
    }

    // dimens SystemUI uses to reserve room for the privacy dot (6dp + 20dp)
    private static final Set<String> ZERO_DIMENS = new HashSet<>(Arrays.asList(
            "ongoing_appops_dot_diameter", "ongoing_appops_dot_min_padding"));

    private final ConcurrentHashMap<Integer, Boolean> dimenCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Boolean> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean installed = new AtomicBoolean(false);

    private boolean shouldHide(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return false;
        Boolean c = cache.get(id);
        if (c != null) return c;
        boolean hide = false;
        try {
            String n = v.getResources().getResourceEntryName(id);
            if (n != null && HIDDEN.contains(n)) {
                hide = true;
                log(Log.INFO, TAG, "hiding view id: " + n);
            }
        } catch (Throwable ignored) {
            // id not resolvable in this Resources, not one of ours
        }
        cache.put(id, hide);
        return hide;
    }

    private boolean isZeroDimen(Resources r, int id) {
        if (id == 0) return false;
        Boolean c = dimenCache.get(id);
        if (c != null) return c;
        boolean zero = false;
        try {
            if ("com.android.systemui".equals(r.getResourcePackageName(id))
                    && "dimen".equals(r.getResourceTypeName(id))
                    && ZERO_DIMENS.contains(r.getResourceEntryName(id))) {
                zero = true;
                log(Log.INFO, TAG, "zeroing dimen: " + r.getResourceEntryName(id));
            }
        } catch (Throwable ignored) {
        }
        dimenCache.put(id, zero);
        return zero;
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!"com.android.systemui".equals(param.getPackageName())) return;
        if (!installed.compareAndSet(false, true)) return;

        // Any visibility change on a target view becomes GONE
        try {
            Method setVisibility = View.class.getDeclaredMethod("setVisibility", int.class);
            hook(setVisibility).intercept(chain -> {
                Object self = chain.getThisObject();
                if (self instanceof View && shouldHide((View) self)) {
                    return chain.proceed(new Object[]{View.GONE});
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "hooked View.setVisibility");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "setVisibility hook failed", t);
        }

        // Also hide target views that start visible from XML and are never toggled
        try {
            for (Method m : View.class.getDeclaredMethods()) {
                if (!m.getName().equals("dispatchAttachedToWindow")) continue;
                hook(m).intercept(chain -> {
                    Object r = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof View) {
                        View v = (View) self;
                        if (shouldHide(v) && v.getVisibility() != View.GONE) {
                            v.setVisibility(View.GONE);
                        }
                    }
                    return r;
                });
            }
            log(Log.INFO, TAG, "hooked View.dispatchAttachedToWindow");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "attach hook failed", t);
        }

        // Remove the space reserved for the privacy dot at the status bar end
        if (HIDE_PRIVACY_DOT && REMOVE_PRIVACY_DOT_SPACE) {
            try {
                hook(Resources.class.getDeclaredMethod("getDimensionPixelSize", int.class))
                        .intercept(chain -> {
                            Object self = chain.getThisObject();
                            if (self instanceof Resources && isZeroDimen((Resources) self,
                                    ((Number) chain.getArgs().get(0)).intValue())) {
                                return 0;
                            }
                            return chain.proceed();
                        });
                hook(Resources.class.getDeclaredMethod("getDimensionPixelOffset", int.class))
                        .intercept(chain -> {
                            Object self = chain.getThisObject();
                            if (self instanceof Resources && isZeroDimen((Resources) self,
                                    ((Number) chain.getArgs().get(0)).intValue())) {
                                return 0;
                            }
                            return chain.proceed();
                        });
                hook(Resources.class.getDeclaredMethod("getDimension", int.class))
                        .intercept(chain -> {
                            Object self = chain.getThisObject();
                            if (self instanceof Resources && isZeroDimen((Resources) self,
                                    ((Number) chain.getArgs().get(0)).intValue())) {
                                return 0f;
                            }
                            return chain.proceed();
                        });
                log(Log.INFO, TAG, "hooked Resources dimen getters");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "dimen hook failed", t);
            }
        }
    }
}
