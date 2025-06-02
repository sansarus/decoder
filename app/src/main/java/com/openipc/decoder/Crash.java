/*
 * Part of the OpenIPC project — https://openipc.org
 * Targets: IP cameras and embedded Linux (POSIX, uClibc/musl/glibc)
 * Contact: tech@openipc.eu
 * License: MIT
 */

package com.openipc.decoder;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

public class Crash extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView errorView = new TextView(this);
        errorView.setTextSize(12);
        errorView.setTextColor(Color.RED);
        errorView.setPadding(24, 12, 24, 12);

        String error = getIntent().getStringExtra("error");
        errorView.setText(error);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(errorView);
        setContentView(scrollView);
    }
}
