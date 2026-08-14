package com.sbplus.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Injects a "+" button into the menu's bottom nav bar. Tapping it opens a dialog listing
 * every icon currently not shown in the grid; tapping one adds it (checked=true) and persists.
 */
public final class MenuAddButtonHelper {

    private MenuAddButtonHelper() {}

    private static android.widget.PopupWindow sActivePopup;

    public static void injectAddButton(final View recycler) {
        try {
            final ViewGroup root = findDialogRoot(recycler);
            if (root == null) return;
            final ViewGroup bottomRow = findBottomRow(root);
            if (bottomRow == null) return;
            if (findExistingAddButton(bottomRow)) return;

            final Context ctx = recycler.getContext();
            final ImageButton addBtn = new ImageButton(ctx);
            addBtn.setBackground(null);
            addBtn.setContentDescription("添加图标");
            addBtn.setImageResource(android.R.drawable.ic_input_add);
            addBtn.setColorFilter(Color.rgb(0x11, 0x11, 0x11));
            addBtn.setPadding(0, 0, 0, 0);
            int size = (int) (48f * recycler.getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.gravity = Gravity.CENTER_VERTICAL;
            addBtn.setLayoutParams(lp);

            addBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showAddDialog(ctx);
                }
            });

            bottomRow.addView(addBtn);
            XposedBridge.log("[SBPlus] add button injected into bottom nav");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] injectAddButton error: " + t);
        }
    }

    private static ViewGroup findDialogRoot(View recycler) {
        // recycler -> dialog_container (LinearLayout vertical) -> root
        View p = (View) recycler.getParent();
        return p instanceof ViewGroup ? (ViewGroup) p : null;
    }

    private static ViewGroup findBottomRow(ViewGroup dialogRoot) {
        Context c = dialogRoot.getContext();
        int bottomId = c.getResources().getIdentifier("bottom_menu_container", "id", c.getPackageName());
        if (bottomId == 0) return null;
        View bottom = dialogRoot.findViewById(bottomId);
        if (!(bottom instanceof ViewGroup)) return null;
        // bottom_menu_container is vertical: [divider][horizontal row of buttons]
        ViewGroup vg = (ViewGroup) bottom;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child instanceof LinearLayout
                    && ((LinearLayout) child).getOrientation() == LinearLayout.HORIZONTAL) {
                return (ViewGroup) child;
            }
        }
        return null;
    }

    private static boolean findExistingAddButton(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof ImageButton
                    && "添加图标".equals(c.getContentDescription())) return true;
        }
        return false;
    }

    static void showAddDialog(final Context ctx) {
        try {
            MenuEditHelper.setClassLoader(ctx.getClassLoader());
            java.util.List<MenuItem> addable = MenuEditHelper.getAddableMenus();
            if (addable == null || addable.isEmpty()) {
                android.widget.Toast.makeText(ctx, "没有可添加的图标", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            // Build the panel; its ✕ and cell clicks dismiss via sActivePopup.
            final int panelHeight = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.55f);
            View panel = buildAddPanel(ctx, addable);
            final android.widget.PopupWindow pop = new android.widget.PopupWindow(
                    panel, ViewGroup.LayoutParams.MATCH_PARENT, panelHeight, true);
            sActivePopup = pop;
            pop.setBackgroundDrawable(new GradientDrawable());
            pop.setOutsideTouchable(true);
            pop.setFocusable(true);

            // Anchor it just above the menu sheet: show at the menu sheet's top-left,
            // offset upward by the panel height.
            View anchor = (View) MenuReorderHelper.menuAnchorView();
            int[] loc = new int[2];
            if (anchor != null) {
                anchor.getLocationInWindow(loc);
                pop.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT,
                        loc[0], loc[1] - panelHeight);
                XposedBridge.log("[SBPlus] add popup above menu yRel=" + (loc[1] - panelHeight)
                        + " menuTopRel=" + loc[1] + " panelH=" + panelHeight);
            } else {
                pop.showAtLocation(panel, Gravity.BOTTOM, 0, 0);
                XposedBridge.log("[SBPlus] add popup bottom (no anchor)");
            }
            XposedBridge.log("[SBPlus] add dialog shown (" + addable.size() + " addable)");
        } catch (Throwable t) {
            XposedBridge.log("[SBPlus] showAddDialog error: " + t);
        }
    }

    private static View buildAddPanel(Context ctx, List<MenuItem> addable) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0x28, 0x28, 0x28));
        float d = ctx.getResources().getDisplayMetrics().density;
        root.setPadding((int)(16*d), (int)(12*d), (int)(16*d), (int)(16*d));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(ctx);
        title.setText("添加图标");
        title.setTextSize(18f);
        title.setTextColor(Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(18f);
        close.setTextColor(Color.rgb(0xCC, 0xCC, 0xCC));
        close.setPadding((int)(12*d), (int)(4*d), (int)(4*d), (int)(4*d));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (sActivePopup != null) sActivePopup.dismiss(); }
        });
        header.addView(close);
        root.addView(header);

        android.widget.GridLayout grid = new android.widget.GridLayout(ctx);
        grid.setColumnCount(4);
        grid.setUseDefaultMargins(false);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(grid);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final float dd = d;
        for (final MenuItem item : addable) {
            android.widget.GridLayout.LayoutParams gp = new android.widget.GridLayout.LayoutParams();
            gp.width = 0;
            gp.setGravity(Gravity.FILL_HORIZONTAL);
            gp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);

            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding((int)(6*dd), (int)(12*dd), (int)(6*dd), (int)(12*dd));

            android.widget.ImageView iv = new android.widget.ImageView(ctx);
            try {
                if (item.getIcon() != null) {
                    iv.setImageDrawable(item.getIcon());
                    iv.setColorFilter(Color.WHITE);
                }
            } catch (Throwable ignored) {}
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    (int)(40*dd), (int)(40*dd));
            ilp.gravity = Gravity.CENTER_HORIZONTAL;
            cell.addView(iv, ilp);

            TextView lbl = new TextView(ctx);
            lbl.setText(String.valueOf(item.getTitle()));
            lbl.setTextSize(11f);
            lbl.setTextColor(Color.WHITE);
            lbl.setGravity(Gravity.CENTER);
            lbl.setSingleLine(true);
            cell.addView(lbl, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            cell.setBackgroundResource(android.R.drawable.list_selector_background);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    MenuReorderHelper.addItem(item);
                    // Keep the panel open so the user can add several icons in one go.
                    XposedBridge.log("[SBPlus] added " + item.getTitle() + " (panel stays open)");
                }
            });
            grid.addView(cell, gp);
        }
        return root;
    }
}
