package com.sbplus.browser;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Long-press-to-reorder + show/hide for Samsung Internet's "More" (⋮) grid menu.
 *
 * Samsung already persists menu order + checked (show/hide) via its customize-toolbar
 * subsystem (CustomizeToolbarManager / CustomizeMenuModel / CustomizeMenuHelper). We reuse
 * that persistence and only add the missing in-place long-press → drag gesture.
 *
 * The drag is implemented through RecyclerView.OnItemTouchListener (obfuscated to "M0"),
 * which is an *interface* and therefore safe to Proxy (unlike the abstract Snap /
 * ScrollListener classes Samsung kept). add/removeOnItemTouchListener are un-obfuscated.
 */
public final class MenuReorderHelper {

    private MenuReorderHelper() {}

    private static ClassLoader sCl;

    private static volatile Object sRecycler;   // RecyclerView
    private static volatile Object sAdapter;    // MoreMenuRecyclerAdapter
    private static volatile Object sHandler;    // MoreMenuHandler (for mMenu + save)
    private static volatile List<MenuItem> sItems;   // flattened reorderable list
    private static volatile List<MenuItem> sCachedPrimary;
    private static volatile List<MenuItem> sCachedSecondary;

    private static volatile Object sTouchProxy;
    private static volatile Object sProxiedRecycler;

    // gesture state
    private static volatile boolean sEditMode = false;
    private static volatile boolean sDragging = false;
    private static volatile long sDownTime;
    private static volatile float sDownX, sDownY;
    private static volatile int sDragPos = -1;
    private static volatile int sDragFrom = -1;
    private static volatile View sDragView = null;

    private static final int EDGE_SCROLL_MAX = 70;        // max scroll px per frame (fastest)
    private static final int EDGE_SCROLL_MIN = 28;        // min scroll px per frame
    private static final int EDGE_ZONE_PX = 48;           // fixed px near the true edge
    private static final Runnable[] sScrollTicker = new Runnable[1];
    private static volatile Float sLastMoveRawX = null;
    private static volatile Float sLastMoveRawY = null;
    private static volatile float sTouchOffsetX = 0f;
    private static volatile float sTouchOffsetY = 0f;

    public static boolean isEditMode() { return sEditMode; }

    public static void setClassLoader(ClassLoader cl) { sCl = cl; }

    public static void cacheRefs(Object handler, Object recycler, Object adapter,
                                 List<MenuItem> primary, List<MenuItem> secondary,
                                 List<MenuItem> removed) {
        sHandler = handler;
        sRecycler = recycler;
        sAdapter = adapter;
        sCachedPrimary = primary;
        sCachedSecondary = secondary;
        sEditMode = false;
        sDragging = false;
        sItems = null;
        sDragView = null;
    }

