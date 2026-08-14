package com.sbplus.browser;

import android.view.MenuItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Show/hide (add/remove) icons in Samsung Internet's "More" (⋮) grid menu.
 *
 * Data model (reverse-engineered):
 *  - CustomizeMenuModel.getAllMenus()  -> the full icon library (every possible item).
 *  - getAllMenus() minus the currently-available list = the "addable" (hidden) icons.
 *  - Adding  = MenuReorderHelper.addItem(item)     (checked=true, appended, saved)
 *  - Removing = MenuReorderHelper.removeItem(item)  (checked=false, dropped, saved)
 *
 * Persistence reuses Samsung's CustomizeToolbarManager.saveToolsMenu(list).
 */
public final class MenuEditHelper {

    private MenuEditHelper() {}

    private static ClassLoader sCl;

    public static void setClassLoader(ClassLoader cl) { sCl = cl; }

    private static Object currentModel() {
        try {
            Class<?> mgrCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.customize_toolbar.CustomizeToolbarManager", sCl);
            Object mgr = XposedHelpers.callStaticMethod(mgrCls, "getInstance");
            return XposedHelpers.callMethod(mgr, "getCurrentInstanceModel");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] currentModel error: " + t);
            return null;
        }
    }

    private static List<MenuItem> allMenus() {
        Object model = currentModel();
        if (model == null) return new ArrayList<>();
        Object r = XposedHelpers.callMethod(model, "getAllMenus");
        return r == null ? new ArrayList<MenuItem>() : (List<MenuItem>) r;
    }

    private static List<MenuItem> availableMenus() {
        Object model = currentModel();
        if (model == null) return new ArrayList<>();
        Object r = XposedHelpers.callMethod(model, "getToolsAvailableMenus");
        return r == null ? new ArrayList<MenuItem>() : (List<MenuItem>) r;
    }

    /** Icons currently not shown (never added, or added but hidden). Sorted by title. */
    public static List<MenuItem> getAddableMenus() {
        try {
            List<MenuItem> all = allMenus();
            List<MenuItem> avail = availableMenus();
            HashSet<Integer> availIds = new HashSet<>();
            if (avail != null) for (MenuItem m : avail) availIds.add(m.getItemId());
            ArrayList<MenuItem> result = new ArrayList<>();
            if (all != null) {
                for (MenuItem m : all) {
                    if (m == null) continue;
                    boolean present = availIds.contains(m.getItemId());
                    if (!present) result.add(m);      // never added -> addable
                    else if (!m.isChecked()) result.add(m); // added but hidden -> addable
                }
            }
            return result;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] getAddableMenus error: " + t);
            return new ArrayList<>();
        }
    }

    // ---------------------------------------------------------------------------------
    // Grid "+" add-item (appended as the last grid cell, not draggable/removable).
    // ---------------------------------------------------------------------------------

    private static volatile boolean sAddItemHooked = false;
    private static volatile ClassLoader sAddItemCl = null;
    private static volatile int sIconCount = -1;
    private static volatile int sPadCount = 0;
    /** Hook adapter getItemCount/getItem/onBindViewHolder to render a trailing "+" cell. */
    public static void installGridAddItem(Object adapter, ClassLoader cl) {
        if (adapter == null) return;
        if (sAddItemHooked && sAddItemCl == cl) return;
        try {
            final Class<?> adapterCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.toolbar.MoreMenuRecyclerAdapter", cl);

            // getItemCount() -> icons + "+". No trailing blank fillers (paging disabled).
            XposedHelpers.findAndHookMethod(adapterCls, "getItemCount",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        int icons = (Integer) p.getResult();
                        sIconCount = icons;
                        sPadCount = 0;
                        p.setResult(icons + 1); // icons + the "+" cell
                    }
                });

            // onCreateViewHolder(ViewGroup,I) after -> set a precise column width BEFORE the
            // LayoutManager measures the item, so match_parent never resolves to the content
            // width (which breaks 5-columns-per-page alignment).
            try {
                XposedHelpers.findAndHookMethod(adapterCls, "onCreateViewHolder",
                        XposedHelpers.findClass("android.view.ViewGroup", cl), int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            Object holder = p.getResult();
                            if (holder == null) return;
                            android.view.View iv = (android.view.View) XposedHelpers.getObjectField(holder, "itemView");
                            if (iv == null) return;
                            try {
                                int screenW = iv.getResources().getDisplayMetrics().widthPixels;
                                int itemW = screenW / 5;
                                android.view.ViewGroup.LayoutParams lp = iv.getLayoutParams();
                                if (lp == null) {
                                    lp = new android.view.ViewGroup.LayoutParams(itemW,
                                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                                } else {
                                    lp.width = itemW;
                                }
                                iv.setLayoutParams(lp);
                            } catch (Throwable ignore) {}
                        }
                    });
            } catch (Throwable t) {
                XposedBridge.log("[SBPlus] onCreateViewHolder width hook failed: " + t);
            }

            // getItem(I) -> return null for the "+" cell and blank fillers (no backing MenuItem).
            XposedHelpers.findAndHookMethod(adapterCls, "getItem", int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        int pos = (Integer) p.args[0];
                        if (sIconCount >= 0 && pos >= sIconCount) {
                            p.setResult(null); // "+" or filler -> no backing MenuItem
                        }
                    }
                });

            // onBindViewHolder(g1, I) after -> style the add cell, blank the fillers.
            XposedHelpers.findAndHookMethod(adapterCls, "onBindViewHolder",
                    XposedHelpers.findClass("androidx.recyclerview.widget.g1", cl),
                    int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        try {
                            int pos = (Integer) p.args[1];
                            Object holder = p.args[0];
                            int icons = sIconCount;
                            int pad = sPadCount;

                            // The "+" cell sits immediately after the last icon.
                            if (pos == icons) {
                                android.widget.ImageView icon =
                                        (android.widget.ImageView) XposedHelpers.getObjectField(holder, "mIcon");
                                android.widget.TextView text =
                                        (android.widget.TextView) XposedHelpers.getObjectField(holder, "mText");
                                if (icon != null) {
                                    icon.setVisibility(android.view.View.VISIBLE);
                                    icon.setImageResource(android.R.drawable.ic_input_add);
                                    icon.setColorFilter(android.graphics.Color.rgb(0xDD, 0xDD, 0xDD));
                                }
                                if (text != null) {
                                    text.setVisibility(android.view.View.VISIBLE);
                                    text.setText("添加");
                                }
                                android.view.View badge = (android.view.View) XposedHelpers.getObjectField(holder, "mBadge");
                                if (badge != null) badge.setVisibility(android.view.View.GONE);
                                android.view.View div = (android.view.View) XposedHelpers.getObjectField(holder, "mDivider");
                                if (div != null) div.setVisibility(android.view.View.GONE);
                                final android.view.View itemView = (android.view.View) XposedHelpers.getObjectField(holder, "itemView");
                                if (itemView != null) {
                                    itemView.setClickable(true);
                                    itemView.setOnClickListener(new android.view.View.OnClickListener() {
                                        @Override public void onClick(android.view.View v) {
                                            XposedBridge.log("[SBPlus] add cell CLICKED");
                                            android.content.Context ctx = itemView.getContext();
                                            MenuAddButtonHelper.showAddDialog(ctx);
                                        }
                                    });
                                }
                                XposedBridge.log("[SBPlus] add cell rendered at pos " + pos
                                        + " (icons=" + icons + " pad=" + pad + ")");
                                return;
                            }

                            // Blank filler cells AFTER the "+" (pad the tail to a full page).
                            if (icons >= 0 && pos > icons) {
                                XposedBridge.log("[SBPlus] blank filler at pos " + pos
                                        + " (icons=" + icons + " pad=" + pad + ")");
                                android.widget.ImageView icon =
                                        (android.widget.ImageView) XposedHelpers.getObjectField(holder, "mIcon");
                                android.widget.TextView text =
                                        (android.widget.TextView) XposedHelpers.getObjectField(holder, "mText");
                                if (icon != null) icon.setVisibility(android.view.View.INVISIBLE);
                                if (text != null) text.setVisibility(android.view.View.INVISIBLE);
                                android.view.View badge = (android.view.View) XposedHelpers.getObjectField(holder, "mBadge");
                                if (badge != null) badge.setVisibility(android.view.View.GONE);
                                android.view.View div = (android.view.View) XposedHelpers.getObjectField(holder, "mDivider");
                                if (div != null) div.setVisibility(android.view.View.GONE);
                                return;
                            }

                            // Real icon cell: restore icons hidden by a recycled blank-filler
                            // holder, then paint the ✕ mark in edit mode.
                            android.widget.ImageView ric =
                                    (android.widget.ImageView) XposedHelpers.getObjectField(holder, "mIcon");
                            android.widget.TextView rtx =
                                    (android.widget.TextView) XposedHelpers.getObjectField(holder, "mText");
                            if (ric != null) ric.setVisibility(android.view.View.VISIBLE);
                            if (rtx != null) rtx.setVisibility(android.view.View.VISIBLE);
                            android.view.View rbd = (android.view.View) XposedHelpers.getObjectField(holder, "mBadge");
                            if (rbd != null) rbd.setVisibility(android.view.View.VISIBLE);
                            MenuReorderHelper.decorateBoundItem(holder, pos);
                        } catch (Throwable t) {
                            XposedBridge.log("[SBPlus] add cell bind error: " + t);
                        }
                    }
                });

            sAddItemHooked = true;
            sAddItemCl = cl;
            XposedBridge.log("[SBPlus] grid add-item hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] installGridAddItem error: " + t);
        }
    }
}
