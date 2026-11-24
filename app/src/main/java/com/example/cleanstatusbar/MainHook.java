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
 *  - disable the privacy green dot AND the space reserved for it, while the
 *    camera/mic pop-up chip animation still plays (same effect as the
 *    "PrivacyDotRemover" overlay: config_enablePrivacyDot=false and
 *    ongoing_appops_dot_min_padding=0dp)
 *
 * View ids checked against the SystemUI.apk pulled from the device:
 *   mobile_in, mobile_out, wifi_in, wifi_out, inout_container
 *   mobile_volte, volte, vonr
 */
public class MainHook extends XposedModule {

    // Switch features on/off here
    private static final boolean HIDE_DATA_ARROWS = true;
    private static final boolean HIDE_VOLTE = true;
    // Same as the reference overlay: no resting green dot, no reserved space at the top right
    private static final boolean DISABLE_PRIVACY_DOT = true;
    // Also hide the pop-up chip (camera/mic icon pill). Off = it still pops up.
    private static final boolean HIDE_PRIVACY_CHIP = false;

    private static final String TAG = "CleanStatusBar";
    private static final String SYSTEMUI = "com.android.systemui";

    // view id names forced to GONE
    private static final Set<String> HIDDEN = new HashSet<>();
    // dimen names forced to 0
    private static final Set<String> ZERO_DIMENS = new HashSet<>();
    // bool names forced to false
    private static final Set<String> FALSE_BOOLS = new HashSet<>();

    static {
        if (HIDE_DATA_ARROWS) {
            HIDDEN.addAll(Arrays.asList(
                    "mobile_in", "mobile_out", "wifi_in", "wifi_out", "inout_container"));
        }
        if (HIDE_VOLTE) {
            HIDDEN.addAll(Arrays.asList("mobile_volte", "volte", "vonr"));
        }
        if (HIDE_PRIVACY_CHIP) {
            HIDDEN.add("privacy_chip");
        }
        if (DISABLE_PRIVACY_DOT) {
            FALSE_BOOLS.add("config_enablePrivacyDot");
            ZERO_DIMENS.add("ongoing_appops_dot_min_padding");
        }
    }

    private final ConcurrentHashMap<Integer, Boolean> viewCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Boolean> dimenCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Boolean> boolCache = new ConcurrentHashMap<>();
    private final AtomicBoolean installed = new AtomicBoolean(false);

    private boolean shouldHide(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return false;
        Boolean c = viewCache.get(id);
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
        viewCache.put(id, hide);
        return hide;
    }

    /** True if resource id is a SystemUI resource of the given type whose name is in names. */
    private boolean matchRes(Resources r, int id, String type, Set<String> names,
                             ConcurrentHashMap<Integer, Boolean> cache) {
        if (id == 0) return false;
        Boolean c = cache.get(id);
        if (c != null) return c;
        boolean match = false;
        try {
            if (SYSTEMUI.equals(r.getResourcePackageName(id))
                    && type.equals(r.getResourceTypeName(id))
                    && names.contains(r.getResourceEntryName(id))) {
                match = true;
                log(Log.INFO, TAG, "overriding " + type + ": " + r.getResourceEntryName(id));
            }
        } catch (Throwable ignored) {
        }
        cache.put(id, match);
        return match;
    }

    private static int argId(io.github.libxposed.api.XposedInterface.Chain chain) {
        return ((Number) chain.getArgs().get(0)).intValue();
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!SYSTEMUI.equals(param.getPackageName())) return;
        if (!installed.compareAndSet(false, true)) return;

        // Any visibility change on a target view becomes GONE
        if (!HIDDEN.isEmpty()) {
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
        }

        // Resource overrides (same effect as a SystemUI RRO overlay)
        if (!ZERO_DIMENS.isEmpty()) {
            try {
                hook(Resources.class.getDeclaredMethod("getDimensionPixelSize", int.class))
                        .intercept(chain -> {
                            Object self = chain.getThisObject();
                            if (self instanceof Resources && matchRes((Resources) self,
                                    argId(chain), "dimen", ZERO_DIMENS, dimenCache)) {
                                return 0;
                            }
                            return chain.proceed();
                        });
                hook(Resources.class.getDeclaredMethod("getDimensionPixelOffset", int.class))
                        .intercept(chain -> {
                            Object self = chain.getThisObject();
                            if (self instanceof Resources && matchRes((Resources) self,
                                    argId(chain), "dimen", ZERO_DIMENS, dimenCache)) {
                                return 0;
                            }
                            return chain.proceed();
                        });
                hook(Resources.class.getDeclaredMethod("getDimension", int.class))
                        .intercept(chain -> {
                            Object self = chain.getThisObject();
                            if (self instanceof Resources && matchRes((Resources) self,
                                    argId(chain), "dimen", ZERO_DIMENS, dimenCache)) {
                                return 0f;
                            }
                            return chain.proceed();
                        });
                log(Log.INFO, TAG, "hooked Resources dimen getters");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "dimen hook failed", t);
            }
        }

        if (!FALSE_BOOLS.isEmpty()) {
            try {
                hook(Resources.class.getDeclaredMethod("getBoolean", int.class))
                        .intercept(chain -> {
                            Object self = chain.getThisObject();
                            if (self instanceof Resources && matchRes((Resources) self,
                                    argId(chain), "bool", FALSE_BOOLS, boolCache)) {
                                return Boolean.FALSE;
                            }
                            return chain.proceed();
                        });
                log(Log.INFO, TAG, "hooked Resources.getBoolean");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "bool hook failed", t);
            }
        }
    }
}