    /** Always-on touch proxy. It only acts while in edit mode; otherwise lets Samsung handle. */
    public static void installTouchProxy() {
        // Re-install only when the RecyclerView instance changed (the menu sheet is rebuilt on
        // every open, so a new RecyclerView needs a fresh OnItemTouchListener).
        if (sRecycler == null || sCl == null || sRecycler == sProxiedRecycler) return;
        try {
            Object recycler = sRecycler;
            Class<?> m0 = XposedHelpers.findClass("androidx.recyclerview.widget.M0", sCl);
            if (m0 == null || !m0.isInterface()) {
                XposedBridge.log("[SBPlus] M0 not an interface, reorder disabled");
                return;
            }
            java.lang.reflect.InvocationHandler h = new java.lang.reflect.InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("onInterceptTouchEvent".equals(name)) {
                        return onIntercept((MotionEvent) args[1]);
                    } else if ("onTouchEvent".equals(name)) {
                        onTouch((MotionEvent) args[1]);
                        return null;
                    } else if ("onRequestDisallowInterceptTouchEvent".equals(name)) {
                        return null;
                    }
                    if ("toString".equals(name)) return "SBPlusReorder";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == args[0];
                    return null;
                }
            };
            sTouchProxy = Proxy.newProxyInstance(sCl, new Class<?>[]{m0}, h);
            XposedHelpers.callMethod(recycler, "addOnItemTouchListener", sTouchProxy);
            sProxiedRecycler = recycler;
            XposedBridge.log("[SBPlus] reorder touch proxy installed on " + recycler);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] installTouchProxy error: " + t);
        }
    }

    private static boolean onIntercept(MotionEvent ev) {
        int action = ev.getActionMasked();
        // While dragging, claim the whole stream (so the icon follows the finger).
        if (sDragging) return true;

        if (action == MotionEvent.ACTION_DOWN) {
            sDownTime = android.os.SystemClock.uptimeMillis();
            sDownX = ev.getRawX();
            sDownY = ev.getRawY();
            sDragPos = -1;
            return false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            sDownTime = 0;
            sDragPos = -1;
            if (action == MotionEvent.ACTION_UP) {
                float rx = ev.getRawX();
                float ry = ev.getRawY();
                ensureFlatItems();
                // Tap on the trailing "+" add cell -> open the add dialog.
                int addPos = (sItems != null) ? sItems.size() : -1;
                if (addPos >= 0 && isTapOnPosition(rx, ry, addPos)) {
                    XposedBridge.log("[SBPlus] add cell tapped -> show add dialog");
                    MenuAddButtonHelper.showAddDialog(((View) sRecycler).getContext());
                    return false;
                }
                // In edit mode, a simple tap should NOT reach the item's onClick. Intercept it
                // and route to our own ✕ / empty-space handling.
                if (sEditMode) {
                    int underRaw = positionUnderRaw(rx, ry);
                    XposedBridge.log("[SBPlus] UP in edit: underRaw=" + underRaw
                            + " hasMark=" + (hitRemoveMark(rx, ry) != null));
                    View mark = hitRemoveMark(rx, ry);
                    if (mark != null) {
                        Integer p = (Integer) mark.getTag();
                        if (p != null && sItems != null && p >= 0 && p < sItems.size()) {
                            MenuItem item = sItems.get(p);
                            removeItem(item);
                            showDeleteMarks();
                        }
                    } else if (underRaw < 0) {
                        XposedBridge.log("[SBPlus] tap empty -> exit edit mode");
                        exitEditModeAndSave();
                    }
                    return true; // swallow the tap so the item's original onClick never fires
                }
            }
            return false;
        }
        // ACTION_MOVE: long-press detection (works in both IDLE and EDIT states).
        float dx = ev.getRawX() - sDownX;
        float dy = ev.getRawY() - sDownY;
        if (sDownTime != 0 && android.os.SystemClock.uptimeMillis() - sDownTime > 500L
                && dx * dx + dy * dy < 2500f) {
            int under = positionUnder(ev);
            XposedBridge.log("[SBPlus] long-press candidate, under=" + under + " edit=" + sEditMode);
            if (under >= 0 && !isAddCell(under)) {
                // Long-press on a real icon -> enter edit mode and begin dragging this icon.
                enterEditMode();
                sDragging = true;
                sDragPos = under;
                sDragFrom = under;
                XposedBridge.log("[SBPlus] long-press -> drag from " + sDragPos);
                if (sRecycler instanceof View) {
                    ((View) sRecycler).performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS);
                }
                startDragVisual(ev);
                return true;
            }
        }
        return false;
    }

    private static void onTouch(MotionEvent ev) {
        try {
            if (sDragging) {
                handleTouch(ev);
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] reorder onTouch error: " + t);
        }
    }

    /** Handle taps while in edit mode (not dragging): ✕ marks or empty-space to exit. */
    private static void handleTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                sLastMoveRawX = ev.getRawX();
                sLastMoveRawY = ev.getRawY();
                int to = positionUnder(ev);
                if (to >= 0) sDragPos = to;
                updateDragVisual(ev);
                handleEdgeScroll(ev);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopEdgeScroll();
                if (sDragging) {
                    sDragging = false;
                    int from = sDragFrom, to = sDragPos;
                    sDragPos = sDragFrom = -1;
                    endDragVisual();
                    applyMove(from, to);
                    // Persist the order immediately so it survives even if the user closes the
                    // menu (e.g. taps outside the sheet) without going through exitEditModeAndSave.
                    persistOrderNow();
                    // Stay in edit mode so the user can tap ✕ to remove, or tap empty to exit.
                    XposedBridge.log("[SBPlus] drag finished, order persisted, still in edit mode");
                }
                break;
        }
    }

    private static void enterEditMode() {
        if (sEditMode) return;
        ArrayList<MenuItem> flat = new ArrayList<>();
        if (sCachedPrimary != null) flat.addAll(sCachedPrimary);
        if (sCachedSecondary != null) flat.addAll(sCachedSecondary);
        sItems = flat;
        sEditMode = true;
        XposedBridge.log("[SBPlus] enter edit mode, items=" + flat.size());
        // Paint the ✕ marks after layout settles so icon coordinates are correct.
        if (sRecycler instanceof View) {
            final View rv = (View) sRecycler;
            rv.post(new Runnable() {
                @Override public void run() {
                    if (!sEditMode) return;
                    showDeleteMarks();
                    disableIconsForEdit();
                    // Second pass after any page-settle/lazy layout finishes, so items that
                    // were still laying out on the first pass also get their ✕.
                    rv.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (!sEditMode) return;
                            showDeleteMarks();
                            disableIconsForEdit();
                        }
                    }, 350);
                }
            });
        }
    }

    /** While editing, block every clickable descendant so taps don't trigger menu actions. */
    private static void disableIconsForEdit() {
        try {
            if (!(sRecycler instanceof View)) return;
            int childCount = ((Number) XposedHelpers.callMethod(sRecycler, "getChildCount")).intValue();
            for (int i = 0; i < childCount; i++) {
                View child = (View) XposedHelpers.callMethod(sRecycler, "getChildAt", i);
                if (child == null) continue;
                disableClickTree(child);
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] disableIconsForEdit error: " + t);
        }
    }

    private static void disableClickTree(View v) {
        if (v == null) return;
        if (v.isClickable()) {
            v.setTag(0x7f0f0001, Boolean.TRUE);
            v.setClickable(false);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                disableClickTree(vg.getChildAt(i));
            }
        }
    }

    private static void enableIconsAfterEdit() {
        try {
            if (!(sRecycler instanceof View)) return;
            int childCount = ((Number) XposedHelpers.callMethod(sRecycler, "getChildCount")).intValue();
            for (int i = 0; i < childCount; i++) {
                View child = (View) XposedHelpers.callMethod(sRecycler, "getChildAt", i);
                if (child == null) continue;
                restoreClickTree(child);
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] enableIconsAfterEdit error: " + t);
        }
    }

    private static void restoreClickTree(View v) {
        if (v == null) return;
        if (Boolean.TRUE.equals(v.getTag(0x7f0f0001))) {
            v.setClickable(true);
            v.setTag(0x7f0f0001, null);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                restoreClickTree(vg.getChildAt(i));
            }
        }
    }

    /** True if the adapter position is the trailing "+" add cell (not a real icon). */
    private static boolean isAddCell(int pos) {
        return sItems != null && pos == sItems.size();
    }

    /** True if the raw coords land on the item at the given adapter position. */
    private static boolean isTapOnPosition(float rawX, float rawY, int pos) {
        try {
            if (!(sRecycler instanceof View)) return false;
            int childCount = ((Number) XposedHelpers.callMethod(sRecycler, "getChildCount")).intValue();
            for (int i = 0; i < childCount; i++) {
                View child = (View) XposedHelpers.callMethod(sRecycler, "getChildAt", i);
                if (child == null) continue;
                int lp = ((Number) XposedHelpers.callMethod(sRecycler, "getChildLayoutPosition", child)).intValue();
                if (lp != pos) continue;
                int[] loc = new int[2];
                child.getLocationOnScreen(loc);
                return rawX >= loc[0] && rawX <= loc[0] + child.getWidth()
                        && rawY >= loc[1] && rawY <= loc[1] + child.getHeight();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] isTapOnPosition error: " + t);
        }
        return false;
    }

    private static void exitEditModeAndSave() {
        if (!sEditMode) return;
        hideDeleteMarks();
        enableIconsAfterEdit();
        sEditMode = false;
        saveOrder();
        XposedBridge.log("[SBPlus] exit edit mode, order saved");
    }

    /** Map a raw screen coordinate to a flat adapter position (5 cols x 2 rows paging grid). */
    private static int positionUnder(MotionEvent ev) {
        return positionUnderRaw(ev.getRawX(), ev.getRawY());
    }

    private static int positionUnderRaw(float rawX, float rawY) {
        try {
            if (!(sRecycler instanceof View)) return -1;
            ensureFlatItems();
            if (sItems == null || sItems.isEmpty()) return -1;
            View rv = (View) sRecycler;
            int total = sItems.size();
            // Preferred: hit-test the actual visible children and read their layout position.
            int childCount = ((Number) XposedHelpers.callMethod(sRecycler, "getChildCount")).intValue();
            for (int i = 0; i < childCount; i++) {
                View child = (View) XposedHelpers.callMethod(sRecycler, "getChildAt", i);
                if (child == null) continue;
                // Skip the item currently being dragged: its translation follows the finger,
                // so it would always hit-test as its original (from) slot and mask the target.
                if (child == sDragView) continue;
                int[] loc = new int[2];
                child.getLocationOnScreen(loc);
                float cx = rawX;
                float cy = rawY;
                if (cx >= loc[0] && cx <= loc[0] + child.getWidth()
                        && cy >= loc[1] && cy <= loc[1] + child.getHeight()) {
                    int pos = ((Number) XposedHelpers.callMethod(sRecycler, "getChildLayoutPosition", child)).intValue();
                    if (pos >= 0 && pos < total) return pos;
                }
            }
            // Fallback: geometric estimate.
            int cols = 5, rows = 2;
            int pageW = rv.getWidth();
            if (pageW <= 0) return -1;
            int itemW = pageW / cols;
            int itemH = 0;
            View child0 = (View) XposedHelpers.callMethod(sRecycler, "getChildAt", 0);
            if (child0 != null) itemH = child0.getHeight();
            if (itemH <= 0) itemH = (int) (76f * rv.getResources().getDisplayMetrics().density);
            int[] gloc = new int[2];
            rv.getLocationOnScreen(gloc);
            float relX = rawX - gloc[0];
            float relY = rawY - gloc[1];
            int scrollX = 0;
            try {
                scrollX = ((Number) XposedHelpers.callMethod(sRecycler, "computeHorizontalScrollOffset")).intValue();
            } catch (Throwable ignored) {}
            int page = (scrollX + (int) relX) / pageW;
            int withinPage = (scrollX + (int) relX) % pageW;
            int col = withinPage / itemW;
            int row = (int) (relY / itemH);
            // Taps above the grid (relY<0) or below it (row>=rows) are empty space, not an item.
            // Return -1 so callers can treat it as "tap outside -> exit edit mode".
            if (relY < 0f || row >= rows) return -1;
            if (col < 0) col = 0; else if (col >= cols) col = cols - 1;
            int pos = page * (cols * rows) + row * cols + col;
            if (pos < 0) pos = 0; else if (pos >= total) pos = total - 1;
            return pos;
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] positionUnder error: " + t);
            return -1;
        }
    }

    private static void applyMove(int from, int to) {
        if (sItems == null || sAdapter == null) return;
        if (from < 0 || from >= sItems.size() || to < 0 || to >= sItems.size()) return;
        if (from == to) return;
        try {
            // Swap semantics: the two icons exchange places directly (not insert-shift).
            MenuItem a = sItems.get(from);
            MenuItem b = sItems.get(to);
            sItems.set(from, b);
            sItems.set(to, a);
            XposedBridge.log("[SBPlus] applyMove (swap) " + from + " <-> " + to);
            // Push the swapped order into the handler's live list (the adapter's real source)
            // BEFORE repainting, otherwise notifyItemChanged shows stale data.
            syncHandlerLists();
            XposedHelpers.callMethod(sAdapter, "notifyItemChanged", from);
            XposedHelpers.callMethod(sAdapter, "notifyItemChanged", to);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] applyMove error: " + t);
        }
    }

    /** Find the item view for a layout position without disturbing the RecyclerView. */
    private static View findItemView(int pos) {
        try {
            int childCount = ((Number) XposedHelpers.callMethod(sRecycler, "getChildCount")).intValue();
            for (int i = 0; i < childCount; i++) {
                View child = (View) XposedHelpers.callMethod(sRecycler, "getChildAt", i);
                int lp = ((Number) XposedHelpers.callMethod(sRecycler, "getChildLayoutPosition", child)).intValue();
                if (lp == pos) return child;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Lift the dragged item (elevation + slight scale) so the user sees it is being held. */
    private static void startDragVisual(MotionEvent ev) {
        try {
            sDragView = findItemView(sDragFrom);
            if (sDragView == null) return;
            // Record where inside the item the finger pressed, so the item stays anchored
            // under the finger (centered) while dragging instead of snapping to its top-left.
            int[] loc = new int[2];
            sDragView.getLocationOnScreen(loc);
            sTouchOffsetX = ev.getRawX() - loc[0];
            sTouchOffsetY = ev.getRawY() - loc[1];
            sDragView.setElevation(24f);
            sDragView.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start();
            updateDragVisual(ev);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] startDragVisual error: " + t);
        }
    }

    /** Make the dragged item follow the finger via translation. */
    private static void updateDragVisual(MotionEvent ev) {
        updateDragVisualRaw(ev.getRawX(), ev.getRawY());
    }

    private static void updateDragVisualRaw(float rawX, float rawY) {
        if (sDragView == null) return;
        try {
            int[] parentLoc = new int[2];
            if (sRecycler instanceof View) ((View) sRecycler).getLocationOnScreen(parentLoc);
            float dx = rawX - (parentLoc[0] + sDragView.getLeft() + sTouchOffsetX);
            float dy = rawY - (parentLoc[1] + sDragView.getTop() + sTouchOffsetY);
            sDragView.setTranslationX(dx);
            sDragView.setTranslationY(dy);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] updateDragVisual error: " + t);
        }
    }

    /** Restore the dragged item before the swap is applied. */
    private static void endDragVisual() {
        if (sDragView == null) return;
        try {
            sDragView.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
            sDragView.setTranslationX(0f);
            sDragView.setTranslationY(0f);
            sDragView.setElevation(0f);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] endDragVisual error: " + t);
        }
        sDragView = null;
    }

    /**
     * Auto-scroll toward the left/right edge while the finger is parked in the hot-zone,
     * letting the user carry an item across pages.
     */
    private static void handleEdgeScroll(MotionEvent ev) {
        if (!(sRecycler instanceof View)) return;
        try {
            final View rv = (View) sRecycler;
            int w = rv.getWidth();
            if (w <= 0) return;
            float relX = ev.getX();
            float zone = EDGE_ZONE_PX;

            int dir = 0;
            float depth = 0f;
            if (relX >= w - zone) { dir = 1; depth = (relX - (w - zone)) / zone; }
            else if (relX <= zone) { dir = -1; depth = (zone - relX) / zone; }

            if (dir == 0) { stopEdgeScroll(); return; }
            final int direction = dir;
            final float d = Math.max(0f, Math.min(1f, depth));

            // Recreate/refresh the ticker on every MOVE so speed & direction track the finger.
            Runnable ticker = new Runnable() {
                @Override public void run() {
                    try {
                        if (!sDragging || sDragView == null) { stopEdgeScroll(); return; }
                        if (sScrollTicker[0] != this) { return; } // superseded by a newer ticker
                        int step = (int) (EDGE_SCROLL_MIN + (EDGE_SCROLL_MAX - EDGE_SCROLL_MIN) * d);
                        XposedHelpers.callMethod(sRecycler, "scrollBy", direction * step, 0);
                        if (sLastMoveRawX != null && sLastMoveRawY != null) {
                            int p = positionUnderRaw(sLastMoveRawX, sLastMoveRawY);
                            if (p >= 0) sDragPos = p;
                            updateDragVisualRaw(sLastMoveRawX, sLastMoveRawY);
                        }
                        rv.postOnAnimation(this);
                    } catch (Throwable t) {
                        stopEdgeScroll();
                    }
                }
            };
            sScrollTicker[0] = ticker;
            rv.postOnAnimation(ticker);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] handleEdgeScroll error: " + t);
        }
    }

    private static void stopEdgeScroll() {
        sScrollTicker[0] = null;
    }

    private static void saveOrder() {
        try {
            persistOrderNow();
            // Also update the handler's live lists that the adapter actually reads,
            // so the reorder is visible immediately (not only after closing/reopening).
            syncHandlerLists();
            if (sAdapter != null) {
                XposedHelpers.callMethod(sAdapter, "notifyDataSetChanged");
            }
            XposedBridge.log("[SBPlus] saveOrder done (" + sItems.size() + " items)");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] saveOrder error: " + t);
        }
    }

    /** Persist the current sItems order to Samsung's customize-toolbar storage (no UI refresh). */
    private static void persistOrderNow() {
        try {
            if (sHandler == null || sItems == null || sItems.isEmpty()) return;
            for (MenuItem it : sItems) {
                if (it != null) it.setChecked(true);
            }
            StringBuilder orderLog = new StringBuilder("persist order=");
            for (MenuItem it : sItems) {
                if (it != null) orderLog.append(String.valueOf(it.getTitle())).append("|");
            }
            XposedBridge.log("[SBPlus] " + orderLog);
            Class<?> mgrCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.customize_toolbar.CustomizeToolbarManager", sCl);
            Object mgr = XposedHelpers.callStaticMethod(mgrCls, "getInstance");
            XposedHelpers.callMethod(mgr, "saveToolsMenu", sItems);
            syncHandlerLists();
            XposedBridge.log("[SBPlus] saveToolsMenu persisted (" + sItems.size() + " items)");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] persistOrderNow error: " + t);
        }
    }

    /**
     * Write the reordered flat list back into the handler's real mPrimaryMenuItems /
     * mSecondaryMenuItems lists (A-scheme: all flat, so all go to primary, secondary emptied).
     */
    private static void syncHandlerLists() {
        try {
            if (sHandler == null) return;
            if (sCachedPrimary != null) {
                sCachedPrimary.clear();
                sCachedPrimary.addAll(sItems);
            }
            if (sCachedSecondary != null) {
                sCachedSecondary.clear();
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] syncHandlerLists error: " + t);
        }
    }

    // ---------------------------------------------------------------------------------
    // Add / remove (show / hide) support — driven by MenuEditHelper.
    // ---------------------------------------------------------------------------------

    /** Build the flat sItems from the handler's cached lists (call before add/remove). */
    public static void ensureFlatItems() {
        if (sItems == null) {
            ArrayList<MenuItem> flat = new ArrayList<>();
            if (sCachedPrimary != null) flat.addAll(sCachedPrimary);
            if (sCachedSecondary != null) flat.addAll(sCachedSecondary);
            sItems = flat;
        }
    }

    /** Current menu grid window bounds (for positioning the add panel right above it). */
    public static int[] menuBoundsOnScreen() {
        try {
            if (sRecycler instanceof View) {
                View rv = (View) sRecycler;
                int[] loc = new int[2];
                rv.getLocationOnScreen(loc);
                return new int[]{ loc[0], loc[1], loc[0] + rv.getWidth(), loc[1] + rv.getHeight() };
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] menuBoundsOnScreen error: " + t);
        }
        return null;
    }

    /** The RecyclerView itself, used as the anchor to show the add panel above the menu. */
    public static View menuAnchorView() {
        return sRecycler instanceof View ? (View) sRecycler : null;
    }

    /** Add a MenuItem (show it) — append to the flat list and persist. */
    public static void addItem(MenuItem item) {
        try {
            if (item == null) return;
            ensureFlatItems();
            if (!sItems.contains(item)) sItems.add(item);
            saveOrder();
            com.sbplus.browser.MainHook.refreshIndicatorIfNeeded();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] addItem error: " + t);
        }
    }

    /** Remove a MenuItem (hide it) — drop from flat list, keep checked=false, persist. */
    public static void removeItem(MenuItem item) {
        try {
            if (item == null) return;
            ensureFlatItems();
            if (item != null) item.setChecked(false);
            sItems.remove(item);
            saveAfterRemove();
            com.sbplus.browser.MainHook.refreshIndicatorIfNeeded();
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] removeItem error: " + t);
        }
    }

    /** Persist after a removal (must NOT force checked=true on the removed item). */
    private static void saveAfterRemove() {
        try {
            if (sHandler == null || sItems == null) return;
            // Force shown=true on every remaining item — Samsung's setToolsMenuList keeps only
            // checked items, so without this the menu collapses (the earlier wipe bug).
            for (MenuItem it : sItems) {
                if (it != null) it.setChecked(true);
            }
            Class<?> mgrCls = XposedHelpers.findClass(
                    "com.sec.android.app.sbrowser.common.customize_toolbar.CustomizeToolbarManager", sCl);
            Object mgr = XposedHelpers.callStaticMethod(mgrCls, "getInstance");
            XposedHelpers.callMethod(mgr, "saveToolsMenu", sItems);
            syncHandlerLists();
            if (sAdapter != null) {
                XposedHelpers.callMethod(sAdapter, "notifyDataSetChanged");
            }
            XposedBridge.log("[SBPlus] removed item, saved " + sItems.size() + " remaining");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] saveAfterRemove error: " + t);
        }
    }

    // ---------------------------------------------------------------------------------
    // ✕ delete marks (shown while in edit mode).
    // ---------------------------------------------------------------------------------

    private static final java.util.Map<View, View> sRemoveMarks = new java.util.HashMap<>();

    /** Draw a small ✕ badge on the top-right of every visible icon. */
    private static void showDeleteMarks() {
        try {
            if (!(sRecycler instanceof View)) return;
            hideDeleteMarks();
            View rv = (View) sRecycler;
            // Allow ✕ marks to draw slightly outside item bounds without being clipped.
            if (rv instanceof ViewGroup) {
                ((ViewGroup) rv).setClipChildren(false);
                ((ViewGroup) rv).setClipToPadding(false);
            }
            int childCount = ((Number) XposedHelpers.callMethod(sRecycler, "getChildCount")).intValue();
            for (int i = 0; i < childCount; i++) {
                View child = (View) XposedHelpers.callMethod(sRecycler, "getChildAt", i);
                if (child == null || !(child instanceof ViewGroup)) continue;
                int pos = ((Number) XposedHelpers.callMethod(sRecycler, "getChildLayoutPosition", child)).intValue();
                if (pos < 0 || sItems == null || pos >= sItems.size()) continue;
                // Block the original click while editing (and remember its clickable state).
                child.setTag(0x7f0f0001, Boolean.valueOf(child.isClickable()));
                child.setClickable(false);
                // Remove any stale mark already on this (recycled) child, then re-draw.
                View old = sRemoveMarks.remove(child);
                if (old != null && old.getParent() instanceof ViewGroup) {
                    ((ViewGroup) old.getParent()).removeView(old);
                }
                child.setTag(0x7f0f0002, pos);
                addDeleteMark((ViewGroup) child, pos);
            }
            XposedBridge.log("[SBPlus] delete marks shown on " + sRemoveMarks.size() + " items");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showDeleteMarks error: " + t);
        }
    }

    private static void hideDeleteMarks() {
        try {
            for (View mark : sRemoveMarks.values()) {
                View p = (View) mark.getParent();
                if (p instanceof ViewGroup) ((ViewGroup) p).removeView(mark);
            }
            sRemoveMarks.clear();
            // Belt-and-suspenders: scan every current child and remove any lingering ✕
            // (covers marks whose parent changed after a page-scroll/recycle).
            if (sRecycler instanceof View) {
                int n = ((Number) XposedHelpers.callMethod(sRecycler, "getChildCount")).intValue();
                for (int i = 0; i < n; i++) {
                    View child = (View) XposedHelpers.callMethod(sRecycler, "getChildAt", i);
                    if (child instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) child;
                        for (int j = vg.getChildCount() - 1; j >= 0; j--) {
                            View c = vg.getChildAt(j);
                            Object marker = c.getTag(0x7f0f0003);
                            if ("SBPlus-RemoveMark".equals(marker)) {
                                vg.removeView(c);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] hideDeleteMarks error: " + t);
        }
    }

    /**
     * Called from onBindViewHolder after-hook: while in edit mode, give a freshly-bound
     * (page-scrolled-into-view) item its ✕ mark and block its original click.
     */
    public static void decorateBoundItem(Object holder, int pos) {
        try {
            View child = (View) XposedHelpers.getObjectField(holder, "itemView");
            if (child == null || !(child instanceof ViewGroup)) return;
            ViewGroup vg = (ViewGroup) child;
            // Always strip any lingering ✕ on a (re)bound item, regardless of edit mode —
            // recycled off-screen items can carry a stale mark back into view.
            for (int j = vg.getChildCount() - 1; j >= 0; j--) {
                View c = vg.getChildAt(j);
                if ("SBPlus-RemoveMark".equals(c.getTag(0x7f0f0003))) {
                    vg.removeView(c);
                    sRemoveMarks.remove(child);
                }
            }
            if (!sEditMode || sItems == null || pos < 0 || pos >= sItems.size()) return;
            disableClickTree(child);
            View old = sRemoveMarks.remove(child);
            if (old != null && old.getParent() instanceof ViewGroup) {
                ((ViewGroup) old.getParent()).removeView(old);
            }
            child.setTag(0x7f0f0002, pos);
            addDeleteMark((ViewGroup) child, pos);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] decorateBoundItem error: " + t);
        }
    }

    private static void addDeleteMark(ViewGroup child, int pos) {
        try {
            if (!(sRecycler instanceof View)) return;
            View rv = (View) sRecycler;
            android.content.Context rc = rv.getContext();
            TextView mark = new TextView(rc);
            mark.setText("✕");
            mark.setTextColor(Color.rgb(0xFF, 0xFF, 0xFF));
            mark.setTextSize(10f);
            mark.setGravity(Gravity.CENTER);
            float d = rc.getResources().getDisplayMetrics().density;
            int size = (int) (20f * d);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.rgb(0xE5, 0x39, 0x35));
            mark.setBackground(bg);
            mark.setTag(pos);
            // Unique marker so we can reliably find and remove this ✕ later.
            mark.setTag(0x7f0f0003, "SBPlus-RemoveMark");

        int iconId = rc.getResources().getIdentifier("icon", "id", rc.getPackageName());
        View icon = iconId != 0 ? child.findViewById(iconId) : null;
        RelativeLayout.LayoutParams rlp = new RelativeLayout.LayoutParams(size, size);
        rlp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        rlp.addRule(RelativeLayout.ALIGN_PARENT_START);

        child.addView(mark, rlp);
        mark.bringToFront();

        // Wait until the icon is actually laid out (paged lazy layout means one post may not
        // be enough), then pin the ✕ to the icon's top-right corner using absolute coords.
        final Runnable pin = new Runnable() {
            @Override public void run() {
                try {
                    View ic = iconId != 0 ? child.findViewById(iconId) : null;
                    if (ic == null || ic.getWidth() <= 0) { child.post(this); return; }
                    int[] il = new int[2];
                    ic.getLocationInWindow(il);
                    int[] cl = new int[2];
                    child.getLocationInWindow(cl);
                    float iconLeft = il[0] - cl[0];
                    float iconTop = il[1] - cl[1];
                    float x = iconLeft + ic.getWidth() - mark.getWidth();
                    float y = Math.max(0f, iconTop);
                    mark.setX(x);
                    mark.setY(y);
                    XposedBridge.log("[SBPlus] mark pinned iconLeft=" + iconLeft
                            + " iconTop=" + iconTop + " x=" + x + " y=" + y);
                } catch (Throwable t) {
                    XposedBridge.log("[SBPlus] mark pin error: " + t);
                }
            }
        };
        child.post(pin);
            mark.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Integer p = (Integer) v.getTag();
                    if (p != null && sItems != null && p >= 0 && p < sItems.size()) {
                        MenuItem item = sItems.get(p);
                        removeItem(item);
                        showDeleteMarks();
                    }
                }
            });
            sRemoveMarks.put(child, mark);
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] addDeleteMark error: " + t);
        }
    }

    private static View hitRemoveMark(float rawX, float rawY) {
        for (java.util.Map.Entry<View, View> e : sRemoveMarks.entrySet()) {
            View mark = e.getValue();
            if (mark.getVisibility() != View.VISIBLE) continue;
            int[] loc = new int[2];
            mark.getLocationOnScreen(loc);
            if (rawX >= loc[0] && rawX <= loc[0] + mark.getWidth()
                    && rawY >= loc[1] && rawY <= loc[1] + mark.getHeight()) {
                return mark;
            }
        }
        return null;
    }
}
