package io.github.iamth3doctor.searchlight.testiconpack;

import android.app.Activity;
import android.os.Bundle;

/** Only here so the pack can declare the icon-pack intents; it shows nothing. */
public class PackActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
