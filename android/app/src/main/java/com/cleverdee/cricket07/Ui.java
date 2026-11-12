package com.cleverdee.cricket07;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/* The launcher screens' look, after the game's own front end: a black sky fading into floodlit grass with faint
   swirls, a red ball, chrome, and bold white italic capitals on slanted bars that light up green. Everything is
   drawn here; nothing is taken from the game's files. */
final class Ui {
    static final int WHITE = Color.WHITE, SOFT = Color.rgb(214, 228, 214), DIM = Color.rgb(150, 172, 152);
    static final int GREEN = Color.rgb(92, 196, 48), RED = Color.rgb(200, 28, 34);
    private static final Typeface BOLD_ITALIC = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC);
    private static final Typeface CONDENSED = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

    private Ui() {}

    /* A page: the backdrop, the title and text column on the left (returned), the menu column on the right
       (side(): buttons go there by themselves), and the credit along the bottom left. Both columns scroll. */
    static LinearLayout page(Activity a) {
        FrameLayout frame = new FrameLayout(a);
        frame.setBackground(new Backdrop());
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = column(a), right = column(a);
        left.setTag(right);
        row.addView(scroller(a, left), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));
        row.addView(scroller(a, right), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        frame.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView credit = new TextView(a);
        credit.setText("Made by Cleverdee");
        credit.setTextSize(11);
        credit.setTextColor(Color.argb(150, 255, 255, 255));
        credit.setTypeface(CONDENSED);
        credit.setLetterSpacing(0.08f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                   ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.bottomMargin = dp(a, 22);
        lp.leftMargin = dp(a, 24);
        frame.addView(credit, lp);
        a.setContentView(frame);
        return left;
    }

    /* empties both columns of a page */
    static void clear(LinearLayout col) {
        side(col).removeAllViews();
        col.removeAllViews();
    }

    /* the menu column of a page */
    static LinearLayout side(LinearLayout col) {
        return col.getTag() instanceof LinearLayout ? (LinearLayout) col.getTag() : col;
    }

    private static LinearLayout column(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 30));
        return col;
    }

    private static ScrollView scroller(Context c, View content) {
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(content);
        return sv;
    }

    /* "CRICKET 07" with a red ball for the O, and a line under it */
    static void title(LinearLayout col, String sub) {
        Context c = col.getContext();
        col.addView(new Logo(c), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 58)));
        if (sub != null) {
            TextView t = text(col, sub.toUpperCase(java.util.Locale.US), 13, GREEN);
            t.setTypeface(BOLD_ITALIC);
            t.setLetterSpacing(0.25f);
        }
    }

    /* a heading for a sub-page */
    static TextView heading(LinearLayout col, String s) {
        TextView t = text(col, s.toUpperCase(java.util.Locale.US), 22, WHITE);
        t.setTypeface(BOLD_ITALIC);
        t.setLetterSpacing(0.04f);
        return t;
    }

    static TextView text(LinearLayout col, String s, int sp, int color) {
        Context c = col.getContext();
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(CONDENSED);
        t.setShadowLayer(dp(c, 3), 0, dp(c, 1), Color.argb(200, 0, 0, 0));
        t.setPadding(0, dp(c, 4), 0, dp(c, 4));
        t.setMaxWidth(dp(c, 600));
        col.addView(t);
        return t;
    }

    /* a menu item: a slanted dark bar with white capitals; green while pressed */
    static MenuButton button(LinearLayout col, String s, View.OnClickListener l) {
        return button(col, s, false, l);
    }

    /* small: a secondary action, narrower and quieter */
    static MenuButton button(LinearLayout col, String s, boolean small, View.OnClickListener l) {
        Context c = col.getContext();
        col = side(col);
        MenuButton b = new MenuButton(c, s, small);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, small ? 250 : 300), dp(c, small ? 38 : 46));
        lp.topMargin = dp(c, small ? 6 : 8);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(b, lp);
        return b;
    }

    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    /* ------------------------------------------------------------------ the backdrop */

    static final class Backdrop extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG), mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();

        @Override public void draw(Canvas c) {
            int w = getBounds().width(), h = getBounds().height();
            mPaint.setShader(new LinearGradient(0, 0, 0, h, new int[] { Color.rgb(2, 5, 3), Color.rgb(4, 22, 8), Color.rgb(16, 74, 14),
                                                                         Color.rgb(58, 140, 30) },
                                                new float[] { 0f, 0.45f, 0.8f, 1f }, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, mPaint);
            /* floodlight glow low in the middle */
            mPaint.setShader(new RadialGradient(w * 0.5f, h * 1.05f, Math.max(w, h) * 0.6f,
                                                Color.argb(110, 150, 230, 90), Color.argb(0, 150, 230, 90), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, mPaint);
            mPaint.setShader(null);
            /* faint swirls sweeping across */
            mLine.setStyle(Paint.Style.STROKE);
            mLine.setStrokeWidth(Math.max(1f, h / 400f));
            for (int i = 0; i < 7; i++) {
                float y0 = h * (0.35f + i * 0.09f), lift = h * (0.22f + i * 0.03f);
                mPath.reset();
                mPath.moveTo(-w * 0.1f, y0);
                mPath.cubicTo(w * 0.3f, y0 - lift, w * 0.6f, y0 + lift * 0.5f, w * 1.1f, y0 - lift * 0.4f);
                mLine.setColor(Color.argb(18 + i * 4, 170, 255, 120));
                c.drawPath(mPath, mLine);
            }
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter f) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /* ------------------------------------------------------------------ the title */

    static final class Logo extends View {
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG), mBall = new Paint(Paint.ANTI_ALIAS_FLAG),
                            mSeam = new Paint(Paint.ANTI_ALIAS_FLAG);

        Logo(Context c) {
            super(c);
            mText.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
            mText.setColor(WHITE);
            mText.setShadowLayer(dp(c, 4), 0, dp(c, 2), Color.argb(220, 0, 0, 0));
            mSeam.setStyle(Paint.Style.STROKE);
            mSeam.setColor(Color.argb(230, 255, 240, 220));
            setLayerType(LAYER_TYPE_SOFTWARE, null);   /* text shadows */
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), size = h * 0.72f;
            mText.setTextSize(size);
            String a = "CRICKET ", b = "07";
            float wa = mText.measureText(a), wb = mText.measureText(b), r = size * 0.4f, gap = size * 0.18f;
            float total = 2 * r + gap + wa + wb;
            float x = (getWidth() - total) / 2f, base = h * 0.78f;
            /* a cricket ball, then the name with 07 in green */
            float cx = x + r, cy = base - size * 0.36f;
            mBall.setShader(new RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.3f,
                                               new int[] { Color.rgb(255, 120, 110), RED, Color.rgb(90, 6, 10) },
                                               new float[] { 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, mBall);
            mSeam.setStrokeWidth(Math.max(1f, r * 0.08f));
            c.drawArc(new RectF(cx - r * 1.55f, cy - r * 1.02f, cx + r * 0.2f, cy + r * 1.02f), -42, 84, false, mSeam);
            c.drawArc(new RectF(cx - r * 1.35f, cy - r * 1.02f, cx + r * 0.4f, cy + r * 1.02f), -42, 84, false, mSeam);
            float tx = x + 2 * r + gap;
            mText.setColor(WHITE);
            c.drawText(a, tx, base, mText);
            mText.setColor(GREEN);
            c.drawText(b, tx + wa, base, mText);
            mText.setColor(WHITE);
            /* a thin green rule under the title */
            mBall.setShader(new LinearGradient(x, 0, x + total, 0, new int[] { Color.argb(0, 92, 196, 48), GREEN, Color.argb(0, 92, 196, 48) },
                                               null, Shader.TileMode.CLAMP));
            c.drawRect(x, h * 0.9f, x + total, h * 0.9f + Math.max(2f, h * 0.035f), mBall);
            mBall.setShader(null);
        }
    }

    /* ------------------------------------------------------------------ menu buttons */

    static final class MenuButton extends View {
        private final String mLabel;
        private final boolean mSmall;
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG), mText = new Paint(Paint.ANTI_ALIAS_FLAG),
                            mEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private boolean mDown;
        private String mNote;   /* optional right-hand detail, e.g. ON / OFF */

        MenuButton(Context c, String label, boolean small) {
            super(c);
            mLabel = label.toUpperCase(java.util.Locale.US);
            mSmall = small;
            mText.setTypeface(BOLD_ITALIC);
            mEdge.setStyle(Paint.Style.STROKE);
            setClickable(true);
            setFocusable(true);
        }

        void setNote(String note) { mNote = note; invalidate(); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: mDown = true; invalidate(); break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: mDown = false; invalidate(); break;
            }
            return super.onTouchEvent(e);
        }

        @Override public void setEnabled(boolean on) { super.setEnabled(on); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), slant = h * 0.32f, inset = dp(getContext(), 2);
            mPath.reset();
            mPath.moveTo(slant + inset, inset);
            mPath.lineTo(w - inset, inset);
            mPath.lineTo(w - slant - inset, h - inset);
            mPath.lineTo(inset, h - inset);
            mPath.close();
            boolean lit = mDown || isFocused();
            if (lit) mFill.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(140, 230, 80), Color.rgb(52, 150, 26), Shader.TileMode.CLAMP));
            else mFill.setShader(new LinearGradient(0, 0, 0, h, Color.argb(215, 34, 40, 36), Color.argb(225, 8, 12, 9), Shader.TileMode.CLAMP));
            c.drawPath(mPath, mFill);
            /* chrome edge, and a red tab at the leading end */
            mEdge.setStrokeWidth(Math.max(1f, h * 0.03f));
            mEdge.setShader(new LinearGradient(0, 0, 0, h, Color.argb(220, 235, 240, 235), Color.argb(150, 110, 120, 112), Shader.TileMode.CLAMP));
            c.drawPath(mPath, mEdge);
            mFill.setShader(null);
            mFill.setColor(lit ? Color.rgb(20, 60, 10) : RED);
            Path tab = new Path();
            tab.moveTo(slant + inset, inset);
            tab.lineTo(slant + inset + h * 0.16f, inset);
            tab.lineTo(inset + h * 0.16f, h - inset);
            tab.lineTo(inset, h - inset);
            tab.close();
            c.drawPath(tab, mFill);
            int alpha = isEnabled() ? 255 : 110;
            mText.setTextSize(h * (mSmall ? 0.38f : 0.4f));
            mText.setColor(lit ? Color.argb(alpha, 8, 20, 6) : Color.argb(alpha, 255, 255, 255));
            mText.setLetterSpacing(0.06f);
            float ty = h / 2f - (mText.descent() + mText.ascent()) / 2f;
            if (mNote == null) {
                mText.setTextAlign(Paint.Align.CENTER);
                c.drawText(mLabel, w / 2f, ty, mText);
            } else {
                mText.setTextAlign(Paint.Align.LEFT);
                c.drawText(mLabel, slant + h * 0.4f, ty, mText);
                mText.setTextAlign(Paint.Align.RIGHT);
                mText.setColor(lit ? Color.argb(alpha, 8, 20, 6) : Color.argb(alpha, 92, 196, 48));
                c.drawText(mNote, w - slant - h * 0.35f, ty, mText);
            }
        }
    }
}
