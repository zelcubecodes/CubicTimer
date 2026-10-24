package com.hatopigeon.cubictimer.wca;

import android.content.Context;
import android.widget.TextView;

/**
 * A TextView that swaps flag emojis for real flag pictures (flag-icons by Panayiotis Lipiridis,
 * MIT License, https://github.com/lipis/flag-icons). Everything else about it is a normal TextView.
 */
public class FlagTextView extends TextView {

    public FlagTextView(Context context) {
        super(context);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(WcaFlags.spanify(getContext(), text), type);
    }
}
