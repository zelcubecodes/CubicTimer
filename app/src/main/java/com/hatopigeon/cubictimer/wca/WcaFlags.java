package com.hatopigeon.cubictimer.wca;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.style.ReplacementSpan;

import com.hatopigeon.cubicify.R;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a WCA nationality (ISO code like "PH" or a country name like "Philippines") into a flag
 * emoji. Anything that cannot be matched to a real country gives an empty string, so a wrong flag
 * is never shown.
 *
 * The emoji is then shown as a real flag picture by {@link FlagTextView}. The pictures come from
 * flag-icons by Panayiotis Lipiridis (MIT License, https://github.com/lipis/flag-icons), packed
 * into one sprite sheet (res/drawable-nodpi/flags_sheet.png) in the order of SHEET_CODES.
 */
public final class WcaFlags {
    private static final Set<String> CODES = new HashSet<>();
    private static final Map<String, String> BY_NAME = new HashMap<>();

    static {
        for (String c : Locale.getISOCountries()) {
            CODES.add(c);
            String n = new Locale("", c).getDisplayCountry(Locale.ENGLISH);
            if (n != null && !n.isEmpty()) BY_NAME.put(norm(n), c);
        }
        CODES.add("XK"); // Kosovo
        String[] alias = {
                "korea", "KR", "south korea", "KR", "republic of korea", "KR",
                "democratic peoples republic of korea", "KP", "north korea", "KP",
                "czech republic", "CZ", "czechia", "CZ", "russia", "RU", "russian federation", "RU",
                "taiwan china", "TW", "taiwan", "TW", "hong kong china", "HK", "hong kong", "HK",
                "macau china", "MO", "macau", "MO", "macao", "MO", "palestine", "PS",
                "palestinian territories", "PS", "vietnam", "VN", "viet nam", "VN", "iran", "IR",
                "laos", "LA", "moldova", "MD", "syria", "SY", "tanzania", "TZ", "turkey", "TR",
                "turkiye", "TR", "swaziland", "SZ", "eswatini", "SZ", "cape verde", "CV",
                "cabo verde", "CV", "congo", "CG", "democratic republic of the congo", "CD",
                "dr congo", "CD", "ivory coast", "CI", "cote divoire", "CI", "brunei", "BN",
                "bolivia", "BO", "venezuela", "VE", "micronesia", "FM", "kosovo", "XK",
                "north macedonia", "MK", "macedonia", "MK", "myanmar", "MM", "burma", "MM",
                "united states", "US", "usa", "US", "united kingdom", "GB", "uk", "GB",
                "timor leste", "TL", "east timor", "TL", "sao tome and principe", "ST",
                "vatican city", "VA", "gambia", "GM", "the gambia", "GM", "bahamas", "BS"
        };
        for (int i = 0; i + 1 < alias.length; i += 2) BY_NAME.put(alias[i], alias[i + 1]);
    }

    private WcaFlags() {}

    private static String norm(String s) {
        String t = s.toLowerCase(Locale.ENGLISH).replace("&", "and");
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Returns an upper-case ISO code for a code or country name, or "" when unknown. */
    public static String code(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) return "";
        if (v.length() == 2) {
            String u = v.toUpperCase(Locale.US);
            return CODES.contains(u) ? u : "";
        }
        String c = BY_NAME.get(norm(v));
        return c == null ? "" : c;
    }

    /** Flag emoji for a code or country name, or "" when unknown. */
    public static String emoji(String value) {
        String c = code(value);
        if (c.length() != 2) return "";
        int a = Character.codePointAt(c, 0) - 'A' + 0x1F1E6;
        int b = Character.codePointAt(c, 1) - 'A' + 0x1F1E6;
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    // ---- flag pictures (flag-icons, MIT License, (c) 2013 Panayiotis Lipiridis) ----

    private static final String SHEET_CODES =
            "ad,ae,af,ag,ai,al,am,ao,aq,ar,as,at,au,aw,ax,az,ba,bb,bd,be,bf,bg,bh,bi,bj,bl,bm,bn,bo,bq,br,bs,bt,bv,bw,by,bz,ca,cc,cd,cf,cg,ch,ci,ck,cl,cm,cn,co,cp,cr,cu,cv,cw,cx,cy,cz,de,dg,dj,dk,dm,do,dz,ec,ee,eg,eh,er,es,et,eu,fi,fj,fk,fm,fo,fr,ga,gb,gd,ge,gf,gg,gh,gi,gl,gm,gn,gp,gq,gr,gs,gt,gu,gw,gy,hk,hm,hn,hr,ht,hu,ic,id,ie,il,im,in,io,iq,ir,is,it,je,jm,jo,jp,ke,kg,kh,ki,km,kn,kp,kr,kw,ky,kz,la,lb,lc,li,lk,lr,ls,lt,lu,lv,ly,ma,mc,md,me,mf,mg,mh,mk,ml,mm,mn,mo,mp,mq,mr,ms,mt,mu,mv,mw,mx,my,mz,na,nc,ne,nf,ng,ni,nl,no,np,nr,nu,nz,om,pa,pc,pe,pf,pg,ph,pk,pl,pm,pn,pr,ps,pt,pw,py,qa,re,ro,rs,ru,rw,sa,sb,sc,sd,se,sg,sh,si,sj,sk,sl,sm,sn,so,sr,ss,st,sv,sx,sy,sz,tc,td,tf,tg,th,tj,tk,tl,tm,tn,to,tr,tt,tv,tw,tz,ua,ug,um,un,us,uy,uz,va,vc,ve,vg,vi,vn,vu,wf,ws,xk,xx,ye,yt,za,zm,zw";
    private static final int SHEET_COLS = 16;
    private static final int CELL_W = 80;
    private static final int CELL_H = 60;
    private static final Map<String, Integer> SHEET_INDEX = new HashMap<>();
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static Bitmap sheet;

    static {
        String[] all = SHEET_CODES.split(",");
        for (int i = 0; i < all.length; i++) SHEET_INDEX.put(all[i].toUpperCase(Locale.US), i);
    }

    /** The flag picture for an upper-case ISO code, or null when there is none. */
    private static synchronized Bitmap picture(Context ctx, String iso) {
        Integer idx = SHEET_INDEX.get(iso);
        if (idx == null) return null;
        Bitmap b = CACHE.get(iso);
        if (b != null) return b;
        try {
            if (sheet == null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;
                sheet = BitmapFactory.decodeResource(ctx.getApplicationContext().getResources(),
                        R.drawable.flags_sheet, o);
            }
            if (sheet == null) return null;
            b = Bitmap.createBitmap(sheet, (idx % SHEET_COLS) * CELL_W, (idx / SHEET_COLS) * CELL_H,
                    CELL_W, CELL_H);
            CACHE.put(iso, b);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Draws a flag picture as wide as a 4:3 flag at about the height of a capital letter. */
    private static final class FlagSpan extends ReplacementSpan {
        private final Bitmap bitmap;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Rect src;

        FlagSpan(Bitmap bitmap) {
            this.bitmap = bitmap;
            this.src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        private static float height(Paint paint) {
            return paint.getTextSize() * 0.8f;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            return Math.round(height(paint) * 4f / 3f);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top,
                         int y, int bottom, Paint paint) {
            float h = height(paint);
            float w = h * 4f / 3f;
            bitmapPaint.setAlpha(paint.getAlpha());
            canvas.drawBitmap(bitmap, src, new RectF(x, y - h, x + w, y), bitmapPaint);
        }
    }

    /**
     * Replaces every flag emoji in the text by its flag picture. Text without flags, and flags
     * without a picture, are returned unchanged.
     */
    public static CharSequence spanify(Context ctx, CharSequence text) {
        if (text == null || text.length() < 4) return text;
        SpannableStringBuilder out = null;
        int n = text.length();
        for (int i = 0; i + 3 < n; i++) {
            int a = Character.codePointAt(text, i);
            if (a < 0x1F1E6 || a > 0x1F1FF) continue;
            int ia = Character.charCount(a);
            if (i + ia >= n) break;
            int b = Character.codePointAt(text, i + ia);
            if (b < 0x1F1E6 || b > 0x1F1FF) { i += ia - 1; continue; }
            int len = ia + Character.charCount(b);
            String iso = "" + (char) ('A' + a - 0x1F1E6) + (char) ('A' + b - 0x1F1E6);
            Bitmap pic = picture(ctx, iso);
            if (pic != null) {
                if (out == null) out = new SpannableStringBuilder(text);
                out.setSpan(new FlagSpan(pic), i, i + len, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i += len - 1;
        }
        return out == null ? text : out;
    }
}
